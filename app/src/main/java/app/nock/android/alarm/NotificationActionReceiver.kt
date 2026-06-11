package app.nock.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.notif.NotificationPresenter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: EscalationEngine
    @Inject lateinit var presenter: NotificationPresenter
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(IntentExtras.EXTRA_ACTION) ?: return

        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    IntentExtras.ACTION_COMPLETE_REMINDER -> {
                        // The room-window Done button: complete the reminder by id
                        // (it may have no live escalation yet) and clear its note.
                        val reminderId = intent.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L)
                        if (reminderId >= 0) {
                            engine.completeReminder(reminderId)
                            presenter.cancelRoomWindow(reminderId)
                        }
                    }
                    else -> {
                        val escalationId = intent.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L)
                        if (escalationId < 0) return@launch
                        when (action) {
                            IntentExtras.ACTION_DONE -> engine.done(escalationId)
                            IntentExtras.ACTION_SNOOZE -> engine.snooze(escalationId)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
