package app.nock.android.trip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Human-readable diagnostic of the most recent calendar sync, surfaced in
 * Settings (under "Sync now") as a single copyable blob.
 *
 * A user who sees an event that *should* have become an alarm but didn't can
 * sync, then copy this log: it lists every event found in the look-ahead window
 * and exactly why each was — or wasn't — turned into a reminder (all-day,
 * calendar not watched, dismissed, geocode failure, the computed leave-by time,
 * etc.). That turns "calendar sync doesn't sync my appointment" into a report
 * that says which step dropped it.
 *
 * Holds only the latest run (in memory). A new [start] clears the previous run,
 * so the visible log always reflects the most recent sync.
 *
 * Thread-safe; lines may be appended from whatever dispatcher the sync runs on.
 */
@Singleton
class TripSyncLog @Inject constructor() {
    private val lock = Any()
    private val buf = StringBuilder()
    private val clockFmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _log = MutableStateFlow("")

    /** The latest published sync report. Empty until the first sync runs. */
    val log: StateFlow<String> = _log.asStateFlow()

    /** Begin a fresh report, discarding the previous run. */
    fun start(nowMs: Long) {
        synchronized(lock) {
            buf.setLength(0)
            buf.append("=== calendar sync ").append(tsFmt.format(Date(nowMs))).append(" ===\n")
        }
    }

    /** Append one raw line to the in-progress report. */
    fun line(text: String) {
        synchronized(lock) { buf.append(text).append('\n') }
    }

    /** Publish the in-progress report so the UI updates. Call once per run, at the end. */
    fun publish() {
        _log.value = synchronized(lock) { buf.toString() }
    }

    /** Latest report text (same as [log]'s value), for the Copy button. */
    fun dump(): String = _log.value

    fun clear() {
        synchronized(lock) { buf.setLength(0) }
        _log.value = ""
    }

    /** "Wed Jun 11, 14:30" — wall-clock of an event/leave-by, in the device's zone. */
    fun clock(ms: Long): String = clockFmt.format(Date(ms))

    /** "28m", "1h 5m", "2d 3h" — coarse duration, enough to read a travel time. */
    fun dur(ms: Long): String {
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
}
