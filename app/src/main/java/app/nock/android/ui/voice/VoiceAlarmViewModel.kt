package app.nock.android.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.voice.PendingVoiceProcessor
import app.nock.android.voice.SpeechResult
import app.nock.android.voice.SpeechToTextManager
import app.nock.android.voice.VoiceAlarmCreator
import app.nock.android.voice.VoiceAlarmOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class VoiceAlarmUiState {
    object Idle : VoiceAlarmUiState()
    data class Listening(val partial: String) : VoiceAlarmUiState()
    data class Thinking(val transcript: String) : VoiceAlarmUiState()
    data class Success(val message: String) : VoiceAlarmUiState()
    data class Error(val message: String) : VoiceAlarmUiState()
}

/**
 * Hold-to-record voice alarm flow.
 *
 * The persistent transcript lives here, not in the recognizer. SpeechToTextManager
 * is a one-shot wrapper that emits three streams of callbacks:
 *  * onPartial — the current segment's growing text.
 *  * onSegmentComplete — the recognizer just internally finalized a segment
 *    (mid-utterance silence). We commit it to [accumulated] right away,
 *    BEFORE the next chunk's partials replace it on screen.
 *  * onResult — Final / Cancelled / Error when the recognizer ends entirely.
 *
 * Whenever a session ends mid-hold (auto-end, recoverable error, anything),
 * we commit and spin up a new recognizer. The displayed string is always
 * [accumulated] + the current session's last partial, so a mid-pause segment
 * break can't visibly reset the text.
 */
@HiltViewModel
class VoiceAlarmViewModel @Inject constructor(
    private val stt: SpeechToTextManager,
    private val creator: VoiceAlarmCreator,
    processor: PendingVoiceProcessor,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceAlarmUiState>(VoiceAlarmUiState.Idle)
    val state: StateFlow<VoiceAlarmUiState> = _state.asStateFlow()

    /**
     * Confirmation toasts emitted when a queued transcript finishes processing
     * into a real reminder. Distinct from [state] because the work completes in
     * the background, after the capture flow has already returned to idle.
     */
    val toasts: SharedFlow<String> = processor.added

    private var session: SpeechToTextManager.Session? = null
    private var isHolding = false

    // Text the user has already produced this hold — survives every recognizer
    // restart that happens between segments.
    private var accumulated: String = ""
    // The currently-running recognizer's last partial. Reset on each segment.
    private var sessionPartial: String = ""

    fun isAvailable(): Boolean = stt.isAvailable()

    fun startListening() {
        if (isHolding) return
        isHolding = true
        accumulated = ""
        sessionPartial = ""
        _state.value = VoiceAlarmUiState.Listening(partial = "")
        startSession()
    }

    private fun startSession() {
        if (session != null) return
        sessionPartial = ""
        session = stt.start(
            onPartial = { partial ->
                sessionPartial = partial
                publishListening()
            },
            onSegmentComplete = { segment ->
                // Recognizer internally finalized a segment without firing
                // onResults — commit now so the next chunk's partials can't
                // erase it.
                commitSegment(segment)
                sessionPartial = ""
                publishListening()
            },
            onResult = { result ->
                session = null
                when (result) {
                    is SpeechResult.Final -> commitSegment(result.text)
                    is SpeechResult.Cancelled -> {
                        // Nothing new to commit — keep the accumulator intact.
                    }
                    is SpeechResult.Error -> {
                        if (!isHolding && accumulated.isBlank()) {
                            _state.value = VoiceAlarmUiState.Error(result.message)
                            return@start
                        }
                    }
                }
                sessionPartial = ""

                if (isHolding) {
                    publishListening()
                    viewModelScope.launch {
                        delay(RESTART_DELAY_MS)
                        if (isHolding && session == null) {
                            startSession()
                        }
                    }
                } else {
                    finalizeTranscript()
                }
            }
        )
    }

    fun stopListening() {
        if (!isHolding) return
        isHolding = false
        val s = session
        if (s != null) {
            s.stop() // result delivery comes back via onResult → finalizeTranscript
        } else {
            finalizeTranscript()
        }
    }

    fun cancel() {
        isHolding = false
        accumulated = ""
        sessionPartial = ""
        session?.cancel()
        session = null
        _state.value = VoiceAlarmUiState.Idle
    }

    fun reset() = cancel()

    private fun commitSegment(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        accumulated = if (accumulated.isEmpty()) t else "$accumulated $t"
    }

    private fun displayedText(): String {
        val partial = sessionPartial.trim()
        return when {
            partial.isEmpty() -> accumulated
            accumulated.isEmpty() -> partial
            else -> "$accumulated $partial"
        }
    }

    private fun publishListening() {
        _state.update { cur ->
            if (cur is VoiceAlarmUiState.Listening) VoiceAlarmUiState.Listening(displayedText()) else cur
        }
    }

    private fun finalizeTranscript() {
        val text = displayedText().trim()
        accumulated = ""
        sessionPartial = ""
        if (text.isEmpty()) {
            _state.value = VoiceAlarmUiState.Idle
            return
        }
        _state.value = VoiceAlarmUiState.Thinking(text)
        viewModelScope.launch {
            val outcome = creator.createFromTranscript(text)
            _state.value = when (outcome) {
                is VoiceAlarmOutcome.Captured -> VoiceAlarmUiState.Success(outcome.message)
                is VoiceAlarmOutcome.Failed -> VoiceAlarmUiState.Error(outcome.message)
            }
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 75L
    }
}
