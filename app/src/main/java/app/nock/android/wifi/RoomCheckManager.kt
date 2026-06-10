package app.nock.android.wifi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.WifiRoomDao
import app.nock.android.data.json.WifiLevelsJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.time.TimeSource
import app.nock.android.history.AlarmHistoryLogger
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
) {
    private val am: AlarmManager = ctx.getSystemService()!!

    /** Periodic tick (or window-start) alarm: check, then arm the next one. */
    suspend fun onCheckAlarm() {
        runCatching { runChecks() }
        rearm()
    }

    /** Free opportunistic check on device unlock; the tick alarm stays as-is. */
    suspend fun onUnlock() {
        runCatching { runChecks() }
    }

    /** Recompute and (re-)schedule the single check alarm from current state. */
    suspend fun rearm() {
        val at = RoomCheckPlanner.nextCheckAt(repo.getAllReminders(), time.nowMs())
        val pi = checkPendingIntent()
        am.cancel(pi)
        if (at != null) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private suspend fun runChecks() {
        val now = time.nowMs()
        val candidates = repo.getAllReminders().filter {
            val s = it.schedule as? Schedule.RoomAfter
            s != null && RoomCheckPlanner.isInWindow(s, it.nextFireAt, now)
        }
        if (candidates.isEmpty()) return

        // Cached results cost nothing; only chase a real scan when they're too
        // old to trust for "which room am I in right now".
        val scan = scanner.cachedScan()
            ?.takeIf { it.ageMs <= MAX_SCAN_AGE_MS && it.levels.size >= RoomFingerprints.MIN_SCAN_APS }
            ?: scanner.freshScan()
            ?: return

        val samplesByRoom = roomDao.getAllSamples()
            .groupBy({ it.roomId }, { WifiLevelsJson.decode(it.levelsJson) })
            .mapValues { (_, v) -> v.filterNotNull() }
        if (samplesByRoom.isEmpty()) return

        val match = RoomFingerprints.matchRoom(scan.levels, samplesByRoom) ?: return
        if (match.score < RoomFingerprints.MIN_MATCH_SCORE) return

        for (r in candidates) {
            val s = r.schedule as Schedule.RoomAfter
            if (s.roomId != match.roomId) continue
            if (engine.fireRoomReminder(r.id)) {
                history.roomDetected(r.name, roomDao.getRoom(s.roomId)?.name)
            }
        }
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
