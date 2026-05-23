package app.nock.android.parse

import app.nock.android.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class NaturalLanguageParserTest {

    private val now = LocalDateTime.of(2026, 1, 15, 10, 0)

    @Test fun parses_daily_two_times() {
        val r = NaturalLanguageParser.parse("Feed dog every day at 8am and 6pm — Pets", now)
        val s = r.schedule as Schedule.Daily
        assertEquals(listOf(8 * 60, 18 * 60), s.timesOfDayMinutes)
        assertTrue(r.name.contains("Feed dog", ignoreCase = true))
        assertEquals("Pets", r.groupHint)
    }

    @Test fun parses_weekly_on_tuesday() {
        val r = NaturalLanguageParser.parse("Team standup weekly on Tue at 09:30", now)
        val s = r.schedule as Schedule.Weekly
        assertEquals(setOf(DayOfWeek.TUESDAY), s.daysOfWeek)
        assertEquals(listOf(9 * 60 + 30), s.timesOfDayMinutes)
    }

    @Test fun parses_interval_hours() {
        val r = NaturalLanguageParser.parse("Take meds every 8 hours", now)
        val s = r.schedule as Schedule.IntervalFromLast
        assertEquals(8L * 60L * 60_000L, s.intervalMs)
    }

    @Test fun parses_interval_minutes() {
        val r = NaturalLanguageParser.parse("Stretch every 45 min", now)
        val s = r.schedule as Schedule.IntervalFromLast
        assertEquals(45L * 60_000L, s.intervalMs)
    }

    @Test fun parses_tomorrow_with_time() {
        val r = NaturalLanguageParser.parse("Call dentist tomorrow at 14:00", now)
        val s = r.schedule as Schedule.OneShot
        assertNotNull(s)
    }

    @Test fun parses_hash_group_hint() {
        val r = NaturalLanguageParser.parse("Pay bill #Household", now)
        assertEquals("Household", r.groupHint)
    }

    @Test fun cleans_name_of_schedule_tokens() {
        val r = NaturalLanguageParser.parse("Feed dog every day at 8am — Pets", now)
        assertTrue(r.name.contains("Feed dog"))
        assertTrue(!r.name.contains("every", ignoreCase = true))
        assertTrue(!r.name.contains("8am", ignoreCase = true))
    }
}
