package app.nock.android.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import app.nock.android.R
import app.nock.android.data.SettingsRepository
import app.nock.android.notif.Channels
import app.nock.android.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmService : Service() {

    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentExtras.ACTION_STOP_ALARM) {
            stopAndDie()
            return START_NOT_STICKY
        }
        val escalationId = intent?.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L) ?: -1L
        val reminderId = intent?.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L) ?: -1L
        // Promote to foreground synchronously so the activity launch below counts
        // as foreground-initiated (mediaPlayback FGS is exempt from BAL).
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification(escalationId))
        acquireWakeLock()
        if (escalationId >= 0L) {
            launchAlarmActivity(escalationId, reminderId)
        }
        scope.launch { startSound() }
        // REDELIVER_INTENT so a killed alarm restarts with its original IDs.
        return START_REDELIVER_INTENT
    }

    private fun launchAlarmActivity(escalationId: Long, reminderId: Long) {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminderId)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            // Background-activity-launch denial: the notification's full-screen
            // intent is the lock-screen fallback and will still trigger.
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService<PowerManager>()!!
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "nock:alarm-service"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60_000L)
        }
        // Short SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP nudges devices whose
        // setTurnScreenOn() path is unreliable (some Samsung/Xiaomi builds).
        // These flags are deprecated but still honored.
        @Suppress("DEPRECATION")
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "nock:alarm-screen"
        ).apply {
            setReferenceCounted(false)
            acquire(5_000L)
        }
    }

    private suspend fun startSound() {
        val customUri = settings.get(SettingsRepository.KEY_ALARM_SOUND)
        val uri: Uri = customUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (_: Throwable) {
            player = null
        }
    }

    private fun buildServiceNotification(escalationId: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.channel_service_desc))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPI)
            .build()
    }

    private fun stopAndDie() {
        try { player?.stop() } catch (_: Throwable) {}
        player?.release()
        player = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        try { player?.stop() } catch (_: Throwable) {}
        player?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 0xA1A2
    }
}
