package app.nock.android.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
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

    // The pre-alarm sound is user-configurable, but a channel's sound is frozen
    // at creation on Android O+. We therefore mint one channel per chosen sound,
    // keying the id off the sound URI so a new choice yields a brand-new channel
    // (recreating under the same id would silently keep the old sound).
    const val PREALARM_PREFIX = "nock_prealarm_"

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

    /**
     * Ensures a heads-up "pre-alarm" channel exists for [soundUri] (null = a
     * silent channel) and returns its id. Old pre-alarm channels are pruned so a
     * changed sound doesn't leave orphans piling up in the system settings.
     * Safe to call on every post; it's a no-op once the channel exists.
     */
    fun ensurePreAlarmChannel(soundUri: Uri?): String {
        val id = Channels.PREALARM_PREFIX + Integer.toHexString((soundUri?.toString() ?: "silent").hashCode())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val nm = ctx.getSystemService<NotificationManager>() ?: return id

        nm.notificationChannels
            .filter { it.id.startsWith(Channels.PREALARM_PREFIX) && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }

        if (nm.getNotificationChannel(id) == null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            nm.createNotificationChannel(
                NotificationChannel(
                    id,
                    ctx.getString(R.string.channel_prealarm_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ctx.getString(R.string.channel_prealarm_desc)
                    setSound(soundUri, if (soundUri != null) attrs else null)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        return id
    }
}
