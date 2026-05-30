package app.nock.android.widget

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.nock.android.ui.voice.VoiceAlarmActivity

/**
 * Handles a widget tap by routing it to the right place:
 *  * If RECORD_AUDIO has never been granted, launch [VoiceAlarmActivity] so
 *    the existing in-activity permission flow can prompt the user. Subsequent
 *    taps go through the service.
 *  * Otherwise, fire ACTION_TOGGLE at [VoiceCaptureService]; the service is
 *    the authoritative state machine and decides whether the tap means start
 *    or stop based on its own running state.
 */
class VoiceWidgetToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            val launch = Intent(context, VoiceAlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // A widget tap grants a background-activity-launch window, so this
            // normally succeeds; guard it anyway so an edge-case denial can't
            // crash the receiver (consistent with the other launch sites).
            runCatching { context.startActivity(launch) }
            return
        }

        val svc = Intent(context, VoiceCaptureService::class.java)
            .setAction(VoiceCaptureService.ACTION_TOGGLE)
        ContextCompat.startForegroundService(context, svc)
    }
}
