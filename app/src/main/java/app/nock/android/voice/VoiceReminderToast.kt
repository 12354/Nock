package app.nock.android.voice

import android.content.Context
import app.nock.android.R
import app.nock.android.domain.model.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Builds the short confirmation shown once a voice transcript has become a real
 * reminder, e.g. `Added "Take meds" · tomorrow at 09:00`.
 *
 * One-shot reminders read their date relative to today (today / tomorrow /
 * in N days) so the toast sounds natural; recurring reminders describe their
 * recurrence instead, since "tomorrow" would be misleading for them.
 */
object VoiceReminderToast {

    fun format(
        ctx: Context,
        name: String,
        nextFireAt: Long?,
        schedule: Schedule,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = ctx.getString(
        R.string.voice_added_toast,
        name,
        whenText(ctx, schedule, nextFireAt, now, zone)
    )

    private fun whenText(
        ctx: Context,
        schedule: Schedule,
        nextFireAt: Long?,
        now: Long,
        zone: ZoneId,
    ): String = when (schedule) {
        is Schedule.Daily ->
            ctx.getString(R.string.voice_when_daily, formatTimes(schedule.timesOfDayMinutes))
        is Schedule.Weekly ->
            ctx.getString(
                R.string.voice_when_weekly,
                formatDays(schedule.daysOfWeek),
                formatTimes(schedule.timesOfDayMinutes)
            )
        is Schedule.Monthly ->
            ctx.getString(
                R.string.voice_when_monthly,
                schedule.dayOfMonth,
                formatMinutes(schedule.timeOfDayMinutes)
            )
        is Schedule.IntervalFromLast -> formatInterval(ctx, schedule.intervalMs)
        is Schedule.OnUnlock -> ctx.getString(R.string.voice_when_on_unlock)
        is Schedule.OneShot ->
            relativeDateTime(ctx, nextFireAt ?: schedule.atEpochMs, now, zone)
    }

    private fun relativeDateTime(ctx: Context, ms: Long, now: Long, zone: ZoneId): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val time = formatMinutes(dt.hour * 60 + dt.minute)
        return when (val days = dayOffset(now, ms, zone)) {
            0L -> ctx.getString(R.string.voice_when_today, time)
            1L -> ctx.getString(R.string.voice_when_tomorrow, time)
            in 2L..6L -> ctx.resources.getQuantityString(
                R.plurals.voice_when_in_days, days.toInt(), days.toInt(), time
            )
            else -> {
                val date = dt.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                ctx.getString(R.string.voice_when_date, date, time)
            }
        }
    }

    /** Whole-day difference between [targetMs] and [nowMs] in [zone] (0 = today). */
    internal fun dayOffset(nowMs: Long, targetMs: Long, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate(),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMs), zone).toLocalDate()
        )

    private fun formatMinutes(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

    private fun formatTimes(minutes: List<Int>): String =
        if (minutes.isEmpty()) formatMinutes(0)
        else minutes.sorted().joinToString(", ") { formatMinutes(it) }

    private fun formatDays(days: Set<DayOfWeek>): String =
        days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

    private fun formatInterval(ctx: Context, intervalMs: Long): String {
        val totalMin = (intervalMs / 60_000L).toInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0 -> ctx.getString(R.string.voice_when_interval_minutes, m)
            m == 0 -> ctx.getString(R.string.voice_when_interval_hours, h)
            else -> ctx.getString(R.string.voice_when_interval_hours_minutes, h, m)
        }
    }
}
