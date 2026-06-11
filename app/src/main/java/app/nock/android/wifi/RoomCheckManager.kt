package app.nock.android.wifi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.getSystemService
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.WifiRoomDao
import app.nock.android.data.json.WifiLevelsJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.time.TimeSource
import app.nock.android.history.AlarmHistoryLogger
import app.nock.android.notif.NotificationPresenter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives room detection for RoomAfter reminders with the cheapest signal
 * available — no always-on scanning, no service:
 *
 *  - One self-re-arming AlarmManager alarm (see [RoomCheckPlanner]) ticks
 *    every ~15 min, but ONLY while a reminder's window is active; outside it
 *    the alarm sleeps until the next window start (so it also performs the
 *    fire-at-start check the moment a window opens).
 *  - Each device unlock during a window is a free extra check.
 *  - A check first reads the system's cached scan results (no radio work);
 *    only when those are stale does it request one real scan, which Android's
 *    background throttle may downgrade back to the cache. Misses are fine:
 *    the fallback deadline armed in the normal escalation pipeline catches
 *    them.
 *
 * Re-arm hooks: boot/time/timezone (BootReceiver), process start
 * (NockApplication) and reminder save/delete/restore. Once one alarm is
 * armed, every tick re-arms the next, so the chain is self-sustaining.
 */
