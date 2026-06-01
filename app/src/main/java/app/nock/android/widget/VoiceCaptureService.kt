package app.nock.android.widget

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.nock.android.R
import app.nock.android.notif.Channels
import app.nock.android.voice.SpeechResult
import app.nock.android.voice.SpeechToTextManager
import app.nock.android.voice.VoiceAlarmCreator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background voice-capture driven by the home-screen widget.
 *
 * Lifecycle:
 *  * ACTION_TOGGLE while [isRecording] is false → start a recognition session.
 *  * ACTION_TOGGLE while [isRecording] is true  → stop and finalize.
 *  * ACTION_STOP   → same as the second toggle (used by the notification action).
 *
 * The recording loop mirrors [app.nock.android.ui.voice.VoiceAlarmViewModel]
 * exactly so the widget gets the same silence-tolerance behaviour as the
 * in-app HoldToRecordButton: when the platform recognizer auto-ends on
 * mid-utterance silence, we restart a fresh session after a short delay and
 * accumulate text across sessions. The only way to actually finish recording
 * is a tap on the widget (or the notification's Stop action).
 *
 * The widget never opens the app. After recording stops we keep the microphone
 * foreground notification alive while DeepSeek parses the transcript — so
 * Android keeps our process around and the work can't be killed mid-flight —
 * then surface the outcome as a toast and stop.
 */
@AndroidEntryPoint
class VoiceCaptureService : Service() {

    @Inject lateinit var stt: SpeechToTextManager
    @Inject lateinit var creator: VoiceAlarmCreator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: SpeechToTextManager.Session? = null
    private var isRecording = false
    private var accumulated: String = ""
    private var sessionPartial: String = ""
    // Consecutive recognizer errors with no speech progress in between. Reset on
    // any committed text; used to break out of an otherwise-infinite restart
    // loop on a persistent hard error (e.g. mic permission denied).
    private var consecutiveErrors = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_TOGGLE -> if (isRecording) stopAndFinish() else startRecording()
            ACTION_STOP -> stopAndFinish()
            else -> {
                // Bare start (or a redelivered intent without our action) —
                // treat as a fresh recording request if we're not already going.
                if (!isRecording) startRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        accumulated = ""
        sessionPartial = ""
        consecutiveErrors = 0
        scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Starting) }
        startSession()
    }

    private fun startSession() {
        if (session != null) return
        sessionPartial = ""
        session = stt.start(
            onReady = {
                if (isRecording) {
                    scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Recording) }
                }
            },
            onPartial = { partial ->
                sessionPartial = partial
            },
            onSegmentComplete = { segment ->
                commitSegment(segment)
                sessionPartial = ""
            },
            onResult = { result ->
                session = null
                when (result) {
                    is SpeechResult.Final -> commitSegment(result.text)
                    is SpeechResult.Cancelled -> { /* keep accumulator */ }
                    is SpeechResult.Error -> {
                        consecutiveErrors++
                        // Unrecoverable when we're not recording and have nothing
                        // to keep — finalize empty, which just resets to Idle.
                        if (!isRecording && accumulated.isBlank()) {
                            finalize()
                            return@start
                        }
                    }
                }
                sessionPartial = ""

                // A persistent hard error (mic permission revoked, recognizer
                // client error, etc.) makes each restart fail again immediately.
                // Cap the retries so the foreground mic service can't respin the
                // recognizer forever; finalize with whatever we already captured.
                if (isRecording && consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    isRecording = false
                    finalize()
                    return@start
                }

                if (isRecording) {
                    scope.launch {
                        delay(RESTART_DELAY_MS)
                        if (isRecording && session == null) {
                            startSession()
                        }
                    }
                } else {
                    finalize()
                }
            }
        )
    }

    private fun stopAndFinish() {
        if (!isRecording) {
            // Already idle; nothing in flight — just tidy up.
            finalize()
            return
        }
        isRecording = false
        scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Stopping) }
        val s = session
        if (s != null) {
            s.stop() // result comes back via onResult → finalize()
        } else {
            finalize()
        }
    }

    private fun commitSegment(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // Real speech captured — the recognizer is healthy, so clear the
        // hard-error streak that guards the restart loop.
        consecutiveErrors = 0
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

    private fun finalize() {
        val text = displayedText().trim()
        accumulated = ""
        sessionPartial = ""
        scope.launch {
            if (text.isNotEmpty()) {
                // Stay foregrounded with a "saving" notification so the process
                // survives while DeepSeek parses, and run the work in our own
                // scope (awaited, not fire-and-forget) so it can't be killed the
                // instant we'd otherwise stop. Then toast the real outcome — the
                // widget deliberately never opens the app.
                updateNotification(getString(R.string.widget_voice_notification_processing))
                val message = try {
                    creator.createAndAwait(text)
                } catch (t: Throwable) {
                    getString(R.string.voice_error_save_failed)
                }
                showToast(message)
            }
            VoiceWidgetState.write(applicationContext, VoiceWidgetState.Idle)
            stopForegroundAndSelf()
        }
    }

    private fun showToast(message: String) {
        if (message.isBlank()) return
        // Runs on the service's main thread (scope is Dispatchers.Main.immediate);
        // a text toast is handed to the system, so it still shows after we stop.
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Pushes an updated foreground notification (e.g. "saving…") without leaving foreground. */
    private fun updateNotification(title: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(title = title, showStop = false))
    }

    private fun buildNotification(
        title: String = getString(R.string.widget_voice_notification_title),
        showStop: Boolean = true,
    ): Notification {
        val builder = NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(title)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (showStop) {
            val stopIntent = Intent(this, VoiceCaptureService::class.java).setAction(ACTION_STOP)
            val stopPI = PendingIntent.getService(
                this,
                REQ_STOP,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.widget_voice_notification_stop_action), stopPI)
        }
        return builder.build()
    }

    override fun onDestroy() {
        try { session?.cancel() } catch (_: Throwable) {}
        session = null
        // Best-effort reset; if write() can't finish before scope.cancel(), the
        // receiver's toggle path still recovers because the service is gone.
        scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Idle) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "app.nock.android.widget.action.TOGGLE"
        const val ACTION_STOP = "app.nock.android.widget.action.STOP"
        private const val NOTIFICATION_ID = 0xC1C2
        private const val REQ_STOP = 1
        private const val RESTART_DELAY_MS = 75L
        private const val MAX_CONSECUTIVE_ERRORS = 4
    }
}
