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
 * Every call, intent extra, and listener callback is logged through
 * [VoiceLogger] so problems can be reproduced and reported by copying the
 * log out of Settings.
 *
 * Must be created and used from the main thread (SpeechRecognizer requirement).
 */
@Singleton
class SpeechToTextManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val logger: VoiceLogger,
) {
    fun isAvailable(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(ctx)
        logger.log(TAG, "isAvailable() = $available")
        return available
    }

    interface Session {
        fun stop()
        fun cancel()
    }

    fun start(
        languageTag: String? = null,
        onPartial: (String) -> Unit = {},
        onResult: (SpeechResult) -> Unit
    ): Session {
        logger.log(TAG, "start(languageTag=$languageTag) called")

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            logger.log(TAG, "start → recognizer NOT available; emitting Error")
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
        logger.log(TAG, "intent extras: model=FREE_FORM, partial=true, lang=$tag, " +
            "callingPackage=${ctx.packageName}, completeSilence=60000ms, " +
            "possiblyCompleteSilence=60000ms, minimumLength=60000ms")

        var resolved = false
        var lastPartial = ""
        var recognizer: SpeechRecognizer? = null

        fun resolve(r: SpeechResult) {
            if (resolved) {
                logger.log(TAG, "resolve($r) ignored — already resolved")
                return
            }
            resolved = true
            val summary = when (r) {
                is SpeechResult.Final -> "Final(\"${r.text}\")"
                is SpeechResult.Error -> "Error(\"${r.message}\")"
                is SpeechResult.Cancelled -> "Cancelled"
            }
            logger.log(TAG, "resolve → $summary (lastPartial=\"$lastPartial\")")
            try { recognizer?.destroy() } catch (t: Throwable) {
                logger.log(TAG, "recognizer.destroy() threw: ${t.message}")
            }
            recognizer = null
            onResult(r)
        }

        /** Whichever of [finalText] or the last partial we saw carries more text. */
        fun bestText(finalText: String): String {
            val f = finalText.trim()
            val p = lastPartial.trim()
            return if (f.length >= p.length) f else p
        }

        logger.log(TAG, "createSpeechRecognizer() …")
        val rec = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (t: Throwable) {
            logger.log(TAG, "createSpeechRecognizer threw: ${t.message}; emitting Error")
            onResult(SpeechResult.Error(t.message ?: "Failed to create recognizer"))
            return NoopSession
        }
        logger.log(TAG, "createSpeechRecognizer OK")
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                logger.log(TAG, "← onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                logger.log(TAG, "← onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {
                // Fires 20+ times/sec — too noisy to log every call.
            }
            override fun onBufferReceived(buffer: ByteArray?) {
                // Fires very frequently — skip.
            }
            override fun onEndOfSpeech() {
                logger.log(TAG, "← onEndOfSpeech")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                logger.log(TAG, "← onPartialResults \"$text\"")
                if (text.isBlank()) return
                lastPartial = text
                onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                logger.log(TAG, "← onResults \"$text\" (lastPartial=\"$lastPartial\")")
                val finalText = bestText(text)
                resolve(if (finalText.isBlank()) SpeechResult.Cancelled else SpeechResult.Final(finalText))
            }

            override fun onError(error: Int) {
                logger.log(TAG, "← onError code=$error (${errorToMessage(error)}) lastPartial=\"$lastPartial\"")
                // If the user said anything at all this session, keep it — emit Final
                // instead of Error so the caller's accumulator can hold on to it.
                val text = lastPartial.trim()
                if (text.isNotBlank()) {
                    resolve(SpeechResult.Final(text))
                } else {
                    resolve(SpeechResult.Error(errorToMessage(error)))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                logger.log(TAG, "← onEvent type=$eventType")
            }
        })
        try {
            logger.log(TAG, "rec.startListening() …")
            rec.startListening(intent)
            logger.log(TAG, "rec.startListening() returned")
        } catch (t: Throwable) {
            logger.log(TAG, "rec.startListening threw: ${t.message}")
            resolve(SpeechResult.Error(t.message ?: "startListening failed"))
        }

        return object : Session {
            override fun stop() {
                logger.log(TAG, "Session.stop() called")
                val r = recognizer
                if (r == null) {
                    logger.log(TAG, "Session.stop() — recognizer already null")
                    return
                }
                try {
                    r.stopListening()
                    logger.log(TAG, "rec.stopListening() returned")
                } catch (t: Throwable) {
                    logger.log(TAG, "rec.stopListening threw: ${t.message}")
                    val text = lastPartial.trim()
                    resolve(if (text.isBlank()) SpeechResult.Cancelled else SpeechResult.Final(text))
                }
            }
            override fun cancel() {
                logger.log(TAG, "Session.cancel() called")
                try { recognizer?.cancel() } catch (t: Throwable) {
                    logger.log(TAG, "rec.cancel threw: ${t.message}")
                }
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

    private companion object {
        const val TAG = "STT"
    }
}
