package app.nock.android.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Lifecycle: caller invokes [start] when the user presses, [Session.stop]
 * when they release. Recording continues for as long as the session is open
 * — if the platform recognizer auto-ends on silence, errors out, or its
 * network drops, the manager transparently spins up a fresh recognizer with
 * exponential backoff so the user can pause without losing the session.
 *
 * "Already-spoken text never gets deleted" is the load-bearing guarantee.
 * The recognizer is just a means to extract more text; the
 * [VoiceTranscriptBuffer] owns the transcript and outlives every recognizer
 * instance spawned during the session. Concretely:
 *
 *  * Recoverable AND non-recoverable recognizer errors are both retried as
 *    long as the user is still holding the button. The only "hard fatal"
 *    error during a session is [SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS].
 *  * Any error that DOES terminate the session resolves to
 *    [SpeechResult.Final] when the buffer is non-empty — never
 *    [SpeechResult.Error] — so previously transcribed text is processed
 *    instead of discarded.
 *  * Restarts back off (75ms → 150ms → 300ms → 600ms → 1000ms) and give up
 *    after [MAX_CONSECUTIVE_ERRORS] failures with no intervening progress,
 *    again preferring to emit [SpeechResult.Final] over throwing text away.
 *  * On [Session.stop], if the recognizer never delivers a final event
 *    within [STOP_TIMEOUT_MS], the buffer's contents are resolved anyway.
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
        var restartScheduled = false
        var consecutiveErrors = 0
        val transcript = VoiceTranscriptBuffer()
        var recognizer: SpeechRecognizer? = null
        val handler = Handler(Looper.getMainLooper())

        fun resolve(r: SpeechResult) {
            if (resolved) return
            resolved = true
            handler.removeCallbacksAndMessages(null)
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            onResult(r)
        }

        // The transcript is sacred — anywhere we'd otherwise throw it away, this
        // helper makes sure non-empty text becomes a Final instead.
        fun resolvePreservingText(errorCode: Int?) {
            val text = transcript.current()
            when {
                text.isNotBlank() -> resolve(SpeechResult.Final(text))
                errorCode != null -> resolve(SpeechResult.Error(errorToMessage(errorCode)))
                else -> resolve(SpeechResult.Cancelled)
            }
        }

        var scheduleRestart: ((SpeechRecognizer?) -> Unit)? = null

        fun startInner() {
            if (resolved) return
            val rec = try {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            } catch (_: Throwable) {
                consecutiveErrors++
                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS || stopRequested) {
                    resolvePreservingText(SpeechRecognizer.ERROR_CLIENT)
                } else {
                    scheduleRestart?.invoke(null)
                }
                return
            }
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    // Audio is flowing — reset the failure counter so a long quiet
                    // period earlier doesn't penalize a recovered session.
                    consecutiveErrors = 0
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) consecutiveErrors = 0
                    transcript.onPartial(text)
                    onPartial(transcript.current())
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) consecutiveErrors = 0
                    transcript.commit(text)
                    onPartial(transcript.current())
                    if (stopRequested) {
                        resolvePreservingText(errorCode = null)
                    } else {
                        // Recognizer auto-ended on silence, but the user is still holding the
                        // button. Schedule a restart on the next main-loop tick so the old
                        // instance can fully release its audio session — restarting
                        // synchronously from inside this callback races against the teardown
                        // and trips ERROR_RECOGNIZER_BUSY / ERROR_CLIENT on many devices.
                        scheduleRestart?.invoke(rec)
                    }
                }

                override fun onError(error: Int) {
                    // Flush whatever partial we'd accumulated into the durable buffer first
                    // so the error path can never out-race the text preservation.
                    transcript.commit("")
                    onPartial(transcript.current())

                    if (stopRequested) {
                        resolvePreservingText(error)
                        return
                    }

                    // ERROR_INSUFFICIENT_PERMISSIONS can't be fixed by restarting — bail now.
                    // Buffer text (if any) is still emitted as Final.
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        resolvePreservingText(error)
                        return
                    }

                    consecutiveErrors++
                    if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                        // We've tried hard enough. Whatever text we have is the result.
                        resolvePreservingText(error)
                        return
                    }

                    // Everything else (NO_MATCH, SPEECH_TIMEOUT, RECOGNIZER_BUSY, CLIENT,
                    // AUDIO, NETWORK, NETWORK_TIMEOUT, SERVER) gets another chance —
                    // priority is to not lose what the user already said.
                    scheduleRestart?.invoke(rec)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                rec.startListening(intent)
            } catch (_: Throwable) {
                consecutiveErrors++
                if (stopRequested) {
                    resolvePreservingText(SpeechRecognizer.ERROR_CLIENT)
                } else if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                    resolvePreservingText(SpeechRecognizer.ERROR_CLIENT)
                } else {
                    scheduleRestart?.invoke(rec)
                }
            }
        }

        scheduleRestart = { rec ->
            if (!resolved && !stopRequested && !restartScheduled) {
                restartScheduled = true
                if (rec != null) {
                    try { rec.destroy() } catch (_: Throwable) {}
                    if (recognizer === rec) recognizer = null
                }
                val delay = backoffDelayMs(consecutiveErrors)
                handler.postDelayed({
                    restartScheduled = false
                    if (!resolved && !stopRequested) startInner()
                }, delay)
            }
        }

        startInner()

        return object : Session {
            override fun stop() {
                stopRequested = true
                // Drop any pending restart — we're committed to ending the session
                // with whatever text is in the buffer.
                handler.removeCallbacksAndMessages(null)
                restartScheduled = false

                val rec = recognizer
                if (rec == null) {
                    resolvePreservingText(errorCode = null)
                    return
                }
                try {
                    rec.stopListening()
                } catch (_: Throwable) {
                    resolvePreservingText(errorCode = null)
                    return
                }
                // Safety net: if onResults/onError never fires after stopListening
                // (some OEMs swallow it), resolve with the buffer anyway.
                handler.postDelayed({
                    resolvePreservingText(errorCode = null)
                }, STOP_TIMEOUT_MS)
            }

            override fun cancel() {
                stopRequested = true
                handler.removeCallbacksAndMessages(null)
                restartScheduled = false
                try { recognizer?.cancel() } catch (_: Throwable) {}
                // True cancel — caller asked to throw the session away. Don't
                // route through resolvePreservingText; this is explicit intent.
                resolve(SpeechResult.Cancelled)
            }
        }
    }

    private fun backoffDelayMs(consecutiveErrors: Int): Long {
        // 0 errors → 75ms (initial gap so the previous recognizer can release).
        // Then double per error, capped at 1s.
        if (consecutiveErrors <= 0) return RESTART_DELAY_MS
        val shift = (consecutiveErrors - 1).coerceAtMost(4)
        return (RESTART_DELAY_MS shl shift).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    private object NoopSession : Session {
        override fun stop() {}
        override fun cancel() {}
    }

    private companion object {
        // Small gap between teardown and the next startListening so the previous
        // recognizer can release the audio session — without it, fast restarts
        // race the teardown and trip ERROR_RECOGNIZER_BUSY on many OEMs.
        const val RESTART_DELAY_MS = 75L
        const val MAX_RESTART_DELAY_MS = 1_000L

        // After this many consecutive errors with no progress, give up on the
        // restart loop. Any accumulated transcript is still emitted as Final.
        const val MAX_CONSECUTIVE_ERRORS = 8

        // Safety net for stop(): if the recognizer doesn't fire onResults/onError
        // within this window after stopListening, resolve with whatever the buffer has.
        const val STOP_TIMEOUT_MS = 2_500L
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
