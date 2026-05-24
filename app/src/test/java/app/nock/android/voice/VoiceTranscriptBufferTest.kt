package app.nock.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptBufferTest {

    @Test fun empty_buffer_returns_empty() {
        assertEquals("", VoiceTranscriptBuffer().current())
    }

    @Test fun blank_partial_is_ignored() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("")
        buf.onPartial("   ")
        assertEquals("", buf.current())
    }

    @Test fun partial_then_matching_final_keeps_text_once() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Hello")
        buf.commit("Hello")
        assertEquals("Hello", buf.current())
    }

    @Test fun multiple_segments_join_with_a_single_space() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Hello")
        buf.commit("Hello")
        buf.onPartial("world")
        buf.commit("world")
        assertEquals("Hello world", buf.current())
    }

    @Test fun pause_with_empty_results_preserves_partial() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Buy milk")
        // Recognizer auto-ends and returns an empty onResults.
        buf.commit("")
        assertEquals("Buy milk", buf.current())
    }

    @Test fun final_shorter_than_partial_keeps_partial() {
        val buf = VoiceTranscriptBuffer()
        // OEM that returns only the last phrase as the final result.
        buf.onPartial("Buy milk and bread tomorrow")
        buf.commit("tomorrow")
        assertEquals("Buy milk and bread tomorrow", buf.current())
    }

    @Test fun partial_regression_does_not_shrink_displayed_text() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Buy milk and bread")
        assertEquals("Buy milk and bread", buf.current())
        // Recognizer second-guesses itself mid-segment.
        buf.onPartial("Buy")
        assertEquals("Buy milk and bread", buf.current())
    }

    @Test fun recoverable_error_after_partial_preserves_partial() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Test message")
        // onError(NO_MATCH) path commits with empty final.
        buf.commit("")
        assertEquals("Test message", buf.current())
    }

    @Test fun final_longer_than_partial_uses_final() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Hello wo")
        buf.commit("Hello world")
        assertEquals("Hello world", buf.current())
    }

    @Test fun repeated_empty_commits_after_speech_do_not_change_text() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Hello world")
        buf.commit("Hello world")
        // User keeps holding the button silent; restart loop keeps firing empty commits.
        buf.commit("")
        buf.commit("")
        buf.commit("")
        assertEquals("Hello world", buf.current())
    }

    @Test fun displayed_text_grows_monotonically_across_a_session() {
        val buf = VoiceTranscriptBuffer()
        val snapshots = mutableListOf<String>()

        buf.onPartial("Buy")
        snapshots += buf.current()
        buf.onPartial("Buy milk")
        snapshots += buf.current()
        buf.onPartial("Buy milk and bread")
        snapshots += buf.current()
        // Recognizer regresses just before auto-ending.
        buf.onPartial("Buy")
        snapshots += buf.current()
        // Auto-end with shorter onResults.
        buf.commit("Buy")
        snapshots += buf.current()
        // Restart, user pauses silently, recoverable error.
        buf.commit("")
        snapshots += buf.current()
        // User resumes speaking.
        buf.onPartial("tomorrow")
        snapshots += buf.current()
        buf.commit("tomorrow at 9am")
        snapshots += buf.current()

        for (i in 1 until snapshots.size) {
            assertTrue(
                "snapshot ${i - 1} -> $i shrank: ${snapshots[i - 1]} -> ${snapshots[i]}",
                snapshots[i].length >= snapshots[i - 1].length
            )
        }
        assertEquals("Buy milk and bread tomorrow at 9am", snapshots.last())
    }

    @Test fun final_with_only_whitespace_is_treated_as_empty() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("Hello")
        buf.commit("   ")
        assertEquals("Hello", buf.current())
    }

    @Test fun new_segment_uses_best_partial_when_final_is_empty() {
        val buf = VoiceTranscriptBuffer()
        buf.onPartial("first")
        buf.commit("first")
        buf.onPartial("second long phrase")
        buf.onPartial("second")
        buf.commit("")
        assertEquals("first second long phrase", buf.current())
    }
}
