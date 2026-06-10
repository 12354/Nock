package app.nock.android.wifi

import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure timing logic for the room-check alarm. The manager keeps exactly one
 * self-re-arming AlarmManager alarm: while any RoomAfter reminder's window is
 * active it ticks every [CHECK_INTERVAL_MS]; otherwise it sleeps until the
 * earliest upcoming window start (derived from the schedule, NOT from
 * nextFireAt, so a window already consumed today still re-arms tomorrow's).
 * No alarm at all is held when no RoomAfter reminder exists.
 */
object RoomCheckPlanner {
    const val CHECK_INTERVAL_MS = 15 * 60_000L

    /**
     * A reminder is checkable while its current window is open and unconsumed.
     * nextFireAt is the fallback deadline of the pending window; once room
     * detection fires, the engine rewrites it to the (past) detection moment,
     * which makes this false for the rest of the day — and Done moves it to
     * tomorrow's deadline, also false until tomorrow's start.
     */
    fun isInWindow(s: Schedule.RoomAfter, nextFireAt: Long?, now: Long): Boolean =
        nextFireAt != null && now < nextFireAt && now >= nextFireAt - s.fallbackMs

    /** Earliest window start strictly after [now]. */
    fun nextWindowStart(s: Schedule.RoomAfter, now: Long, zone: ZoneId): Long {
        val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val timeOfDay = LocalTime.of(s.afterMinutes / 60, s.afterMinutes % 60)
        for (offset in 0L..1L) {
            val start = nowDt.toLocalDate().plusDays(offset).atTime(timeOfDay)
                .atZone(zone).toInstant().toEpochMilli()
            if (start > now) return start
        }
        return now + 24 * 3_600_000L // unreachable: tomorrow's start is always future
    }

    /** When the check alarm should next fire, or null if nothing needs one. */
    fun nextCheckAt(reminders: List<Reminder>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        var next: Long? = null
        for (r in reminders) {
            val s = r.schedule as? Schedule.RoomAfter ?: continue
            val candidate = if (isInWindow(s, r.nextFireAt, now)) {
                val tick = now + CHECK_INTERVAL_MS
                // No tick at/past the deadline — the fallback escalation armed
                // for nextFireAt owns that moment; sleep to the next window.
                if (tick < r.nextFireAt!!) tick else nextWindowStart(s, now, zone)
            } else {
                nextWindowStart(s, now, zone)
            }
            if (next == null || candidate < next) next = candidate
        }
        return next
    }
}
