package app.nock.android.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.voice.SpeechResult
import app.nock.android.voice.SpeechToTextManager
import app.nock.android.voice.VoiceAlarmCreator
import app.nock.android.voice.VoiceAlarmOutcome
import app.nock.android.voice.VoiceLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class VoiceAlarmUiState {
    object Idle : VoiceAlarmUiState()
    data class Listening(val partial: String) : VoiceAlarmUiState()
    data class Thinking(val transcript: String) : VoiceAlarmUiState()
    data class Success(val reminderName: String) : VoiceAlarmUiState()
    data class Error(val message: String) : VoiceAlarmUiState()
}

/**
 * Hold-to-record voice alarm flow.
 *
 * The persistent transcript lives here, not in the recognizer. SpeechToTextManager
 * is a one-shot wrapper — when the recognizer auto-ends on silence we commit its
 * text to [accumulated], spin up a new recognizer, and keep going. The displayed
 * string is always [accumulated] + the current session's last partial, so a
 * mid-pause auto-end can't visibly reset the text.
 *
 * Every press, callback, commit, and restart is logged through [VoiceLogger] so
 * the user can copy a full transcript of what happened out of Settings.
 */
@HiltViewModel
class VoiceAlarmViewModel @Inject constructor(
    private val stt: SpeechToTextManager,
    private val creator: VoiceAlarmCreator,
    private val logger: VoiceLogger,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceAlarmUiState>(VoiceAlarmUiState.Idle)
    val state: StateFlow<VoiceAlarmUiState> = _state.asStateFlow()

    private var session: SpeechToTextManager.Session? = null
    private var isHolding = false

    // Text the user has already produced this hold — survives every recognizer
    // restart that happens between segments.
    private var accumulated: String = ""
    // The currently-running recognizer's last partial. Reset on each segment.
    private var sessionPartial: String = ""

    fun isAvailable(): Boolean = stt.isAvailable()

    fun startListening() {
        logger.log(TAG, "startListening() called (isHolding=$isHolding)")
        if (isHolding) {
            logger.log(TAG, "startListening ignored — already holding")
            return
        }
        isHolding = true
        accumulated = ""
        sessionPartial = ""
        _state.value = VoiceAlarmUiState.Listening(partial = "")
        startSession()
    }

    private fun startSession() {
        if (session != null) {
            logger.log(TAG, "startSession ignored — session already active")
            return
        }
        logger.log(TAG, "startSession() — accumulated so far=\"$accumulated\"")
        sessionPartial = ""
        session = stt.start(
            onPartial = { partial ->
                sessionPartial = partial
                val display = displayedText()
                logger.log(TAG, "onPartial \"$partial\" → display=\"$display\"")
                publishListening()
            },
            onResult = { result ->
                session = null
                val summary = when (result) {
                    is SpeechResult.Final -> "Final(\"${result.text}\")"
                    is SpeechResult.Cancelled -> "Cancelled"
                    is SpeechResult.Error -> "Error(\"${result.message}\")"
                }
                logger.log(TAG, "onResult $summary (isHolding=$isHolding, " +
                    "accumulated=\"$accumulated\", sessionPartial=\"$sessionPartial\")")
                when (result) {
                    is SpeechResult.Final -> commitSegment(result.text)
                    is SpeechResult.Cancelled -> {
                        // Nothing new to commit — keep the accumulator intact.
                    }
                    is SpeechResult.Error -> {
                        if (!isHolding && accumulated.isBlank()) {
                            logger.log(TAG, "  error + not holding + nothing accumulated → surfacing as UI error")
                            _state.value = VoiceAlarmUiState.Error(result.message)
                            return@start
                        }
                        logger.log(TAG, "  error but we have text or are still holding → keep going")
                    }
                }
                sessionPartial = ""

                if (isHolding) {
                    publishListening()
                    logger.log(TAG, "  scheduling restart in ${RESTART_DELAY_MS}ms")
                    viewModelScope.launch {
                        delay(RESTART_DELAY_MS)
                        if (isHolding && session == null) {
                            logger.log(TAG, "  restart delay elapsed → startSession()")
                            startSession()
                        } else {
                            logger.log(TAG, "  restart skipped (isHolding=$isHolding, session=${session != null})")
                        }
                    }
                } else {
                    logger.log(TAG, "  not holding → finalizeTranscript()")
                    finalizeTranscript()
                }
            }
        )
    }

    fun stopListening() {
        logger.log(TAG, "stopListening() called (isHolding=$isHolding, session=${session != null})")
        if (!isHolding) {
            logger.log(TAG, "stopListening ignored — not holding")
            return
        }
        isHolding = false
        val s = session
        if (s != null) {
            logger.log(TAG, "stopListening → session.stop()")
            s.stop() // result delivery comes back via onResult → finalizeTranscript
        } else {
            logger.log(TAG, "stopListening → no session, finalizing directly")
            finalizeTranscript()
        }
    }

    fun cancel() {
        logger.log(TAG, "cancel() called (isHolding=$isHolding, session=${session != null}, " +
            "accumulated=\"$accumulated\")")
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
        if (t.isEmpty()) {
            logger.log(TAG, "commitSegment(\"\") — nothing to commit")
            return
        }
        val before = accumulated
        accumulated = if (accumulated.isEmpty()) t else "$accumulated $t"
        logger.log(TAG, "commitSegment \"$t\": \"$before\" → \"$accumulated\"")
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
        logger.log(TAG, "finalizeTranscript() → \"$text\"")
        accumulated = ""
        sessionPartial = ""
        if (text.isEmpty()) {
            logger.log(TAG, "  empty transcript → Idle")
            _state.value = VoiceAlarmUiState.Idle
            return
        }
        _state.value = VoiceAlarmUiState.Thinking(text)
        viewModelScope.launch {
            val outcome = creator.createFromTranscript(text)
            logger.log(TAG, "createFromTranscript outcome=${outcome::class.simpleName}")
            _state.value = when (outcome) {
                is VoiceAlarmOutcome.Created -> VoiceAlarmUiState.Success(outcome.reminder.name)
                is VoiceAlarmOutcome.Failed -> VoiceAlarmUiState.Error(outcome.message)
            }
        }
    }

    private companion object {
        const val TAG = "VM"
        const val RESTART_DELAY_MS = 75L
    }
}
