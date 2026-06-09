package app.nock.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the [PackageInstaller] commit callback for a self-update.
 *
 * The session is committed with USER_ACTION_NOT_REQUIRED so a same-signer
 * self-update applies silently — no confirmation tap and no Play Protect scan
 * prompt — on Android 12+. The platform can still demand user action in the few
 * cases the silent path isn't permitted yet: most notably the first update after
 * the app was installed by a *different* installer (Android 14 "update
 * ownership"), or a signing-certificate mismatch. There it hands back
 * STATUS_PENDING_USER_ACTION plus a confirmation Intent, which we launch; once
 * this app is the update owner, subsequent updates go silent.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // The update is user-initiated, so the app is foreground here and
                // the background-activity-launch restriction doesn't bite.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            // STATUS_SUCCESS: the system replaces the app (MY_PACKAGE_REPLACED
            // re-arms alarms via BootReceiver); nothing to do here.
            // Failures are swallowed — the next check simply re-offers the update.
        }
    }
}
