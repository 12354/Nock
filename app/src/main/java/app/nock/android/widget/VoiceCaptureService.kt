package app.nock.android.widget

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.nock.android.R
import app.nock.android.notif.Channels
import app.nock.android.ui.MainActivity
import app.nock.android.voice.SpeechResult
import app.nock.android.voice.SpeechToTextManager
import app.nock.android.voice.VoiceAlarmCreator
import app.nock.android.voice.VoiceLogger
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
 *  * ACTION_TOGGLE while [isRecording] is true  → stop, finalize, open MainActivity.
 *  * ACTION_STOP   → same as the second toggle (used by the notification action).
 *
 * The recording loop mirrors [app.nock.android.ui.voice.VoiceAlarmViewModel]
 * exactly so the widget gets the same silence-tolerance behaviour as the
 * in-app HoldToRecordButton: when the platform recognizer auto-ends on
 * mid-utterance silence, we restart a fresh session after a short delay and
 * accumulate text across sessions. The only way to actually finish recording
 * is a tap on the widget (or the notification's Stop action).
 */
@AndroidEntryPoint
class VoiceCaptureService : Service() {

    @Inject lateinit var stt: SpeechToTextManager
    @Inject lateinit var creator: VoiceAlarmCreator
    @Inject lateinit var logger: VoiceLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: SpeechToTextManager.Session? = null
    private var isRecording = false
    private var accumulated: String = ""
    private var sessionPartial: String = ""

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
        logger.log(TAG, "startRecording() called (isRecording=$isRecording)")
        if (isRecording) return
        isRecording = true
        accumulated = ""
        sessionPartial = ""
        scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Starting) }
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
                logger.log(TAG, "onPartial \"$partial\"")
                if (isRecording) {
                    scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Recording) }
                }
            },
            onSegmentComplete = { segment ->
                val before = accumulated
                commitSegment(segment)
                sessionPartial = ""
                logger.log(TAG, "onSegmentComplete \"$segment\": \"$before\" → \"$accumulated\"")
            },
            onResult = { result ->
                session = null
                val summary = when (result) {
                    is SpeechResult.Final -> "Final(\"${result.text}\")"
                    is SpeechResult.Cancelled -> "Cancelled"
                    is SpeechResult.Error -> "Error(\"${result.message}\")"
                }
                logger.log(TAG, "onResult $summary (isRecording=$isRecording, " +
                    "accumulated=\"$accumulated\", sessionPartial=\"$sessionPartial\")")
                when (result) {
                    is SpeechResult.Final -> commitSegment(result.text)
                    is SpeechResult.Cancelled -> { /* keep accumulator */ }
                    is SpeechResult.Error -> {
                        // Unrecoverable when we're not recording and have nothing
                        // to keep — finalize empty, which just resets to Idle.
                        if (!isRecording && accumulated.isBlank()) {
                            logger.log(TAG, "  error + not recording + nothing accumulated → finalize empty")
                            finalize()
                            return@start
                        }
                        logger.log(TAG, "  error but we have text or are still recording → keep going")
                    }
                }
                sessionPartial = ""

                if (isRecording) {
                    logger.log(TAG, "  scheduling restart in ${RESTART_DELAY_MS}ms")
                    scope.launch {
                        delay(RESTART_DELAY_MS)
                        if (isRecording && session == null) {
                            logger.log(TAG, "  restart delay elapsed → startSession()")
                            startSession()
                        } else {
                            logger.log(TAG, "  restart skipped (isRecording=$isRecording, session=${session != null})")
                        }
                    }
                } else {
                    logger.log(TAG, "  not recording → finalize()")
                    finalize()
                }
            }
        )
    }

    private fun stopAndFinish() {
        logger.log(TAG, "stopAndFinish() called (isRecording=$isRecording, session=${session != null})")
        if (!isRecording) {
            // Already idle; nothing in flight — just tidy up.
            finalize()
            return
        }
        isRecording = false
        scope.launch { VoiceWidgetState.write(applicationContext, VoiceWidgetState.Stopping) }
        val s = session
        if (s != null) {
            logger.log(TAG, "stopAndFinish → session.stop()")
            s.stop() // result comes back via onResult → finalize()
        } else {
            logger.log(TAG, "stopAndFinish → no session, finalizing directly")
            finalize()
        }
    }

    private fun commitSegment(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
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

    private fun finalize() {
        val text = displayedText().trim()
        logger.log(TAG, "finalize() → \"$text\"")
        accumulated = ""
        sessionPartial = ""
        scope.launch {
            if (text.isNotEmpty()) {
                val outcome = creator.createFromTranscript(text)
                logger.log(TAG, "createFromTranscript outcome=${outcome::class.simpleName}")
            }
            // Per the agreed UX: always open the task overview on the second tap,
            // regardless of Captured / Failed / empty-transcript outcome.
            openTaskOverview()
            VoiceWidgetState.write(applicationContext, VoiceWidgetState.Idle)
            stopForegroundAndSelf()
        }
    }

    private fun openTaskOverview() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            logger.log(TAG, "startActivity(MainActivity) threw: ${t.message}")
        }
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, VoiceCaptureService::class.java).setAction(ACTION_STOP)
        val stopPI = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(getString(R.string.widget_voice_notification_title))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.widget_voice_notification_stop_action), stopPI)
            .build()
    }

    override fun onDestroy() {
        logger.log(TAG, "onDestroy() (isRecording=$isRecording, session=${session != null})")
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
        private const val TAG = "WIDGET_SVC"
        private const val RESTART_DELAY_MS = 75L
    }
}
