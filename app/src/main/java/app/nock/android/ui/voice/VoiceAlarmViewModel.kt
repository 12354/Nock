package app.nock.android.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.voice.SpeechResult
import app.nock.android.voice.SpeechToTextManager
import app.nock.android.voice.VoiceAlarmCreator
import app.nock.android.voice.VoiceAlarmOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class VoiceAlarmViewModel @Inject constructor(
    private val stt: SpeechToTextManager,
    private val creator: VoiceAlarmCreator,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceAlarmUiState>(VoiceAlarmUiState.Idle)
    val state: StateFlow<VoiceAlarmUiState> = _state.asStateFlow()

    private var session: SpeechToTextManager.Session? = null

    fun isAvailable(): Boolean = stt.isAvailable()

    fun startListening() {
        if (session != null) return
        _state.value = VoiceAlarmUiState.Listening(partial = "")
        session = stt.start(
            onPartial = { partial ->
                _state.update { cur ->
                    if (cur is VoiceAlarmUiState.Listening) VoiceAlarmUiState.Listening(partial) else cur
                }
            },
            onResult = { result ->
                session = null
                when (result) {
                    is SpeechResult.Final -> handleTranscript(result.text)
                    is SpeechResult.Cancelled -> _state.value = VoiceAlarmUiState.Idle
                    is SpeechResult.Error -> _state.value = VoiceAlarmUiState.Error(result.message)
                }
            }
        )
    }

    fun stopListening() {
        session?.stop()
        // Result delivery happens asynchronously via onResult.
    }

    fun cancel() {
        session?.cancel()
        session = null
        _state.value = VoiceAlarmUiState.Idle
    }

    fun reset() {
        cancel()
    }

    private fun handleTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.value = VoiceAlarmUiState.Idle
            return
        }
        _state.value = VoiceAlarmUiState.Thinking(trimmed)
        viewModelScope.launch {
            _state.value = when (val r = creator.createFromTranscript(trimmed)) {
                is VoiceAlarmOutcome.Created -> VoiceAlarmUiState.Success(r.reminder.name)
                is VoiceAlarmOutcome.Failed -> VoiceAlarmUiState.Error(r.message)
            }
        }
    }
}
