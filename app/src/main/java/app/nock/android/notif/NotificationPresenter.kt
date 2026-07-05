package app.nock.android.notif

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import app.nock.android.R
import app.nock.android.alarm.AlarmActivity
import app.nock.android.alarm.AlarmService
import app.nock.android.alarm.IntentExtras
import app.nock.android.alarm.NotificationActionReceiver
import app.nock.android.data.SettingsRepository
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.VibrationPattern
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPresenter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val channels: NockNotificationChannels,
    private val vibrationPlayer: VibrationPlayer,
) {
    private val nm: NotificationManager = ctx.getSystemService()!!

    fun showSilent(reminder: Reminder, group: Group, escalationId: Long, suffix: String = "") {
        val notif = baseBuilder(reminder, group, escalationId, Channels.SILENT)
            .setContentText("${group.name}$suffix")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        nm.notify(escalationId.toInt(), notif)
    }

    /**
     * The silent local notification that accompanies a TELEGRAM stage: a quiet
     * heads-up on the phone that a Telegram message was just sent, without making
     * any sound or vibration. Same silent channel as [showSilent]; the suffix
     * makes the reason explicit.
     */
    fun showTelegram(reminder: Reminder, group: Group, escalationId: Long) =
        showSilent(reminder, group, escalationId, suffix = " (Telegram sent)")

    /**
     * A normal heads-up notification that plays the user-chosen pre-alarm sound.
     * Used by the NOTIFICATION stage so there's an audible nudge on the phone. The
     * sound is resolved from settings: absent = default notification tone, blank =
     * silent, else a specific ringtone/notification URI the user picked.
     */
    suspend fun showPreAlarm(reminder: Reminder, group: Group, escalationId: Long, suffix: String = "") {
        val stored = settings.get(SettingsRepository.KEY_PREALARM_SOUND)
        val soundUri: Uri? = when {
            stored == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            stored.isBlank() -> null
            else -> Uri.parse(stored)
        }
        val channelId = channels.ensurePreAlarmChannel(soundUri)
        val notif = baseBuilder(reminder, group, escalationId, channelId)
            .setContentText("${group.name}$suffix")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                // On pre-O there are no channels, so the sound rides on the
                // notification itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && soundUri != null) setSound(soundUri)
            }
            .build()
        nm.notify(escalationId.toInt(), notif)
    }

    fun showVibrate(reminder: Reminder, group: Group, escalationId: Long, suffix: String = "") {
        val notif = baseBuilder(reminder, group, escalationId, Channels.VIBRATE)
            .setContentText("${group.name}$suffix")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()
        nm.notify(escalationId.toInt(), notif)
    }

    /**
     * A "regular" reminder: a single gentle nudge with a per-reminder short/long
     * vibration pattern and NO escalation. Unlike every escalation stage this posts
     * an ordinary, dismissable notification with no Done/Snooze actions — the
     * reminder auto-completes when it fires, so there is nothing to acknowledge —
     * and plays [pattern] once on the vibrator.
     */
    fun showRegular(reminder: Reminder, group: Group, escalationId: Long, pattern: VibrationPattern) {
        val notif = NotificationCompat.Builder(ctx, Channels.REGULAR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.name)
            .setContentText(group.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(escalationId.toInt(), notif)
        vibrationPlayer.play(pattern)
    }

    fun showAlarm(reminder: Reminder, group: Group, escalationId: Long) {
        // The alarm notification is posted by AlarmService.startForeground() so
        // it's bound to the running foreground service — that keeps it ongoing
        // and effectively undismissable while the alarm is ringing. The service
        // also re-launches the full-screen activity for the same alarm.
        startAlarmService(reminder, group, escalationId, vibrationOnly = false)
    }

    fun showAlarmVibrate(reminder: Reminder, group: Group, escalationId: Long) {
        startAlarmService(reminder, group, escalationId, vibrationOnly = true)
    }

    private fun startAlarmService(
        reminder: Reminder,
        group: Group,
        escalationId: Long,
        vibrationOnly: Boolean,
    ) {
        val svc = Intent(ctx, AlarmService::class.java).apply {
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminder.id)
            putExtra(IntentExtras.EXTRA_REMINDER_NAME, reminder.name)
            putExtra(IntentExtras.EXTRA_GROUP_NAME, group.name)
            putExtra(IntentExtras.EXTRA_VIBRATION_ONLY, vibrationOnly)
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(ctx, svc)
        } catch (_: Throwable) {
            // The background FGS-start grant from the exact alarm can be denied
            // on API 31+ (ForegroundServiceStartNotAllowedException) when the
            // delivery is delayed past its grace window. This runs inside the
            // EscalationReceiver's app-scope coroutine, which has no exception
            // handler — an uncaught throw here would crash the app at the exact
            // moment the alarm should ring. Fall back to posting the alarm
            // notification directly so its full-screen intent, heads-up and
            // Done/Snooze actions still surface. Sound and vibration need the
            // service, so they're lost in this fallback, but the reminder is
            // still shown and acknowledgeable rather than silently dropped.
            nm.notify(
                escalationId.toInt(),
                buildAlarmNotification(escalationId, reminder.id, reminder.name, group.name)
            )
        }
    }

    fun buildAlarmNotification(
        escalationId: Long,
        reminderId: Long,
        reminderName: String,
        groupName: String
    ): Notification {
        val fullScreen = Intent(ctx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminderId)
        }
        val fullScreenPI = PendingIntent.getActivity(
            ctx,
            escalationId.toInt(),
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return alarmActionsBuilder(escalationId, reminderName)
            .setContentText(ctx.getString(R.string.notification_alarm_suffix, groupName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPI, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The quiet, ever-present note shown while a room reminder's window is open,
     * so the user can mark it done by hand even when WiFi never produces a
     * confident match. It carries a single Done button that completes the
     * reminder by id (no live escalation needed). Posted under a dedicated tag so
     * it shares no id space with the escalation notifications keyed by
     * escalationId, and re-posting it on each ~15-min room check merely refreshes
     * it. [timeoutAtMs] is the window's fallback deadline: the system auto-removes
     * the note then, exactly when the loud fallback escalation takes over.
     */
    fun showRoomWindow(reminderId: Long, reminderName: String, roomName: String?, timeoutAtMs: Long) {
        val text = roomName?.let { ctx.getString(R.string.room_window_in_room, it) }
            ?: ctx.getString(R.string.room_window_generic)
        val doneIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            // A distinct action keeps this PendingIntent from colliding (via
            // filterEquals, which ignores extras) with the escalation Done/Snooze
            // intents that target the same receiver.
            action = "${ctx.packageName}.${IntentExtras.ACTION_COMPLETE_REMINDER}"
            putExtra(IntentExtras.EXTRA_ACTION, IntentExtras.ACTION_COMPLETE_REMINDER)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminderId)
        }
        val donePI = PendingIntent.getBroadcast(
            ctx,
            reminderId.toInt(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val timeout = timeoutAtMs - System.currentTimeMillis()
        if (timeout <= 0L) return // window already closed; nothing to show
        val notif = NotificationCompat.Builder(ctx, Channels.ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminderName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setTimeoutAfter(timeout)
            .addAction(0, ctx.getString(R.string.done), donePI)
            .build()
        nm.notify(ROOM_WINDOW_TAG, reminderId.toInt(), notif)
    }

    fun cancelRoomWindow(reminderId: Long) {
        nm.cancel(ROOM_WINDOW_TAG, reminderId.toInt())
    }

    fun cancel(escalationId: Long) {
        nm.cancel(escalationId.toInt())
    }

    fun cancelStageVisuals(escalationId: Long) {
        nm.cancel(escalationId.toInt())
    }

    fun stopAlarm() {
        AlarmService.clearRingingState()
        val stop = Intent(ctx, AlarmService::class.java).apply {
            action = IntentExtras.ACTION_STOP_ALARM
        }
        ctx.stopService(stop)
    }

    private fun baseBuilder(
        reminder: Reminder,
        group: Group,
        escalationId: Long,
        channelId: String
    ): NotificationCompat.Builder =
        alarmActionsBuilder(escalationId, reminder.name, channelId)

    private fun alarmActionsBuilder(
        escalationId: Long,
        reminderName: String,
        channelId: String = Channels.ALARM
    ): NotificationCompat.Builder {
        val doneIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            putExtra(IntentExtras.EXTRA_ACTION, IntentExtras.ACTION_DONE)
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
        }
        val donePI = PendingIntent.getBroadcast(
            ctx,
            (escalationId * 2).toInt(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            putExtra(IntentExtras.EXTRA_ACTION, IntentExtras.ACTION_SNOOZE)
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
        }
        val snoozePI = PendingIntent.getBroadcast(
            ctx,
            (escalationId * 2 + 1).toInt(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // No content intent: tapping the notification body does nothing, so the
        // body can't be mis-tapped into an action. The only way to interact is
        // the explicit Done/Snooze buttons (and, for the loud stage, the
        // full-screen takeover). setOngoing(true) makes it non-dismissable —
        // the user can't swipe it away (per spec, swiping must not count as
        // Done); it's cleared only when Done/Snooze cancels it.
        return NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminderName)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .addAction(0, ctx.getString(R.string.done), donePI)
            .addAction(0, ctx.getString(R.string.snooze), snoozePI)
    }

    private companion object {
        // Tag for room-window notifications so their per-reminder ids don't
        // collide with the escalation notifications posted by escalationId.toInt().
        const val ROOM_WINDOW_TAG = "room_window"
    }
}
