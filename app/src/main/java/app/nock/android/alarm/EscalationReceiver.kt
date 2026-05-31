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

        // Launch the full-screen takeover here, synchronously, while we still
        // hold the background-activity-launch grant the system hands the
        // receiver of a setAlarmClock broadcast. This is the only moment that
        // grant is live: AlarmService (an FGS) can't start an activity from the
        // background on Android 14+, and the notification's full-screen intent
        // is suppressed to a heads-up while the phone is unlocked and in use —
        // so without this the loud stage would ring with no takeover until the
        // user opened the app. Only loud stages are flagged; AlarmActivity
        // re-checks the escalation and finishes itself if it was already
        // Done/Snoozed, so an occasional stale launch is harmless.
        if (intent.getBooleanExtra(IntentExtras.EXTRA_IS_LOUD_STAGE, false)) {
            launchAlarmTakeover(context, escalationId)
        }

        val pm = context.getSystemService<PowerManager>()
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nock:escalation-receive")
            ?.apply { setReferenceCounted(false); acquire(30_000L) }

        val pending = goAsync()
        scope.launch {
            try {
                engine.onAlarmFired(escalationId)
            } catch (_: Throwable) {
                // Backstop: the app-scope CoroutineScope uses a SupervisorJob
                // with no CoroutineExceptionHandler, so an uncaught throw while
                // handling an alarm would crash the app instead of just failing
                // this one fire. Swallow it here — the known recoverable case
                // (a denied foreground-service start) already degrades to a
                // plain notification inside the engine.
            } finally {
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
                pending.finish()
            }
        }
    }

    private fun launchAlarmTakeover(context: Context, escalationId: Long) {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            // reminderId is unknown here; AlarmActivity resolves it from the
            // escalation row when the intent omits it.
        }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            // Grant lapsed (very late delivery) or BAL denied: the alarm
            // notification's full-screen intent remains the lock-screen
            // fallback, and MainActivity re-launches the takeover if the user
            // opens the app while it's still ringing.
        }
    }
}
