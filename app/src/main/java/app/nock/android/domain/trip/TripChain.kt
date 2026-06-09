package app.nock.android.domain.trip

import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType

/**
 * Builds the escalation chain used by calendar trip reminders. The chain is
 * anchored at the reminder's scheduled time, which for a trip is `leaveBy`
 * (= eventStart − travel). Offsets are therefore relative to departure:
 *
 *   SILENT        @ −buffer        "start wrapping up, you leave in <buffer>"
 *   ALARM_VIBRATE @ −buffer/3      escalate as departure nears
 *   ALARM         @ 0 (leaveBy)    you must leave now — loud, repeats until Done
 *
 * The Trips group stores this as its override chain, so trip reminders reuse the
 * existing per-group escalation machinery untouched: only the offsets differ
 * from the global default, and they re-anchor on each leave-by recompute.
 */
object TripChain {

    /** Smallest buffer that still leaves room for a distinct mid stage. */
    private const val MIN_BUFFER_MS = 2 * 60_000L
    private const val MIN_MID_LEAD_MS = 60_000L

    fun build(bufferMs: Long, repeatIntervalMs: Long): EscalationChain {
        val buffer = bufferMs.coerceAtLeast(MIN_BUFFER_MS)
        // Mid (vibrate) stage sits a third of the way from heads-up to departure,
        // but never closer than a minute to the silent stage so the two never
        // collapse onto the same offset (which the chain forbids).
        val midLead = (buffer / 3).coerceAtLeast(MIN_MID_LEAD_MS).coerceAtMost(buffer - MIN_MID_LEAD_MS)
        return EscalationChain(
            stages = listOf(
                StageConfig(StageType.SILENT, -buffer),
                StageConfig(StageType.ALARM_VIBRATE, -midLead),
                StageConfig(StageType.ALARM, 0L),
            ),
            repeatIntervalMs = repeatIntervalMs,
        )
    }
}

object TripDefaults {
    const val BUFFER_MS: Long = 30 * 60_000L
    const val REPEAT_INTERVAL_MS: Long = 10 * 60_000L
    /**
     * How far ahead calendar events are imported as trip reminders. Kept wide
     * enough that the next several days of trips are visible in the lists, not
     * just the imminent ones; the traffic-aware leave-by time still refines
     * itself as each trip nears via the scheduled recomputes.
     */
    const val LOOKAHEAD_MS: Long = 7L * 24 * 60 * 60_000L

    /**
     * How far ahead the manual single-appointment importer lists events. Much
     * wider than [LOOKAHEAD_MS] so the user can scroll or search to an appointment
     * weeks or months out, not just the auto-synced week.
     */
    const val MANUAL_LOOKAHEAD_MS: Long = 365L * 24 * 60 * 60_000L

    const val TRAVEL_MODE: String = "car"

    /**
     * Below this travel time a located event isn't worth framing as a trip:
     * the reminder shows the plain event title instead of "Leave for …".
     */
    const val MIN_TRIP_TRAVEL_MS: Long = 5 * 60_000L
}
