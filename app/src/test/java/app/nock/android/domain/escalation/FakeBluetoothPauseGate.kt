package app.nock.android.domain.escalation

import app.nock.android.bluetooth.BluetoothPauseGate

/** In-memory [BluetoothPauseGate]; tests flip [active] to simulate a connected car. */
class FakeBluetoothPauseGate(var active: Boolean = false) : BluetoothPauseGate {
    val deferred = mutableMapOf<Long, Long>()

    override fun isActive(): Boolean = active

    override fun markDeferred(escalationId: Long, recheckAtMs: Long) {
        deferred[escalationId] = recheckAtMs
    }

    override fun takeDeferred(): Map<Long, Long> = deferred.toMap().also { deferred.clear() }
}
