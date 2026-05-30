package app.nock.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class VoiceReminderToastTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun same_day_is_today() {
        val now = ms(2026, 5, 30, 9, 0)
        val target = ms(2026, 5, 30, 18, 0)
        assertEquals(0L, VoiceReminderToast.dayOffset(now, target, zone))
    }

    @Test fun next_calendar_day_is_tomorrow_even_a_minute_later() {
        val now = ms(2026, 5, 30, 23, 59)
        val target = ms(2026, 5, 31, 0, 1)
        assertEquals(1L, VoiceReminderToast.dayOffset(now, target, zone))
    }

    @Test fun two_days_out() {
        val now = ms(2026, 5, 30, 9, 0)
        val target = ms(2026, 6, 1, 8, 0)
        assertEquals(2L, VoiceReminderToast.dayOffset(now, target, zone))
    }

    @Test fun offset_is_by_calendar_day_not_elapsed_hours() {
        // Less than 24h elapsed but it crosses midnight, so it's "tomorrow".
        val now = ms(2026, 5, 30, 20, 0)
        val target = ms(2026, 5, 31, 8, 0)
        assertEquals(1L, VoiceReminderToast.dayOffset(now, target, zone))
    }
}
