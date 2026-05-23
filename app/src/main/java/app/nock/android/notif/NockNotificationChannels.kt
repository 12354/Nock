package app.nock.android.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.getSystemService
import app.nock.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val SILENT = "nock_silent"
    const val NORMAL = "nock_normal"
    const val ALARM = "nock_alarm"
    const val SERVICE = "nock_service"
}

@Singleton
class NockNotificationChannels @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    fun ensureCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.SILENT,
                ctx.getString(R.string.channel_silent_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.channel_silent_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.NORMAL,
                ctx.getString(R.string.channel_normal_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = ctx.getString(R.string.channel_normal_desc)
                val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(sound, attrs)
                enableVibration(true)
            }
        )

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.ALARM,
                ctx.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_alarm_desc)
                setSound(alarmSound, alarmAttrs)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.SERVICE,
                ctx.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.channel_service_desc)
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }
}
