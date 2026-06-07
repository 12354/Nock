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

        val pending = goAsync()
        scope.launch {
            try {
                engine.rescheduleAll()
                // Re-import upcoming events and re-arm the daily sync + recompute
                // alarms (AlarmManager drops everything on reboot).
                runCatching { trips.syncNow() }
            } finally { pending.finish() }
        }
    }
}
