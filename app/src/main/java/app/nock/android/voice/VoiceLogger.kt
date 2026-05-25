package app.nock.android.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only log of every interaction with the platform SpeechRecognizer
 * and the voice-alarm state machine — what we called, what arguments we
 * passed, what callbacks fired, what values came back. Exposed in Settings
 * as a single copyable blob so a user-reproduced problem can be shipped off
 * verbatim for analysis.
 *
 * Persists to internal storage so the log survives process restarts.
 * Bounded at ~1 MB; when the cap is hit the oldest ~250 KB is dropped in
 * one chunk so the trim cost is amortised.
 *
 * Thread-safe.
 */
@Singleton
class VoiceLogger @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val file = File(ctx.filesDir, FILE_NAME)
    private val lock = Any()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        synchronized(lock) {
            if (!file.exists()) file.createNewFile()
            val sessionStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("\n--- app session started $sessionStamp ---\n")
            trimIfNeeded()
        }
    }

    fun log(tag: String, message: String) {
        val line = "[${tsFmt.format(Date())}] [$tag] $message\n"
        synchronized(lock) {
            file.appendText(line)
            trimIfNeeded()
        }
    }

    fun dump(): String = synchronized(lock) {
        if (file.exists()) file.readText() else ""
    }

    fun clear() {
        synchronized(lock) {
            file.writeText("")
        }
    }

    private fun trimIfNeeded() {
        val len = file.length()
        if (len <= MAX_BYTES) return
        val text = file.readText()
        val dropFrom = TRIM_BYTES.coerceAtMost(text.length)
        val tail = text.substring(dropFrom)
        // Snap to the next newline so we never leave a half-line at the start.
        val nl = tail.indexOf('\n')
        val kept = if (nl >= 0) tail.substring(nl + 1) else tail
        file.writeText("--- log trimmed ---\n$kept")
    }

    private companion object {
        const val FILE_NAME = "voice_log.txt"
        const val MAX_BYTES = 1_000_000L
        const val TRIM_BYTES = 250_000
    }
}
