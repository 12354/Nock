package app.nock.android.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A paired Bluetooth device as shown in the picker. */
data class BluetoothDeviceInfo(val address: String, val name: String)

/**
 * Production [BluetoothPauseGate]. State lives in SharedPreferences rather than
 * the Room settings table because [isActive] must be answerable synchronously —
 * EscalationReceiver decides whether to launch the full-screen takeover on the
 * main thread, inside the short background-activity-launch grant. It is also
 * device-local on purpose (paired devices differ per phone), so it is not part
 * of the Drive snapshot.
 *
 * "Is device X connected" has no public API, so the answer combines two signals
 * (see [BluetoothPausePolicy]): the hidden-but-greylisted
 * BluetoothDevice.isConnected(), and a set tracked from ACL broadcasts by
 * [BluetoothConnectionReceiver] as a fallback when the former is unavailable.
 */
@Singleton
class BluetoothPauseMonitor @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : BluetoothPauseGate {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _pauseStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when a selected device connects, so an on-screen alarm can close itself. */
    val pauseStarted: SharedFlow<Unit> = _pauseStarted.asSharedFlow()

    fun notifyPauseStarted() {
        _pauseStarted.tryEmit(Unit)
    }

    // ---- Selection --------------------------------------------------------

    fun selectedAddresses(): Set<String> =
        prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()

    /** The name a device had when it was picked, for display if it is later unpaired. */
    fun selectedName(address: String): String? = prefs.getString(nameKey(address), null)

    fun setSelected(device: BluetoothDeviceInfo, selected: Boolean) {
        val next = selectedAddresses().let { if (selected) it + device.address else it - device.address }
        prefs.edit().apply {
            putStringSet(KEY_SELECTED, next)
            if (selected) putString(nameKey(device.address), device.name) else remove(nameKey(device.address))
        }.apply()
    }

    // ---- Connection state -------------------------------------------------

    override fun isActive(): Boolean = connectedSelected().isNotEmpty()

    /** Addresses of selected devices that are connected right now. */
    fun connectedSelected(): Set<String> {
        val selected = selectedAddresses()
        if (selected.isEmpty()) return emptySet()
        val adapter = adapter()
        // Bluetooth off (or absent): nothing can be connected, whatever we tracked.
        if (adapter == null || !safeIsEnabled(adapter)) return emptySet()
        return BluetoothPausePolicy.connectedSelected(selected, trackedConnected()) { addr ->
            liveConnected(adapter, addr)
        }
    }

    fun onAclConnected(address: String) {
        prefs.edit().putStringSet(KEY_CONNECTED, trackedConnected() + address).apply()
    }

    fun onAclDisconnected(address: String) {
        prefs.edit().putStringSet(KEY_CONNECTED, trackedConnected() - address).apply()
    }

    fun onAdapterOff() {
        prefs.edit().remove(KEY_CONNECTED).apply()
    }

    private fun trackedConnected(): Set<String> =
        prefs.getStringSet(KEY_CONNECTED, emptySet()).orEmpty().toSet()

    // ---- Deferred escalations ---------------------------------------------

    override fun markDeferred(escalationId: Long, recheckAtMs: Long) {
        val entries = deferredEntries().filterKeys { it != escalationId } + (escalationId to recheckAtMs)
        prefs.edit().putStringSet(KEY_DEFERRED, entries.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    override fun takeDeferred(): Map<Long, Long> {
        val entries = deferredEntries()
        prefs.edit().remove(KEY_DEFERRED).apply()
        return entries
    }

    private fun deferredEntries(): Map<Long, Long> =
        prefs.getStringSet(KEY_DEFERRED, emptySet()).orEmpty().mapNotNull { token ->
            val parts = token.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (id != null && at != null) id to at else null
        }.toMap()

    // ---- Platform ---------------------------------------------------------

    /** BLUETOOTH_CONNECT is a runtime permission from Android 12; before that it's install-time. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun isBluetoothAvailable(): Boolean = adapter() != null

    /** Paired devices, sorted by name. Empty without permission or with Bluetooth off. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceInfo> {
        if (!hasPermission()) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { BluetoothDeviceInfo(it.address, deviceName(it)) }
        }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    fun deviceName(device: BluetoothDevice): String =
        runCatching {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.address

    private fun adapter(): BluetoothAdapter? = ctx.getSystemService<BluetoothManager>()?.adapter

    @SuppressLint("MissingPermission")
    private fun safeIsEnabled(adapter: BluetoothAdapter): Boolean =
        runCatching { adapter.isEnabled }.getOrDefault(true)

    /**
     * BluetoothDevice.isConnected() is hidden but on the SDK greylist, so it is
     * callable from apps; null when it can't be used (no permission, removed on
     * some future platform, OEM oddities) so the tracked set takes over.
     */
    private fun liveConnected(adapter: BluetoothAdapter, address: String): Boolean? {
        if (!hasPermission()) return null
        return runCatching {
            val device = adapter.getRemoteDevice(address)
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
        }.getOrNull()
    }

    private fun nameKey(address: String) = "$KEY_NAME_PREFIX$address"

    companion object {
        private const val PREFS = "bluetooth_pause"
        private const val KEY_SELECTED = "selected"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_DEFERRED = "deferred"
        private const val KEY_NAME_PREFIX = "name:"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BluetoothPauseModule {
    @Binds abstract fun bindGate(impl: BluetoothPauseMonitor): BluetoothPauseGate
}
