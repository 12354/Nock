package app.nock.android.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nock.android.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

// Delivery point for RoomCheckManager's self-re-arming check alarm.
@AndroidEntryPoint
class RoomCheckReceiver : BroadcastReceiver() {

    @Inject lateinit var manager: RoomCheckManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try { manager.onCheckAlarm() } finally { pending.finish() }
        }
    }
}
