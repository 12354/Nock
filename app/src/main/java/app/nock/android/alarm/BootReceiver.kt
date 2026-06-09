package app.nock.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.trip.TripSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: EscalationEngine
    @Inject lateinit var trips: TripSyncManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED
            )
        ) return

        // A timezone or manual clock change moves the wall-clock meaning of every
        // stored fire time; tell the engine to re-anchor time-of-day schedules. A
        // plain boot / package replace keeps the stored absolute times.
        val recomputeWallClock =
            action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED

        val pending = goAsync()
        scope.launch {
            try {
                engine.rescheduleAll(recomputeWallClock = recomputeWallClock)
                // Re-import upcoming events and re-arm the daily sync + recompute
                // alarms (AlarmManager drops everything on reboot).
                runCatching { trips.syncNow() }
            } finally { pending.finish() }
        }
    }
}
