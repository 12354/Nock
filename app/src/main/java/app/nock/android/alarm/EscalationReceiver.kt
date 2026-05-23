package app.nock.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import android.os.PowerManager
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EscalationReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: EscalationEngine
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val escalationId = intent.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L)
        if (escalationId < 0) return

        val pm = context.getSystemService<PowerManager>()
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nock:escalation-receive")
            ?.apply { setReferenceCounted(false); acquire(30_000L) }

        val pending = goAsync()
        scope.launch {
            try {
                engine.onAlarmFired(escalationId)
            } finally {
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
                pending.finish()
            }
        }
    }
}
