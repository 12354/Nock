package app.nock.android.notif

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import app.nock.android.R
import app.nock.android.alarm.AlarmActivity
import app.nock.android.alarm.AlarmService
import app.nock.android.alarm.IntentExtras
import app.nock.android.alarm.NotificationActionReceiver
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPresenter @Inject constructor(
    @ApplicationContext private val ctx: Context
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

    fun showNormal(reminder: Reminder, group: Group, escalationId: Long) {
        val notif = baseBuilder(reminder, group, escalationId, Channels.NORMAL)
            .setContentText(group.name)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
        nm.notify(escalationId.toInt(), notif)
    }

    fun showAlarm(reminder: Reminder, group: Group, escalationId: Long) {
        val fullScreen = Intent(ctx, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminder.id)
        }
        val fullScreenPI = PendingIntent.getActivity(
            ctx,
            escalationId.toInt(),
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = baseBuilder(reminder, group, escalationId, Channels.ALARM)
            .setContentText("${group.name} — alarm")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPI, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(escalationId.toInt(), notif)

        val svc = Intent(ctx, AlarmService::class.java).apply {
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_REMINDER_ID, reminder.id)
        }
        androidx.core.content.ContextCompat.startForegroundService(ctx, svc)
        ctx.startActivity(fullScreen)
    }

    fun cancel(escalationId: Long) {
        nm.cancel(escalationId.toInt())
    }

    fun cancelStageVisuals(escalationId: Long) {
        nm.cancel(escalationId.toInt())
    }

    fun stopAlarm() {
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
        val tap = Intent(ctx, app.nock.android.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val tapPI = PendingIntent.getActivity(
            ctx, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.name)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(tapPI)
            .addAction(0, ctx.getString(R.string.done), donePI)
            .addAction(0, ctx.getString(R.string.snooze), snoozePI)
    }
}
