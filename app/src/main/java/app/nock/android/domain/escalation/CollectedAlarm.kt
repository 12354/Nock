package app.nock.android.domain.escalation

import app.nock.android.domain.model.StageType

/**
 * Pure rules for the "collected alarm": when the Bluetooth pause ends with
 * several reminders held, they are presented as ONE alarm instead of a burst of
 * alarms one after another. One held escalation becomes the lead — it walks its
 * own chain and is what rings — and the others ride along silently; the lead
 * shows every name, and its Done completes them all.
 */
object CollectedAlarm {

    /** A held escalation that is about to be released. */
    data class Candidate(
        val escalationId: Long,
        val name: String,
        val dueStage: StageType,
        val startedAtMs: Long,
        val simpleVibration: Boolean,
    )

    /**
     * How insistent a stage is, so the collected alarm is as loud as the most
     * urgent reminder in it. (Not StageType's declaration order: that puts
     * TELEGRAM below VIBRATE, but a Telegram stage both posts and messages.)
     */
    fun severity(type: StageType): Int = when (type) {
        StageType.SILENT -> 0
        StageType.VIBRATE -> 1
        StageType.TELEGRAM -> 2
        StageType.NOTIFICATION -> 3
        StageType.ALARM_VIBRATE -> 4
        StageType.ALARM -> 5
    }

    /**
     * The lead: a real escalating reminder over a single-vibration one (which
     * auto-completes on its first fire and so could not carry the others), then
     * the most urgent due stage, then the one that has been waiting longest.
     */
    fun pickLead(candidates: List<Candidate>): Candidate =
        candidates.sortedWith(
            compareBy<Candidate> { it.simpleVibration }
                .thenByDescending { severity(it.dueStage) }
                .thenBy { it.startedAtMs }
                .thenBy { it.escalationId }
        ).first()

    /** One title for the whole collection: the lead first, the rest in order. */
    fun title(leadName: String, followerNames: List<String>): String {
        if (followerNames.isEmpty()) return leadName
        val names = listOf(leadName) + followerNames
        val shown = names.take(MAX_NAMES_IN_TITLE)
        val more = names.size - shown.size
        return shown.joinToString(" · ") + if (more > 0) " +$more" else ""
    }

    private const val MAX_NAMES_IN_TITLE = 4
}
