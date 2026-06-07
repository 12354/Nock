package app.nock.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import app.nock.android.di.ApplicationScope
import app.nock.android.trip.TripPendingIntents
import app.nock.android.trip.TripSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes for the two trip alarms scheduled by [app.nock.android.trip.TripScheduler]:
 * a per-trip travel-time recompute, and the daily catch-up sync. Neither rings —
 * they refresh estimates and re-arm the escalation alarms.
 */
@AndroidEntryPoint
class TripReceiver : BroadcastReceiver() {

    @Inject lateinit var syncManager: TripSyncManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val reminderId = intent.getLongExtra(TripPendingIntents.EXTRA_REMINDER_ID, -1L)

        val pm = context.getSystemService<PowerManager>()
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nock:trip-recompute")
            ?.apply { setReferenceCounted(false); acquire(30_000L) }

        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    TripPendingIntents.ACTION_RECOMPUTE -> if (reminderId >= 0) syncManager.recompute(reminderId)
                    TripPendingIntents.ACTION_SYNC -> syncManager.syncNow()
                }
            } catch (_: Throwable) {
                // Best-effort: a failed recompute leaves the last estimate armed.
            } finally {
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
                pending.finish()
            }
        }
    }
}
