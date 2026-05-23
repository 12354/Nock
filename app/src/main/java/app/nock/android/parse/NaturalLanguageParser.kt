package app.nock.android.parse

import app.nock.android.domain.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedReminder(
    val name: String,
    val schedule: Schedule?,
    val groupHint: String?
)

object NaturalLanguageParser {

    private val daysOfWeek = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val timeRegex = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""",
        RegexOption.IGNORE_CASE
    )

    private val intervalHoursRegex = Regex("""every\s+(\d+)\s*h(?:our|rs)?s?""", RegexOption.IGNORE_CASE)
    private val intervalMinsRegex = Regex("""every\s+(\d+)\s*m(?:in|inute|ins|inutes)?\b""", RegexOption.IGNORE_CASE)
    private val dateRegex = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
    private val tomorrowRegex = Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE)
    private val todayRegex = Regex("""\btoday\b""", RegexOption.IGNORE_CASE)
    private val monthDayRegex = Regex(
        """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): ParsedReminder {
        var working = input.trim()
        val groupHint = extractGroupHint(working)?.also { working = stripGroupHint(working) }

        val intervalH = intervalHoursRegex.find(working)
        if (intervalH != null) {
            val hours = intervalH.groupValues[1].toInt()
            val name = cleanName(working.replace(intervalH.value, "").trim())
            return ParsedReminder(name, Schedule.IntervalFromLast(hours * 60L * 60_000L), groupHint)
        }
        val intervalM = intervalMinsRegex.find(working)
        if (intervalM != null) {
            val mins = intervalM.groupValues[1].toInt()
            val name = cleanName(working.replace(intervalM.value, "").trim())
            return ParsedReminder(name, Schedule.IntervalFromLast(mins * 60_000L), groupHint)
        }

        val times = extractTimes(working)
        val weekdays = extractWeekdays(working)
        val isEveryDay = Regex("""every\s+day|daily""", RegexOption.IGNORE_CASE).containsMatchIn(working)

        val dateMatch = dateRegex.find(working)
        val monthDayMatch = monthDayRegex.find(working)
        val tomorrowMatch = tomorrowRegex.find(working)
        val todayMatch = todayRegex.find(working)

        val explicitDate: LocalDate? = when {
            dateMatch != null -> runCatching { LocalDate.parse(dateMatch.value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
            monthDayMatch != null -> parseMonthDay(monthDayMatch, now)
            tomorrowMatch != null -> now.toLocalDate().plusDays(1)
            todayMatch != null -> now.toLocalDate()
            else -> null
        }

        val schedule: Schedule? = when {
            weekdays.isNotEmpty() && times.isNotEmpty() ->
                Schedule.Weekly(weekdays, times.map { it.hour * 60 + it.minute })
            isEveryDay && times.isNotEmpty() ->
                Schedule.Daily(times.map { it.hour * 60 + it.minute })
            explicitDate != null && times.isNotEmpty() -> {
                val ldt = explicitDate.atTime(times.first())
                Schedule.OneShot(ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
            times.isNotEmpty() -> {
                val first = times.first()
                val date = if (LocalTime.of(first.hour, first.minute) <= now.toLocalTime()) {
                    now.toLocalDate().plusDays(1)
                } else now.toLocalDate()
                Schedule.OneShot(
                    date.atTime(first).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
            }
            else -> null
        }

        val name = cleanName(stripScheduleTokens(working))
        return ParsedReminder(name, schedule, groupHint)
    }

    private fun parseMonthDay(m: MatchResult, now: LocalDateTime): LocalDate? {
        val mon = m.groupValues[1].lowercase(Locale.ROOT)
        val day = m.groupValues[2].toInt()
        val month = monthShort(mon) ?: return null
        var year = now.year
        val candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (candidate.isBefore(now.toLocalDate())) candidate.plusYears(1) else candidate
    }

    private fun monthShort(s: String): Int? = when {
        s.startsWith("jan") -> 1
        s.startsWith("feb") -> 2
        s.startsWith("mar") -> 3
        s.startsWith("apr") -> 4
        s == "may" -> 5
        s.startsWith("jun") -> 6
        s.startsWith("jul") -> 7
        s.startsWith("aug") -> 8
        s.startsWith("sep") -> 9
        s.startsWith("oct") -> 10
        s.startsWith("nov") -> 11
        s.startsWith("dec") -> 12
        else -> null
    }

    private fun extractTimes(text: String): List<LocalTime> {
        return timeRegex.findAll(text)
            .mapNotNull { match -> parseTime(match) }
            .toList()
    }

    private fun parseTime(m: MatchResult): LocalTime? {
        val hStr = m.groupValues[1]
        val mStr = m.groupValues[2]
        val mer = m.groupValues[3].lowercase(Locale.ROOT)

        val rawH = hStr.toIntOrNull() ?: return null
        val rawM = mStr.toIntOrNull() ?: 0
        if (rawM > 59) return null

        var hour = rawH
        if (mer.contains("a") || mer.contains("p")) {
            if (hour !in 1..12) return null
            if (hour == 12) hour = 0
            if (mer.contains("p")) hour += 12
        } else {
            if (mStr.isEmpty()) return null
            if (hour !in 0..23) return null
        }
        return LocalTime.of(hour, rawM)
    }

    private fun extractWeekdays(text: String): Set<DayOfWeek> {
        val isWeekly = Regex("""weekly|every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        if (!isWeekly && !Regex("""on\s+(mon|tue|wed|thu|fri|sat|sun)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return emptySet()
        }
        val out = mutableSetOf<DayOfWeek>()
        Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun)\b""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                daysOfWeek[m.value.lowercase(Locale.ROOT)]?.let(out::add)
            }
        return out
    }

    private fun extractGroupHint(text: String): String? {
        val m = Regex("""\B[-—–]\s+([A-Za-z][A-Za-z0-9 ]{0,20})\s*$""").find(text)
            ?: Regex("""#([A-Za-z][A-Za-z0-9]{0,20})""").find(text)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun stripGroupHint(text: String): String {
        return text
            .replace(Regex("""\B[-—–]\s+[A-Za-z][A-Za-z0-9 ]{0,20}\s*$"""), "")
            .replace(Regex("""#[A-Za-z][A-Za-z0-9]{0,20}"""), "")
            .trim()
    }

    private fun stripScheduleTokens(text: String): String {
        var s = text
        s = s.replace(timeRegex, "")
        s = s.replace(intervalHoursRegex, "")
        s = s.replace(intervalMinsRegex, "")
        s = s.replace(Regex("""every\s+day|daily""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""weekly""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\bon\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\bat\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\band\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun)\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(tomorrowRegex, "")
        s = s.replace(todayRegex, "")
        s = s.replace(dateRegex, "")
        s = s.replace(monthDayRegex, "")
        return s
    }

    private fun cleanName(raw: String): String {
        return raw
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[-–—,]+\s*$"""), "")
            .replace(Regex("""^\s*[-–—,]+"""), "")
            .trim()
    }
}