@Singleton
class RoomCheckManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: NockRepository,
    private val roomDao: WifiRoomDao,
    private val scanner: WifiScanProvider,
    private val engine: EscalationEngine,
    private val time: TimeSource,
    private val history: AlarmHistoryLogger,
    private val notifier: NotificationPresenter,
) {
    private val am: AlarmManager = ctx.getSystemService()!!

    // Per-reminder "first detected in the room this window" stamps, backing the
    // grace/dwell guard. Persisted so the timer survives process death between
    // the ~15-min ticks; entries are scoped to a window and overwritten when a
    // newer window opens, so a stale stamp never short-circuits the wait.
    private val dwellPrefs: SharedPreferences =
        ctx.getSharedPreferences("room_dwell", Context.MODE_PRIVATE)

    /** Periodic tick (or window-start) alarm: check, then arm the next one. */
    suspend fun onCheckAlarm() {
        runCatching { runChecks() }
        rearm()
    }

    /**
     * Free opportunistic check on device unlock. Also re-arms, because the check
     * may have started a grace timer that needs its own wake-up.
     */
    suspend fun onUnlock() {
        runCatching { runChecks() }
        rearm()
    }

    /** Recompute and (re-)schedule the single check alarm from current state. */
    suspend fun rearm() {
        val reminders = repo.getAllReminders()
        val now = time.nowMs()
        val planned = RoomCheckPlanner.nextCheckAt(reminders, now)
        val grace = nextGraceCheckAt(reminders, now)
        val at = when {
            planned == null -> grace
            grace == null -> planned
            else -> minOf(planned, grace)
        }
        val pi = checkPendingIntent()
        am.cancel(pi)
        if (at != null) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private suspend fun runChecks() {
        val now = time.nowMs()
        val reminders = repo.getAllReminders()

        // Always surface a quiet, dismissable "mark me done" note for every room
        // reminder whose window is open — independent of the scan, so the user
        // has a manual completion path even when WiFi never produces a confident
        // match. Reminders outside their window get any stale note cleared.
        syncWindowNotifications(reminders, now)

        val candidates = reminders.filter {
            val s = it.schedule as? Schedule.RoomAfter
            s != null && RoomCheckPlanner.isInWindow(s, it.nextFireAt, now)
        }
        if (candidates.isEmpty()) return

        // Cached results cost nothing; only chase a real scan when they're too
        // old to trust for "which room am I in right now".
        val scan = scanner.cachedScan()
            ?.takeIf { it.ageMs <= MAX_SCAN_AGE_MS && it.levels.size >= RoomFingerprints.MIN_SCAN_APS }
            ?: scanner.freshScan()
            ?: return // a failed scan tells us nothing — leave dwell timers running

        val samplesByRoom = roomDao.getAllSamples()
            .groupBy({ it.roomId }, { WifiLevelsJson.decode(it.levelsJson) })
            .mapValues { (_, v) -> v.filterNotNull() }
        if (samplesByRoom.isEmpty()) return

        val matchedRoomId = RoomFingerprints.matchRoom(scan.levels, samplesByRoom)
            ?.takeIf { it.confident }
            ?.roomId

        for (r in candidates) {
            val s = r.schedule as Schedule.RoomAfter
            if (s.roomId != matchedRoomId) {
                // A good scan placed the user outside the room: reset the clock.
                clearDwell(r.id)
                continue
            }
            // In the room. Hold off until they've stayed graceMs, so merely
            // passing through doesn't ring.
            if (s.graceMs > 0L) {
                val windowStart = r.nextFireAt!! - s.fallbackMs
                val firstSeen = dwellStart(r.id, now, windowStart)
                if (now - firstSeen < s.graceMs) continue
            }
            if (engine.fireRoomReminder(r.id)) {
                // Detection consumed the window; the escalation owns the user's
                // attention now, so retire the manual note.
                notifier.cancelRoomWindow(r.id)
                history.roomDetected(r.name, roomDao.getRoom(s.roomId)?.name)
            }
            clearDwell(r.id)
        }
    }

    /**
     * Post the manual "mark done" note for every room reminder whose window is
     * open right now and clear it for every one whose window is not, so the note
     * appears at window start and is gone once the reminder is detected,
     * completed, or its window lapses. The fallback deadline doubles as the
     * note's auto-timeout (see [NotificationPresenter.showRoomWindow]), so the
     * deadline edge needs no tick of its own.
     */
    private suspend fun syncWindowNotifications(reminders: List<Reminder>, now: Long) {
        for (r in reminders) {
            val s = r.schedule as? Schedule.RoomAfter ?: continue
            val deadline = r.nextFireAt
            if (deadline != null && RoomCheckPlanner.isInWindow(s, deadline, now)) {
                notifier.showRoomWindow(r.id, r.name, roomDao.getRoom(s.roomId)?.name, deadline)
            } else {
                notifier.cancelRoomWindow(r.id)
            }
        }
    }

    /**
     * Earliest moment a pending grace timer elapses, so a detection that started
     * the dwell clock rings ~on time instead of waiting for the next tick. Reads
     * the persisted stamps, so it is correct even after a process restart.
     */
    private fun nextGraceCheckAt(reminders: List<Reminder>, now: Long): Long? {
        var next: Long? = null
        for (r in reminders) {
            val s = r.schedule as? Schedule.RoomAfter ?: continue
            if (s.graceMs <= 0L) continue
            val deadline = r.nextFireAt ?: continue
            if (!RoomCheckPlanner.isInWindow(s, deadline, now)) continue
            val firstSeen = dwellPrefs.getLong(r.id.toString(), 0L)
            if (firstSeen < deadline - s.fallbackMs) continue // no live dwell this window
            val graceAt = firstSeen + s.graceMs
            // Past → the tick owns it; at/after the deadline → the fallback does.
            if (graceAt <= now || graceAt >= deadline) continue
            if (next == null || graceAt < next) next = graceAt
        }
        return next
    }

    /** First-seen stamp for the current window, starting it at [now] if unset or stale. */
    private fun dwellStart(reminderId: Long, now: Long, windowStart: Long): Long {
        val existing = dwellPrefs.getLong(reminderId.toString(), 0L)
        if (existing >= windowStart) return existing
        dwellPrefs.edit().putLong(reminderId.toString(), now).apply()
        return now
    }

    private fun clearDwell(reminderId: Long) {
        dwellPrefs.edit().remove(reminderId.toString()).apply()
    }

    private fun checkPendingIntent(): PendingIntent {
        val intent = Intent(ctx, RoomCheckReceiver::class.java).apply {
            action = "${ctx.packageName}.ROOM_CHECK"
        }
        return PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        // Outside this age the cached scan may describe a room the user has
        // long left; prefer a real scan (or skip — the next tick/unlock or the
        // fallback deadline covers it).
        const val MAX_SCAN_AGE_MS = 6 * 60_000L

        // Distinct from AlarmScheduler's escalation-id request codes only by
        // targeting a different receiver class; any constant works.
        const val REQUEST_CODE = 1
    }
}
