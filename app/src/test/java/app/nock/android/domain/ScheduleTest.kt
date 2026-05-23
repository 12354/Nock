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

    @Test fun interval_uses_last_completed() {
        val s = Schedule.IntervalFromLast(8 * 60 * 60_000L)
        val last = ms(2026, 1, 15, 10, 0)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, last, zone)
        assertEquals(last + 8 * 60 * 60_000L, next)
    }

    @Test fun interval_no_history_returns_now_plus_interval() {
        val s = Schedule.IntervalFromLast(8 * 60 * 60_000L)
        val now = ms(2026, 1, 15, 12, 0)
        val next = s.nextFireFrom(now, null, zone)
        assertEquals(now + 8 * 60 * 60_000L, next)
    }
}
