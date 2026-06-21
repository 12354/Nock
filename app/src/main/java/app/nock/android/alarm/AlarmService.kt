package app.nock.android.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import app.nock.android.R
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.notif.NotificationPresenter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmService : Service() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var activeDao: ActiveEscalationDao
    @Inject lateinit var notifier: NotificationPresenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
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
        val reminderName = intent?.getStringExtra(IntentExtras.EXTRA_REMINDER_NAME)
            ?: getString(R.string.alarm_title)
        val groupName = intent?.getStringExtra(IntentExtras.EXTRA_GROUP_NAME).orEmpty()
        val vibrationOnly = intent?.getBooleanExtra(IntentExtras.EXTRA_VIBRATION_ONLY, false) ?: false

        // Promote to foreground synchronously with the alarm notification itself
        // so it's bound to the running service — that's what makes it ongoing
        // and effectively undismissable while the alarm is ringing. The
        // mediaPlayback FGS type is what lets us start this foreground service
        // from the background when the alarm fires.
        val notification = notifier.buildAlarmNotification(
            escalationId = escalationId,
            reminderId = reminderId,
            reminderName = reminderName,
            groupName = groupName
        )
        val notificationId = if (escalationId >= 0L) escalationId.toInt() else FALLBACK_NOTIFICATION_ID
        ServiceCompat.startForeground(
            this,
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        setRinging(
            escalationId = if (escalationId >= 0L) escalationId else null,
            reminderId = if (reminderId >= 0L) reminderId else null,
        )

        // Launch the full-screen takeover eagerly — synchronously here, before
        // the suspend guard below touches the database. The background-activity-
        // launch grant we receive when the setAlarmClock alarm is delivered is
        // short-lived; deferring startActivity() behind a Room round-trip can
        // push it past that window on a busy device, leaving the loud stage
        // ringing with only a heads-up notification and no takeover while the
        // phone is unlocked and in use (when locked, the notification's
        // full-screen intent still covers it). AlarmActivity re-checks the
        // escalation in bindFromIntent and finishes itself if it was already
        // Done/Snoozed, so launching ahead of the guard is safe for a stale
        // start.
        if (escalationId >= 0L) launchAlarmActivity(escalationId, reminderId)

        scope.launch {
            // Guard against a stale start: Done/Snooze may have already removed
            // the escalation by the time the system delivers our start command,
            // and we don't want to ring then. The full-screen activity launched
            // above self-cancels in that same situation.
            if (escalationId < 0L || activeDao.getById(escalationId) == null) {
                stopAndDie()
                return@launch
            }
            // A repeat re-fire (the loud stage re-arms itself every interval) or a
            // stage change (ALARM_VIBRATE → ALARM) delivers a fresh onStartCommand
            // to this same already-running service. Tear down the sound/vibration/
            // wakelocks from the previous start BEFORE re-acquiring: otherwise
            // startSound() would overwrite `player` with a second looping
            // MediaPlayer while the first keeps playing untracked, and stopAndDie()
            // (Done/Snooze) would only stop the latest one — leaving the orphan
            // ringing on after the reminder was dismissed.
            releasePlayback()
            acquireWakeLock()
            if (vibrationOnly) startVibration() else startSound()
        }
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

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        // Wait 0ms, vibrate 800ms, pause 500ms — repeat from index 0 so the
        // pattern loops until stopAndDie() cancels it.
        val timings = longArrayOf(0L, 800L, 500L)
        val amplitudes = intArrayOf(0, 255, 0)
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amplitudes, 0)
            } else {
                VibrationEffect.createWaveform(timings, 0)
            }
            v.vibrate(effect, attrs)
        } catch (_: Throwable) {
            vibrator = null
        }
    }

    // Stop and release sound, vibration and wakelocks without tearing down the
    // foreground service. Safe to call repeatedly (idempotent) — used both when a
    // re-fire restarts playback and when the alarm is dismissed.
    private fun releasePlayback() {
        try { player?.stop() } catch (_: Throwable) {}
        player?.release()
        player = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
    }

    private fun stopAndDie() {
        clearRingingState()
        releasePlayback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        clearRingingState()
        releasePlayback()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FALLBACK_NOTIFICATION_ID = 0xA1A2

        // Tracks the currently-ringing alarm so MainActivity can re-launch
        // the full-screen view if the user navigated away from it (e.g. via
        // Home) while the alarm is still ringing.
        @Volatile var ringingEscalationId: Long? = null
            private set
        @Volatile var ringingReminderId: Long? = null
            private set

        // Observable mirror of [ringingEscalationId]. AlarmActivity collects this
        // so the full-screen takeover can finish itself the instant its alarm
        // stops ringing — snoozed/done/cancelled from the notification, the Today
        // screen, a sync, or a room detection. Without it the silent, already-
        // dismissed activity lingers in its own task and resurfaces on the next
        // app foreground (the "ghost alarm"). Kept in lock-step with the
        // [ringingEscalationId] field above by routing every write through
        // [setRinging]/[clearRingingState].
        private val _ringingEscalation = MutableStateFlow<Long?>(null)
        val ringingEscalation: StateFlow<Long?> = _ringingEscalation.asStateFlow()

        private fun setRinging(escalationId: Long?, reminderId: Long?) {
            ringingEscalationId = escalationId
            ringingReminderId = reminderId
            _ringingEscalation.value = escalationId
        }

        /**
         * Mark an alarm as ringing without the foreground service — used by the
         * FGS-start-denied fallback in [NotificationPresenter], where the loud
         * stage surfaces only as a full-screen-intent notification. Setting the
         * ringing state keeps that fallback's takeover from being treated as a
         * ghost (and self-finishing) when the activity launches; it's cleared by
         * the usual Done/Snooze teardown via [clearRingingState].
         */
        fun markRinging(escalationId: Long, reminderId: Long?) {
            if (escalationId < 0L) return
            setRinging(escalationId, if ((reminderId ?: -1L) >= 0L) reminderId else null)
        }

        // Called when the engine decides the alarm should stop (Done/Snooze).
        // Must clear synchronously so MainActivity.onResume — which can fire
        // immediately after the alarm activity finishes — doesn't see stale
        // ringing state and bounce the user back into the alarm screen
        // before Service.onDestroy gets a chance to run.
        fun clearRingingState() {
            setRinging(null, null)
        }
    }
}
