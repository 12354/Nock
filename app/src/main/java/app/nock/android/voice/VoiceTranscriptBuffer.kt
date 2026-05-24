package app.nock.android.voice

/**
 * Monotone transcript buffer for the hold-to-record voice session.
 *
 * Once any text reaches this buffer it stays — neither a regressing partial,
 * an empty onResults, a restart between segments, nor a recognizer error can
 * shorten the string [current] returns. The buffer is shared across every
 * recognizer instance spawned during one hold-to-record session; the
 * recognizer is just an audio→text source, this buffer owns the transcript.
 *
 * Three layered invariants enforce the "never delete already-spoken text"
 * guarantee:
 *  1. Committed segments accumulate in [accumulated]; commits only ever
 *     append (or are skipped if duplicate / empty).
 *  2. The displayed string ([current]) is a high-water mark stored in
 *     [bestEverDisplayed] — it can grow but never shrink within a session.
 *  3. A new segment's commit chooses whichever of the final text or the
 *     longest partial seen this segment carries more text, so a stingy
 *     onResults can't drop words the partial stream already captured.
 *
 * Duplicate detection: if a fresh recognizer (post-restart) replays the same
 * onResults text it just emitted before the restart — some OEMs buffer audio
 * across instances — the duplicate is dropped.
 *
 * Not thread-safe; drive it from the main thread alongside the recognizer
 * listener callbacks.
 */
internal class VoiceTranscriptBuffer {
    private val accumulated = StringBuilder()
    private var currentPartial: String = ""
    private var bestPartialThisSegment: String = ""
    private var bestEverDisplayed: String = ""
    private var lastCommittedSegment: String = ""

    fun current(): String {
        val partial = if (currentPartial.length >= bestPartialThisSegment.length) {
            currentPartial
        } else {
            bestPartialThisSegment
        }
        val combined = buildString {
            append(accumulated)
            val effective = effectivePartial(partial.trim())
            if (effective.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(effective)
            }
        }
        if (combined.length > bestEverDisplayed.length) {
            bestEverDisplayed = combined
        }
        return bestEverDisplayed
    }

    fun onPartial(text: String) {
        if (text.isBlank()) return
        currentPartial = text
        if (text.length > bestPartialThisSegment.length) {
            bestPartialThisSegment = text
        }
        current()
    }

    /**
     * Commit the current recognizer segment, picking whichever of [finalText]
     * or the longest partial seen this segment carries more text. An empty
     * [finalText] (recoverable error / restart on silence) still flushes the
     * partial we already saw. An exact duplicate of the previous committed
     * segment is dropped, and a segment that extends the previous one
     * (prefix overlap) only contributes its new suffix — both happen when a
     * restarted recognizer re-fires text from buffered audio on some OEMs.
     */
    fun commit(finalText: String) {
        val finalTrim = finalText.trim()
        val bestTrim = bestPartialThisSegment.trim()
        val toCommit = if (finalTrim.length >= bestTrim.length) finalTrim else bestTrim

        // Always reset segment-local state — the next segment starts fresh.
        currentPartial = ""
        bestPartialThisSegment = ""

        if (toCommit.isNotEmpty()) {
            val newPortion = stripLeadingOverlap(toCommit)
            if (newPortion.isNotEmpty()) {
                if (accumulated.isNotEmpty()) accumulated.append(' ')
                accumulated.append(newPortion)
            }
            // Track the full committed phrase (not just the new portion) so a
            // subsequent restart-replay can be recognised and deduplicated.
            lastCommittedSegment = toCommit
        }
        current()
    }

    /**
     * Drop the part of a partial that exactly duplicates the last committed
     * segment. Lets the display show a recognizer's "echo + new words" partial
     * without rendering the echoed prefix twice.
     */
    private fun effectivePartial(trimmedPartial: String): String =
        stripLeadingOverlap(trimmedPartial)

    /**
     * If [text] equals [lastCommittedSegment] or starts with it followed by a
     * space, return only the new tail. Otherwise return [text] unchanged.
     */
    private fun stripLeadingOverlap(text: String): String {
        if (text.isEmpty()) return ""
        if (lastCommittedSegment.isEmpty()) return text
        if (text == lastCommittedSegment) return ""
        val prefix = "$lastCommittedSegment "
        if (text.startsWith(prefix)) {
            return text.substring(prefix.length)
        }
        return text
    }
}
