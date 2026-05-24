package app.nock.android.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class SpeechResult {
    data class Final(val text: String) : SpeechResult()
    object Cancelled : SpeechResult()
    data class Error(val message: String) : SpeechResult()
}

/**
 * Hold-to-record wrapper around the platform SpeechRecognizer.
 *
 * Lifecycle: caller invokes [start] when the user presses, [stop] when they release.
 * Recording continues for as long as the session is open — if the platform recognizer
 * auto-ends on silence before [stop] is called, the manager transparently restarts it
 * so the user can pause without losing the session. The running transcript is tracked
 * by [VoiceTranscriptBuffer], whose contract is that the text it returns only ever
 * grows for the lifetime of one session — pauses, partial-result regressions, and
 * stingy onResults can never delete words the user already saw transcribed.
 *
 * Must be created and used from the main thread (SpeechRecognizer requirement).
 */
@Singleton
class SpeechToTextManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    interface Session {
        fun stop()
        fun cancel()
    }

    fun start(
        languageTag: String? = null,
        onPartial: (String) -> Unit = {},
        onResult: (SpeechResult) -> Unit
    ): Session {
        if (!isAvailable()) {
            onResult(SpeechResult.Error("Speech recognition not available on this device"))
            return NoopSession
        }

        val tag = languageTag?.ifBlank { null } ?: Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Tell the recognizer to be very tolerant of pauses — we drive end-of-input from
            // button release, not from silence detection. Many OEMs ignore these; the
            // restart-on-auto-end loop below is the real safety net.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000)
        }

        var resolved = false
        var stopRequested = false
        val transcript = VoiceTranscriptBuffer()
        var recognizer: SpeechRecognizer? = null

        fun resolve(r: SpeechResult) {
            if (resolved) return
            resolved = true
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            onResult(r)
        }

        fun resolveFinal() {
            val text = transcript.current()
            resolve(if (text.isBlank()) SpeechResult.Cancelled else SpeechResult.Final(text))
        }

        fun startInner() {
            if (resolved) return
            val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    transcript.onPartial(text)
                    onPartial(transcript.current())
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    transcript.commit(text)
                    onPartial(transcript.current())
                    if (stopRequested) {
                        resolveFinal()
                    } else {
                        // Recognizer auto-ended on silence, but the user is still holding the
                        // button. Tear down and restart so recording continues.
                        try { rec.destroy() } catch (_: Throwable) {}
                        startInner()
                    }
                }

                override fun onError(error: Int) {
                    val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (recoverable) {
                        transcript.commit("")
                        onPartial(transcript.current())
                        if (!stopRequested) {
                            try { rec.destroy() } catch (_: Throwable) {}
                            startInner()
                        } else {
                            resolveFinal()
                        }
                        return
                    }
                    resolve(SpeechResult.Error(errorToMessage(error)))
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                rec.startListening(intent)
            } catch (t: Throwable) {
                resolve(SpeechResult.Error(t.message ?: "startListening failed"))
            }
        }

        startInner()

        return object : Session {
            override fun stop() {
                stopRequested = true
                val rec = recognizer ?: run { resolveFinal(); return }
                try {
                    rec.stopListening()
                } catch (_: Throwable) {
                    resolveFinal()
                }
            }
            override fun cancel() {
                stopRequested = true
                try { recognizer?.cancel() } catch (_: Throwable) {}
                resolve(SpeechResult.Cancelled)
            }
        }
    }

    private object NoopSession : Session {
        override fun stop() {}
        override fun cancel() {}
    }

    private fun errorToMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error ($code)"
    }
}
