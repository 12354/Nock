package app.nock.android.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import app.nock.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val SILENT = "nock_silent_v2"
    const val VIBRATE = "nock_vibrate_v1"
    const val ALARM = "nock_alarm_v2"
    const val SERVICE = "nock_service"

    val LEGACY_IDS = listOf("nock_silent", "nock_normal", "nock_alarm", "nock_normal_v2")
}

@Singleton
class NockNotificationChannels @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    fun ensureCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService<NotificationManager>() ?: return

        Channels.LEGACY_IDS.forEach { nm.deleteNotificationChannel(it) }

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.SILENT,
                ctx.getString(R.string.channel_silent_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_silent_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.VIBRATE,
                ctx.getString(R.string.channel_vibrate_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_vibrate_desc)
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                Channels.ALARM,
                ctx.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_alarm_desc)
                setSound(null, null)
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
