package app.nock.android.voice

/**
 * Monotone transcript buffer for the hold-to-record voice session.
 *
 * Guarantees that [current] never returns a string shorter than any value it
 * has previously returned for this instance. The platform SpeechRecognizer
 * auto-ends on silence (we restart it across pauses), can deliver an
 * onResults shorter than the last partial it emitted, and can emit a
 * regressing partial mid-segment — none of those should ever wipe text the
 * user already saw transcribed.
 *
 * Not thread-safe; drive it from the main thread alongside the recognizer
 * listener callbacks.
 */
internal class VoiceTranscriptBuffer {
    private val accumulated = StringBuilder()
    private var currentPartial: String = ""
    private var bestPartialThisSegment: String = ""
    private var bestEverDisplayed: String = ""

    fun current(): String {
        val partial = if (currentPartial.length >= bestPartialThisSegment.length) {
            currentPartial
        } else {
            bestPartialThisSegment
        }
        val combined = buildString {
            append(accumulated)
            val trimmed = partial.trim()
            if (trimmed.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(trimmed)
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
     * or the longest partial seen this segment carries more text. Prevents a
     * stingy onResults from dropping words the partial stream already
     * captured.
     */
    fun commit(finalText: String) {
        val finalTrim = finalText.trim()
        val bestTrim = bestPartialThisSegment.trim()
        val toCommit = if (finalTrim.length >= bestTrim.length) finalTrim else bestTrim
        if (toCommit.isNotEmpty()) {
            if (accumulated.isNotEmpty()) accumulated.append(' ')
            accumulated.append(toCommit)
        }
        currentPartial = ""
        bestPartialThisSegment = ""
        current()
    }
}
