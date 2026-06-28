package app.nock.android.domain

import app.nock.android.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun oneShot_returns_null_if_completed() {
        val s = Schedule.OneShot(ms(2026, 1, 15, 10, 0))
        assertNull(s.nextFireFrom(ms(2026, 1, 1, 0, 0), lastCompletedAt = ms(2026, 1, 16, 0, 0), zone))
    }

    @Test fun daily_chooses_next_time_today() {
        val s = Schedule.Daily(listOf(8 * 60, 18 * 60))
        val now = ms(2026, 1, 15, 9, 0)
        val next = s.nextFireFrom(now, null, zone)
        assertEquals(ms(2026, 1, 15, 18, 0), next)
    }

    @Test fun daily_rolls_to_tomorrow_after_last_time() {
        val s = Schedule.Daily(listOf(8 * 60))
        val now = ms(2026, 1, 15, 9, 0)
        val next = s.nextFireFrom(now, null, zone)
        assertEquals(ms(2026, 1, 16, 8, 0), next)
    }

    @Test fun weekly_picks_next_weekday() {
        val s = Schedule.Weekly(setOf(DayOfWeek.WEDNESDAY), listOf(9 * 60))
        val mondayMs = ms(2026, 1, 12, 12, 0)
        val next = s.nextFireFrom(mondayMs, null, zone)
        assertEquals(ms(2026, 1, 14, 9, 0), next)
    }

    @Test fun monthly_handles_short_month() {
        val s = Schedule.Monthly(31, 9 * 60)
        val feb1 = ms(2026, 2, 1, 0, 0)
        val next = s.nextFireFrom(feb1, null, zone)
        assertNotNull(next)
        assertTrue(next!! > feb1)
    }

    // An anchorless (legacy) interval row keeps the old completion-relative step.
    @Test fun interval_anchorless_falls_back_to_last_completed() {
        val s = Schedule.IntervalFromStart(8 * 60 * 60_000L)
        val last = ms(2026, 1, 15, 10, 0)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, last, zone)
        assertEquals(last + 8 * 60 * 60_000L, next)
    }

    @Test fun interval_anchorless_no_history_returns_now_plus_interval() {
        val s = Schedule.IntervalFromStart(8 * 60 * 60_000L)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, null, zone)
        assertEquals(now + 8 * 60 * 60_000L, next)
    }

    @Test fun interval_with_future_start_fires_at_start() {
        val startAt = ms(2026, 1, 20, 10, 0)
        val s = Schedule.IntervalFromStart(8 * 60 * 60_000L, startAtMs = startAt)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, null, zone)
        assertEquals(startAt, next)
    }

    // Past anchor: the next fire is the next point on the fixed grid, not `now`.
    @Test fun interval_with_past_start_returns_next_grid_point() {
        val startAt = ms(2026, 1, 10, 10, 0)
        val s = Schedule.IntervalFromStart(8 * 60 * 60_000L, startAtMs = startAt)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, null, zone)
        // 8h grid from 01-10 10:00 → first point strictly after 01-15 12:00.
        assertEquals(ms(2026, 1, 15, 18, 0), next)
    }

    // The cadence is anchored on the start date, so the next fire is identical
    // whether the previous occurrence was completed early or hours late.
    @Test fun interval_cadence_ignores_completion_time() {
        val startAt = ms(2026, 1, 20, 10, 0)
        val s = Schedule.IntervalFromStart(8 * 60 * 60_000L, startAtMs = startAt)
        val now = ms(2026, 1, 20, 14, 0)
        val onTime = s.nextFireFrom(now, ms(2026, 1, 20, 10, 5), zone)
        val late = s.nextFireFrom(now, ms(2026, 1, 20, 13, 59), zone)
        assertEquals(ms(2026, 1, 20, 18, 0), onTime)
        assertEquals(ms(2026, 1, 20, 18, 0), late)
    }

    // Completing days late after repeated snoozes resyncs to the grid; the next
    // fire is the grid point, never "completion + interval".
    @Test fun interval_many_snoozes_do_not_shift_grid() {
        val startAt = ms(2026, 1, 1, 0, 0)
        val s = Schedule.IntervalFromStart(24 * 60 * 60_000L, startAtMs = startAt)
        val now = ms(2026, 1, 5, 6, 0)
        val next = s.nextFireFrom(now, ms(2026, 1, 5, 6, 0), zone)
        assertEquals(ms(2026, 1, 6, 0, 0), next)
    }

    // --- Robustness against corrupt / out-of-range fields -------------------
    // nextFireFrom runs inside the boot/clock-change reschedule loop over every
    // reminder, so a single throw would abort re-arming all of them. A corrupt or
    // adversarial sync payload must degrade gracefully (clamp or never-fire), not
    // crash.

    @Test fun monthly_clamps_zero_or_negative_day_instead_of_crashing() {
        // dayOfMonth 0 / negative would throw DateTimeException out of
        // withDayOfMonth without a lower-bound clamp.
        for (badDay in listOf(0, -1, -31)) {
            val s = Schedule.Monthly(badDay, 9 * 60)
            val now = ms(2026, 2, 10, 0, 0)
            val next = s.nextFireFrom(now, null, zone)
            assertNotNull("Monthly(day=$badDay) must still produce a fire time", next)
            assertTrue(next!! > now)
            // Clamped up to the 1st of a month at 09:00.
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
            assertEquals(1, dt.dayOfMonth)
            assertEquals(9, dt.hour)
        }
    }

    @Test fun out_of_range_minute_fields_do_not_crash() {
        // Minute-of-day past 1439 (or negative) would throw from LocalTime.of.
        val now = ms(2026, 1, 10, 0, 0)
        assertNotNull(Schedule.Monthly(15, 5_000).nextFireFrom(now, null, zone))
        assertNotNull(Schedule.Daily(listOf(9_000)).nextFireFrom(now, null, zone))
        assertNotNull(
            Schedule.Weekly(setOf(DayOfWeek.MONDAY), listOf(-30)).nextFireFrom(now, null, zone)
        )
        assertNotNull(
            Schedule.RoomAfter(roomId = 1, afterMinutes = 4_000, fallbackMs = 60_000L)
                .nextFireFrom(now, null, zone)
        )
    }

    @Test fun empty_daily_or_weekly_never_fires_but_does_not_crash() {
        val now = ms(2026, 1, 10, 0, 0)
        assertNull(Schedule.Daily(emptyList()).nextFireFrom(now, null, zone))
        assertNull(Schedule.Weekly(emptySet(), listOf(9 * 60)).nextFireFrom(now, null, zone))
        assertNull(Schedule.Weekly(setOf(DayOfWeek.MONDAY), emptyList()).nextFireFrom(now, null, zone))
    }

    @Test fun interval_overflow_saturates_to_future_never_wraps_negative() {
        // A near-Long.MAX interval would wrap to a negative (past) fire time without
        // overflow-safe arithmetic, which the engine would then treat as overdue.
        val now = ms(2026, 1, 10, 0, 0)
        val s = Schedule.IntervalFromStart(Long.MAX_VALUE / 2, startAtMs = now - 1)
        val next = s.nextFireFrom(now, null, zone)
        assertNotNull(next)
        assertTrue("overflowing interval must never return a past time", next!! > now)
    }

    @Test fun interval_zero_is_coerced_and_still_advances() {
        val now = ms(2026, 1, 10, 0, 0)
        val s = Schedule.IntervalFromStart(0L, startAtMs = now)
        val next = s.nextFireFrom(now, null, zone)
        assertNotNull(next)
        assertTrue(next!! > now)
    }
}
