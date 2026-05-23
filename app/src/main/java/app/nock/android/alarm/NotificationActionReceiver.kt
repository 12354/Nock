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

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: EscalationEngine
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(IntentExtras.EXTRA_ACTION) ?: return
        val escalationId = intent.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L)
        if (escalationId < 0) return

        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    IntentExtras.ACTION_DONE -> engine.done(escalationId)
                    IntentExtras.ACTION_SNOOZE -> engine.snooze(escalationId)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
