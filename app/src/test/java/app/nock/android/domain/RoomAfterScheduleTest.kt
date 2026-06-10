package app.nock.android.domain

import app.nock.android.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RoomAfterScheduleTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val hourMs = 3_600_000L

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Bedroom after 22:00, fallback 60 min → deadline 23:00.
    private val s = Schedule.RoomAfter(roomId = 1L, afterMinutes = 22 * 60, fallbackMs = hourMs)

    @Test fun returns_todays_deadline_before_window() {
        val now = ms(2026, 6, 10, 12, 0)
        assertEquals(ms(2026, 6, 10, 23, 0), s.nextFireFrom(now, null, zone))
    }

    @Test fun returns_todays_deadline_inside_window() {
        val now = ms(2026, 6, 10, 22, 30)
        assertEquals(ms(2026, 6, 10, 23, 0), s.nextFireFrom(now, null, zone))
    }

    @Test fun rolls_to_tomorrow_after_deadline() {
        val now = ms(2026, 6, 10, 23, 30)
        assertEquals(ms(2026, 6, 11, 23, 0), s.nextFireFrom(now, null, zone))
    }

    @Test fun completion_inside_window_skips_today() {
        // Done at 22:10 (window started 22:00) → today is consumed.
        val now = ms(2026, 6, 10, 22, 10)
        val next = s.nextFireFrom(now, lastCompletedAt = ms(2026, 6, 10, 22, 10), zone)
        assertEquals(ms(2026, 6, 11, 23, 0), next)
    }

    @Test fun completion_yesterday_keeps_today() {
        val now = ms(2026, 6, 10, 12, 0)
        val next = s.nextFireFrom(now, lastCompletedAt = ms(2026, 6, 9, 22, 5), zone)
        assertEquals(ms(2026, 6, 10, 23, 0), next)
    }

    @Test fun completion_exactly_at_window_start_skips_today() {
        val now = ms(2026, 6, 10, 22, 0)
        val next = s.nextFireFrom(now, lastCompletedAt = ms(2026, 6, 10, 22, 0), zone)
        assertEquals(ms(2026, 6, 11, 23, 0), next)
    }

    @Test fun midnight_crossing_window_uses_yesterdays_deadline() {
        // After 23:30 + 60 min fallback → deadline 00:30 the next day.
        val late = Schedule.RoomAfter(roomId = 1L, afterMinutes = 23 * 60 + 30, fallbackMs = hourMs)
        val now = ms(2026, 6, 11, 0, 15) // inside the window that started June 10
        assertEquals(ms(2026, 6, 11, 0, 30), late.nextFireFrom(now, null, zone))
    }

    @Test fun midnight_crossing_window_completed_rolls_to_tonight() {
        val late = Schedule.RoomAfter(roomId = 1L, afterMinutes = 23 * 60 + 30, fallbackMs = hourMs)
        val now = ms(2026, 6, 11, 0, 15)
        // Detected and Done at 23:45 inside yesterday's window.
        val next = late.nextFireFrom(now, lastCompletedAt = ms(2026, 6, 10, 23, 45), zone)
        assertEquals(ms(2026, 6, 12, 0, 30), next)
    }

    @Test fun is_not_one_time() {
        org.junit.Assert.assertFalse(s.isOneTime)
    }
}
