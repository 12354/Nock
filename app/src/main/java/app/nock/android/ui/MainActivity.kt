package app.nock.android.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import app.nock.android.alarm.AlarmActivity
import app.nock.android.alarm.AlarmService
import app.nock.android.alarm.IntentExtras
import app.nock.android.di.ApplicationScope
import app.nock.android.trip.TripSyncManager
import app.nock.android.ui.theme.NockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var trips: TripSyncManager
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op; user toggled */ }

    private var requestedTab by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        val initialTab = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: Tab.Today.route
        setContent {
            NockTheme {
                NockApp(
                    initialTab = initialTab,
                    requestedTab = requestedTab,
                    onTabConsumed = { requestedTab = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { requestedTab = it }
    }

    override fun onResume() {
        super.onResume()
        // If an alarm is currently ringing, the full-screen alarm view should
        // always be on top — re-launch it in case the user navigated away
        // (e.g. via Home) or its full-screen intent was suppressed earlier.
        val escalationId = AlarmService.ringingEscalationId
        val reminderId = AlarmService.ringingReminderId
        if (escalationId != null) {
            val intent = Intent(this, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
                if (reminderId != null) putExtra(IntentExtras.EXTRA_REMINDER_ID, reminderId)
            }
            runCatching { startActivity(intent) }
        }

        // Import/refresh calendar trip reminders on every foreground. No-ops
        // quickly when the feature is off or calendar permission isn't granted.
        appScope.launch { runCatching { trips.syncNow() } }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService<AlarmManager>()
            if (am != null && !am.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
            }
        }
        // Android 14+ reclassified USE_FULL_SCREEN_INTENT as a special-access
        // permission. Without it the full-screen alarm activity never launches
        // from a locked screen, defeating the whole point of the loud stage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService<NotificationManager>()
            if (nm != null && !nm.canUseFullScreenIntent()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
            }
        }
    }
}
