package app.nock.android.history

import android.content.Context
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Append-only history of everything an alarm does over its life — created,
 * edited, fired (per escalation stage), done, snoozed, and the late "catch-up"
 * replays that happen after a reboot or an app update. Exposed in Settings as a
 * single copyable blob so a user who sees a wrong-time alarm can ship the whole
 * timeline off verbatim ("fired at 21:00 for an 18:00 reminder — was it missed
 * before?") instead of trying to describe it.
 *
 * This replaces the old voice-recognition diagnostics log: same storage shape,
 * but the events that matter for alarm-timing bugs rather than speech callbacks.
 *
 * Persists to internal storage so the timeline survives process restarts —
 * which is exactly when the interesting late-fire events happen. Bounded at
 * ~1 MB; when the cap is hit the oldest ~250 KB is dropped in one chunk so the
 * trim cost is amortised.
 *
 * Thread-safe.
 */
@Singleton
class AlarmHistoryLogger @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val file = java.io.File(ctx.filesDir, FILE_NAME)
    private val lock = Any()
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val clockFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    init {
        synchronized(lock) {
            if (!file.exists()) file.createNewFile()
        }
    }

    /** A brand-new reminder was saved. The "task overview" line. */
    fun created(name: String, group: String?, schedule: Schedule, nextFireAtMs: Long?) {
        val parts = buildList {
            group?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(describeSchedule(schedule))
            nextFireAtMs?.let { add("next ${clockFmt.format(Date(it))}") }
        }
        write("CREATED", name, parts.joinToString(" · "))
    }

    /** An existing reminder was edited. [changes] is one entry per field touched. */
    fun modified(name: String, changes: List<String>) {
        if (changes.isEmpty()) return
        write("EDITED", name, changes.joinToString(" · "))
    }

    /**
     * An escalation stage fired. [scheduledForMs] is the instant the reminder
     * was due; comparing it to now is what reveals a late delivery.
     */
    fun fired(name: String, stage: StageType, scheduledForMs: Long, nowMs: Long) {
        val lateMs = nowMs - scheduledForMs
        val detail = buildString {
            append("due ${clockFmt.format(Date(scheduledForMs))}")
            if (lateMs >= LATE_THRESHOLD_MS) append(" · ${humanDuration(lateMs)} late")
        }
        write("FIRED", name, "[${stageLabel(stage)}] $detail")
    }

    /** The reminder was dismissed. */
    fun done(name: String) = write("DONE", name, "")

    /** The reminder was snoozed; [nextAtMs] is when it will ring again. */
    fun snoozed(name: String, nextAtMs: Long) =
        write("SNOOZE", name, "next ${clockFmt.format(Date(nextAtMs))}")

    /**
     * An overdue reminder was replayed "now" after a reboot or app update — it
     * had been due in the past and is only ringing now. This is the event that
     * explains a 6pm alarm going off at 9pm.
     */
    fun replayedOverdue(name: String, originalDueMs: Long?, nowMs: Long) {
        val detail = if (originalDueMs != null) {
            "was due ${clockFmt.format(Date(originalDueMs))} (${humanDuration(nowMs - originalDueMs)} ago) · replayed after restart"
        } else {
            "overdue · replayed after restart"
        }
        write("MISSED", name, detail)
    }

    fun dump(): String = synchronized(lock) {
        if (file.exists()) file.readText() else ""
    }

    fun clear() {
        synchronized(lock) { file.writeText("") }
    }

    /**
     * A snapshot of every live alarm and its current state, formatted to be
     * appended to the copied history — so a bug report carries not just what
     * happened but what is scheduled right now. Ordered by next fire time
     * (unscheduled last). [nowMs] is the wall clock the listing is taken at.
     */
    fun snapshot(states: List<AlarmState>, nowMs: Long): String = buildString {
        append("\n=== current alarms (")
        append(clockFmt.format(Date(nowMs)))
        append(") ===\n")
        if (states.isEmpty()) {
            append("(none)\n")
            return@buildString
        }
        states.sortedWith(compareBy(nullsLast()) { it.nextFireAtMs }).forEach { s ->
            val parts = buildList {
                s.group?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(describeSchedule(s.schedule))
                add(if (s.nextFireAtMs != null) "next ${clockFmt.format(Date(s.nextFireAtMs))}" else "not scheduled")
                s.lastCompletedAt?.let { add("last done ${clockFmt.format(Date(it))}") }
                s.active?.let {
                    add("ESCALATING → next ${stageLabel(it.nextStageType)} at ${clockFmt.format(Date(it.nextFireAtMs))}")
                }
            }
            append('"').append(s.name).append('"').append(" · ").append(parts.joinToString(" · ")).append('\n')
            // The escalation chain the reminder will walk when it fires — the
            // stage sequence with offsets relative to the due time, plus the
            // repeat interval. Indented under its alarm so a bug report shows
            // exactly how every alarm is configured to escalate.
            append("    chain: ").append(describeChain(s.chain)).append('\n')
        }
    }

    /** One live alarm for [snapshot]. [active] is null unless it is mid-escalation. */
    data class AlarmState(
        val name: String,
        val group: String?,
        val schedule: Schedule,
        val nextFireAtMs: Long?,
        val lastCompletedAt: Long?,
        val active: ActiveState?,
        val chain: EscalationChain,
    )

    /** The escalation state of a currently-firing alarm: the next stage and when. */
    data class ActiveState(
        val nextStageType: StageType,
        val nextFireAtMs: Long,
    )

    private fun write(event: String, name: String, detail: String) {
        // event padded so the column lines up in the monospace viewer.
        val head = event.padEnd(8)
        val line = buildString {
            append("[${tsFmt.format(Date())}] ")
            append(head)
            append(" \"").append(name).append('"')
            if (detail.isNotBlank()) append(" · ").append(detail)
            append('\n')
        }
        synchronized(lock) {
            file.appendText(line)
            trimIfNeeded()
        }
    }

    /** Human-readable one-liner for a schedule; reused for create + edit diffs. */
    fun describeSchedule(s: Schedule): String = when (s) {
        is Schedule.OneShot -> "once at ${clockFmt.format(Date(s.atEpochMs))}"
        is Schedule.Daily -> "daily " + s.timesOfDayMinutes.sorted().joinToString(", ") { hhmm(it) }
        is Schedule.Weekly -> {
            val days = s.daysOfWeek.sorted().joinToString(",") { dayLabel(it) }
            "weekly $days " + s.timesOfDayMinutes.sorted().joinToString(", ") { hhmm(it) }
        }
        is Schedule.Monthly -> "monthly day ${s.dayOfMonth} at ${hhmm(s.timeOfDayMinutes)}"
        is Schedule.IntervalFromLast -> "every ${humanDuration(s.intervalMs)} after completion"
        is Schedule.OnUnlock -> "on next unlock"
    }

    /**
     * Human-readable one-liner for an escalation chain: each stage as
     * "label ±offset" relative to the due time, joined by arrows, then the
     * repeat interval — e.g. "silent -10m → telegram +5m → alarm +10m · repeat 10m".
     */
    fun describeChain(c: EscalationChain): String {
        val stages = c.stages.joinToString(" → ") { "${stageLabel(it.type)} ${offsetLabel(it.offsetMs)}" }
        return "$stages · repeat ${humanDuration(c.repeatIntervalMs)}"
    }

    /** Signed offset relative to the due time: "-10m", "+0m", "+5m". */
    private fun offsetLabel(ms: Long): String = (if (ms < 0) "-" else "+") + humanDuration(ms)

    private fun hhmm(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun dayLabel(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "Mon"; DayOfWeek.TUESDAY -> "Tue"; DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"; DayOfWeek.FRIDAY -> "Fri"; DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    private fun stageLabel(t: StageType): String = when (t) {
        StageType.SILENT -> "silent"
        StageType.VIBRATE -> "buzz"
        StageType.TELEGRAM -> "telegram"
        StageType.ALARM_VIBRATE -> "vibrate"
        StageType.ALARM -> "alarm"
    }

    /** "3h 0m", "45m", "2d 4h" — coarse, just enough to read a lateness gap. */
    private fun humanDuration(ms: Long): String {
        val totalMin = abs(ms) / 60_000L
        val days = totalMin / (24 * 60)
        val hours = (totalMin % (24 * 60)) / 60
        val mins = totalMin % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }

    private fun trimIfNeeded() {
        val len = file.length()
        if (len <= MAX_BYTES) return
        val text = file.readText()
        val dropFrom = TRIM_BYTES.coerceAtMost(text.length)
        val tail = text.substring(dropFrom)
        // Snap to the next newline so we never leave a half-line at the start.
        val nl = tail.indexOf('\n')
        val kept = if (nl >= 0) tail.substring(nl + 1) else tail
        file.writeText("--- history trimmed ---\n$kept")
    }

    private companion object {
        const val FILE_NAME = "alarm_history.txt"
        const val MAX_BYTES = 1_000_000L
        const val TRIM_BYTES = 250_000
        // Ignore sub-minute slack so legitimate scheduling jitter isn't flagged "late".
        const val LATE_THRESHOLD_MS = 60_000L
    }
}
