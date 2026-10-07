package app.nock.android.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Follows Bluetooth connections for the "pause while connected" feature.
 * ACL_CONNECTED / ACL_DISCONNECTED are on the implicit-broadcast exemption list,
 * so this works from the manifest even when the app isn't running. From
 * Android 12 they are only delivered with BLUETOOTH_CONNECT granted.
 *
 *  - A selected device connecting silences anything ringing right now (the
 *    engine holds it for later) and closes an on-screen alarm.
 *  - A selected device disconnecting (or Bluetooth turning off) brings every
 *    held escalation forward so it rings now, at the stage due by then.
 */
@AndroidEntryPoint
class BluetoothConnectionReceiver : BroadcastReceiver() {

    @Inject lateinit var monitor: BluetoothPauseMonitor
    @Inject lateinit var engine: EscalationEngine
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val address = intent.device()?.address ?: return
                monitor.onAclConnected(address)
                if (address !in monitor.selectedAddresses()) return
                monitor.notifyPauseStarted()
                runAsync { engine.onBluetoothPauseStarted() }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val address = intent.device()?.address ?: return
                monitor.onAclDisconnected(address)
                if (address !in monitor.selectedAddresses()) return
                runAsync { engine.resumeAfterBluetoothPause() }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.STATE_OFF) return
                monitor.onAdapterOff()
                runAsync { engine.resumeAfterBluetoothPause() }
            }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } catch (_: Throwable) {
                // Same backstop as EscalationReceiver: never crash the app from a
                // broadcast. A missed resume is still caught by the held
                // escalation's own periodic recheck.
            } finally {
                pending.finish()
            }
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
