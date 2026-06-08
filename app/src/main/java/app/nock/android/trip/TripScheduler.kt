package app.nock.android.trip

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import app.nock.android.alarm.TripReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the two trip-specific AlarmManager alarms: per-trip travel-time
 * recomputes and a daily catch-up sync. These are separate from the escalation
 * alarms — they don't ring; they wake [TripReceiver] to refresh estimates.
 *
 * Uses setExactAndAllowWhileIdle (not setAlarmClock): recompute timing only
 * needs to be roughly on-point, and these must not show the system's alarm icon.
 */
@Singleton
class TripScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val am: AlarmManager = ctx.getSystemService()!!

    fun scheduleRecompute(reminderId: Long, atMs: Long) {
        setIdle(atMs, TripPendingIntents.recompute(ctx, reminderId))
    }

    fun cancelRecompute(reminderId: Long) {
        am.cancel(TripPendingIntents.recompute(ctx, reminderId))
    }

    fun scheduleDailySync(atMs: Long) {
        setIdle(atMs, TripPendingIntents.sync(ctx))
    }

    fun cancelDailySync() {
        am.cancel(TripPendingIntents.sync(ctx))
    }

    private fun setIdle(atMs: Long, pi: android.app.PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}

object TripPendingIntents {
    const val ACTION_RECOMPUTE = "app.nock.android.TRIP_RECOMPUTE"
    const val ACTION_SYNC = "app.nock.android.TRIP_SYNC"
    const val EXTRA_REMINDER_ID = "trip_reminder_id"

    // Request code reserved for the daily-sync alarm. Per-trip recompute alarms
    // use reminderId.toInt(); the distinct action keeps them from matching this
    // even on a collision, and reminder ids start at 1.
    private const val SYNC_REQUEST_CODE = 0

    fun recompute(ctx: Context, reminderId: Long): android.app.PendingIntent {
        val intent = Intent(ctx, TripReceiver::class.java).apply {
            action = ACTION_RECOMPUTE
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return android.app.PendingIntent.getBroadcast(
            ctx, reminderId.toInt(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun sync(ctx: Context): android.app.PendingIntent {
        val intent = Intent(ctx, TripReceiver::class.java).apply { action = ACTION_SYNC }
        return android.app.PendingIntent.getBroadcast(
            ctx, SYNC_REQUEST_CODE, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
