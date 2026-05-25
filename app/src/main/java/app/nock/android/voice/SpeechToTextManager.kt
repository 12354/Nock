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
    /** Recognizer finished a chunk. [text] is the longest text we saw this segment. */
    data class Final(val text: String) : SpeechResult()
    /** Caller called [Session.cancel] or the recognizer produced no speech at all. */
    object Cancelled : SpeechResult()
    /** Unrecoverable failure with nothing to keep. */
    data class Error(val message: String) : SpeechResult()
}

/**
 * Thin one-shot wrapper around the platform SpeechRecognizer.
 *
 * One [start] call = one recognizer instance = one Final emitted when the
 * recognizer auto-ends on silence, the caller calls [Session.stop], or an
 * unrecoverable error happens. Concretely:
 *
 *  * onResults / stop : Final with the recognized text (or whichever of the
 *    final text or the last partial was longer — some OEMs return a stingy
 *    final shorter than the last partial they emitted).
 *  * onError with a partial already shown : Final with that partial, so the
 *    caller can keep whatever the user just said.
 *  * onError with nothing yet : Error.
 *  * stop with no recognizer alive : Final / Cancelled depending on partial.
 *
 * No internal restart loop. The caller (the ViewModel) owns the persistent
 * transcript and the "keep going across pauses" policy — that's the only
 * place where the accumulated text outlives any single recognizer instance.
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
            // Be tolerant of pauses — many OEMs ignore these, but when honoured they
            // delay the auto-end so the same recognizer keeps capturing across a brief
            // mid-sentence breath.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000)
        }

        var resolved = false
        var lastPartial = ""
        var recognizer: SpeechRecognizer? = null

        fun resolve(r: SpeechResult) {
            if (resolved) return
            resolved = true
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            onResult(r)
        }

        /** Whichever of [finalText] or the last partial we saw carries more text. */
        fun bestText(finalText: String): String {
            val f = finalText.trim()
            val p = lastPartial.trim()
            return if (f.length >= p.length) f else p
        }

        val rec = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (t: Throwable) {
            onResult(SpeechResult.Error(t.message ?: "Failed to create recognizer"))
            return NoopSession
        }
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
                if (text.isBlank()) return
                lastPartial = text
                onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                val finalText = bestText(text)
                resolve(if (finalText.isBlank()) SpeechResult.Cancelled else SpeechResult.Final(finalText))
            }

            override fun onError(error: Int) {
                // If the user said anything at all this session, keep it — emit Final
                // instead of Error so the caller's accumulator can hold on to it.
                val text = lastPartial.trim()
                if (text.isNotBlank()) {
                    resolve(SpeechResult.Final(text))
                } else {
                    resolve(SpeechResult.Error(errorToMessage(error)))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            rec.startListening(intent)
        } catch (t: Throwable) {
            resolve(SpeechResult.Error(t.message ?: "startListening failed"))
        }

        return object : Session {
            override fun stop() {
                val r = recognizer ?: return
                try {
                    r.stopListening()
                } catch (_: Throwable) {
                    val text = lastPartial.trim()
                    resolve(if (text.isBlank()) SpeechResult.Cancelled else SpeechResult.Final(text))
                }
            }
            override fun cancel() {
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
