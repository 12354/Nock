package app.nock.android.wifi

import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RoomCheckPlannerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val hourMs = 3_600_000L

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val schedule = Schedule.RoomAfter(roomId = 1L, afterMinutes = 22 * 60, fallbackMs = hourMs)

    private fun reminder(nextFireAt: Long?, s: Schedule = schedule) = Reminder(
        id = 1L, groupId = 1L, name = "meds", schedule = s,
        nextFireAt = nextFireAt, lastCompletedAt = null, createdAt = 0L
    )

    @Test fun in_window_between_start_and_deadline() {
        val deadline = ms(2026, 6, 10, 23, 0)
        assertTrue(RoomCheckPlanner.isInWindow(schedule, deadline, ms(2026, 6, 10, 22, 0)))
        assertTrue(RoomCheckPlanner.isInWindow(schedule, deadline, ms(2026, 6, 10, 22, 59)))
        assertFalse(RoomCheckPlanner.isInWindow(schedule, deadline, ms(2026, 6, 10, 21, 59)))
        assertFalse(RoomCheckPlanner.isInWindow(schedule, deadline, ms(2026, 6, 10, 23, 0)))
        assertFalse(RoomCheckPlanner.isInWindow(schedule, null, ms(2026, 6, 10, 22, 30)))
    }

    @Test fun consumed_window_is_not_in_window() {
        // Detection rewrote nextFireAt to the (past) detection moment.
        val detectedAt = ms(2026, 6, 10, 22, 5)
        assertFalse(RoomCheckPlanner.isInWindow(schedule, detectedAt, ms(2026, 6, 10, 22, 20)))
    }

    @Test fun before_window_sleeps_to_window_start() {
        val r = reminder(nextFireAt = ms(2026, 6, 10, 23, 0))
        val now = ms(2026, 6, 10, 12, 0)
        assertEquals(ms(2026, 6, 10, 22, 0), RoomCheckPlanner.nextCheckAt(listOf(r), now, zone))
    }

    @Test fun inside_window_ticks_at_interval() {
        val r = reminder(nextFireAt = ms(2026, 6, 10, 23, 0))
        val now = ms(2026, 6, 10, 22, 10)
        assertEquals(now + RoomCheckPlanner.CHECK_INTERVAL_MS, RoomCheckPlanner.nextCheckAt(listOf(r), now, zone))
    }

    @Test fun near_deadline_skips_to_next_window() {
        // 22:50 + 15 min would land past the 23:00 deadline; the fallback owns it.
        val r = reminder(nextFireAt = ms(2026, 6, 10, 23, 0))
        val now = ms(2026, 6, 10, 22, 50)
        assertEquals(ms(2026, 6, 11, 22, 0), RoomCheckPlanner.nextCheckAt(listOf(r), now, zone))
    }

    @Test fun consumed_window_rearms_tomorrows_start() {
        // Fired by detection at 22:05; checks resume at tomorrow's window start.
        val r = reminder(nextFireAt = ms(2026, 6, 10, 22, 5))
        val now = ms(2026, 6, 10, 22, 20)
        assertEquals(ms(2026, 6, 11, 22, 0), RoomCheckPlanner.nextCheckAt(listOf(r), now, zone))
    }

    @Test fun earliest_event_across_reminders_wins() {
        val evening = reminder(nextFireAt = ms(2026, 6, 10, 23, 0))
        val morning = Schedule.RoomAfter(roomId = 2L, afterMinutes = 8 * 60, fallbackMs = hourMs)
        val morningReminder = Reminder(
            id = 2L, groupId = 1L, name = "vitamins", schedule = morning,
            nextFireAt = ms(2026, 6, 11, 9, 0), lastCompletedAt = null, createdAt = 0L
        )
        val now = ms(2026, 6, 10, 23, 30)
        // Evening window is over (deadline passed → not in window): next events
        // are tomorrow 22:00 (evening) and tomorrow 08:00 (morning).
        assertEquals(
            ms(2026, 6, 11, 8, 0),
            RoomCheckPlanner.nextCheckAt(listOf(evening, morningReminder), now, zone)
        )
    }

    @Test fun no_room_reminders_means_no_alarm() {
        val daily = Reminder(
            id = 3L, groupId = 1L, name = "x", schedule = Schedule.Daily(listOf(8 * 60)),
            nextFireAt = ms(2026, 6, 11, 8, 0), lastCompletedAt = null, createdAt = 0L
        )
        assertNull(RoomCheckPlanner.nextCheckAt(listOf(daily), ms(2026, 6, 10, 12, 0), zone))
    }
}
