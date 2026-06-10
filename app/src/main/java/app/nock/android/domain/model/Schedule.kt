package app.nock.android.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

sealed class Schedule {
    abstract fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId = ZoneId.systemDefault()): Long?

    // True for schedules that have no future occurrence after a single
    // completion. Callers (the escalation engine, the today screen) use
    // this to drop "spent" reminders instead of leaving them in the list.
    // Adding a new Schedule subtype forces an explicit choice here.
    abstract val isOneTime: Boolean

    data class OneShot(val atEpochMs: Long) : Schedule() {
        override val isOneTime: Boolean = true
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            if (lastCompletedAt != null && lastCompletedAt >= atEpochMs) return null
            return atEpochMs
        }
    }

    data class Daily(val timesOfDayMinutes: List<Int>) : Schedule() {
        override val isOneTime: Boolean = false
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            return nextDailyOrWeekly(now, zone, daysOfWeek = null, timesOfDayMinutes = timesOfDayMinutes)
        }
    }

    data class Weekly(val daysOfWeek: Set<DayOfWeek>, val timesOfDayMinutes: List<Int>) : Schedule() {
        override val isOneTime: Boolean = false
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            return nextDailyOrWeekly(now, zone, daysOfWeek = daysOfWeek, timesOfDayMinutes = timesOfDayMinutes)
        }
    }

    data class Monthly(val dayOfMonth: Int, val timeOfDayMinutes: Int) : Schedule() {
        override val isOneTime: Boolean = false
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            val nowDt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            val time = LocalTime.of(timeOfDayMinutes / 60, timeOfDayMinutes % 60)
            for (offset in 0..2) {
                val month = nowDt.toLocalDate().plusMonths(offset.toLong())
                val safeDay = dayOfMonth.coerceAtMost(month.lengthOfMonth())
                val candidate = month.withDayOfMonth(safeDay).atTime(time)
                val candidateMs = candidate.atZone(zone).toInstant().toEpochMilli()
                if (candidateMs > now) return candidateMs
            }
            return null
        }
    }

    data class IntervalFromLast(val intervalMs: Long, val startAtMs: Long? = null) : Schedule() {
        override val isOneTime: Boolean = false
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            if (lastCompletedAt != null) {
                val next = lastCompletedAt + intervalMs
                return if (next < now) now else next
            }
            // First fire: respect explicit start time, or fall back to now + interval
            if (startAtMs != null) {
                return if (startAtMs >= now) startAtMs else now
            }
            return now + intervalMs
        }
    }

    // Fires the next time the device is unlocked after it was armed.
    // nextFireFrom returns the arming timestamp as a sentinel so the row
    // sorts naturally and rescheduleAll can tell pending from completed —
    // actual delivery happens via UnlockReceiver, not AlarmManager.
    data class OnUnlock(val armedAtMs: Long) : Schedule() {
        override val isOneTime: Boolean = true
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            if (lastCompletedAt != null && lastCompletedAt >= armedAtMs) return null
            return armedAtMs
        }
    }

    // Fires once per day when the user is detected (via the WiFi room
    // fingerprint) in room [roomId] after [afterMinutes], or — if never
    // detected — at the fallback deadline (window start + [fallbackMs]).
    //
    // [graceMs] is a dwell guard: detection only fires once the user has been
    // continuously in the room for that long, so merely passing through doesn't
    // ring. 0 means fire on first detection. It only delays the detection-based
    // fire; the fallback deadline is unaffected and still rings on time.
    //
    // nextFireFrom returns the fallback DEADLINE, so the ordinary time-based
    // escalation machinery arms the can't-miss fallback for free; room
    // detection (RoomCheckManager → EscalationEngine.fireRoomReminder) pulls
    // the fire forward by re-anchoring the chain at the detection moment and
    // rewriting nextFireAt to it. "Once per day" falls out of the
    // lastCompletedAt guard: a window whose start the user already completed
    // at/after is skipped, so Done always advances to the next day's window.
    data class RoomAfter(
        val roomId: Long,
        val afterMinutes: Int,
        val fallbackMs: Long,
        val graceMs: Long = 0L
    ) : Schedule() {
        override val isOneTime: Boolean = false
        override fun nextFireFrom(now: Long, lastCompletedAt: Long?, zone: ZoneId): Long? {
            val nowDt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            val timeOfDay = LocalTime.of(afterMinutes / 60, afterMinutes % 60)
            // Start at yesterday: a window that crosses midnight (e.g. start
            // 23:30 with a 60 min fallback) still has its deadline pending
            // shortly after midnight.
            for (offset in -1L..1L) {
                val start = nowDt.toLocalDate().plusDays(offset).atTime(timeOfDay)
                    .atZone(zone).toInstant().toEpochMilli()
                val deadline = start + fallbackMs
                if (deadline <= now) continue
                if (lastCompletedAt != null && lastCompletedAt >= start) continue
                return deadline
            }
            return null
        }
    }

    companion object {
        private fun nextDailyOrWeekly(
            now: Long,
            zone: ZoneId,
            daysOfWeek: Set<DayOfWeek>?,
            timesOfDayMinutes: List<Int>
        ): Long? {
            if (timesOfDayMinutes.isEmpty()) return null
            val nowDt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            for (offset in 0..8L) {
                val date: LocalDate = nowDt.toLocalDate().plusDays(offset)
                if (daysOfWeek != null && date.dayOfWeek !in daysOfWeek) continue
                for (m in timesOfDayMinutes.sorted()) {
                    val candidate = date.atTime(m / 60, m % 60)
                    val candidateMs = candidate.atZone(zone).toInstant().toEpochMilli()
                    if (candidateMs > now) return candidateMs
                }
            }
            return null
        }
    }
}
