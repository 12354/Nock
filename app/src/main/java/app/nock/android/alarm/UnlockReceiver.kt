package app.nock.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

// Listens for ACTION_USER_PRESENT (sent when the user unlocks the keyguard)
// and triggers any reminders armed with Schedule.OnUnlock. ACTION_USER_PRESENT
// is not in the implicit-broadcast exemption list, so this receiver is
// registered at runtime in NockApplication rather than via the manifest.
@AndroidEntryPoint
class UnlockReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: EscalationEngine
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val pending = goAsync()
        scope.launch {
            try { engine.fireUnlockReminders() } finally { pending.finish() }
        }
    }
}
