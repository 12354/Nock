package app.nock.android.bluetooth

/**
 * "Driving mode": the escalation engine's view of the Bluetooth pause. While a
 * Bluetooth device the user picked (typically a car) is connected, alarms are
 * held instead of rung so they don't distract the driver; they resume — at the
 * stage due by then — once the device disconnects.
 *
 * An interface so the engine stays unit-testable without Android's Bluetooth
 * stack; [BluetoothPauseMonitor] is the production implementation.
 */
interface BluetoothPauseGate {
    /** True while at least one selected device is connected. Synchronous and cheap. */
    fun isActive(): Boolean

    /**
     * Remember that escalation [escalationId] was held by the pause and re-armed
     * for a recheck at [recheckAtMs], so the disconnect can bring it forward.
     */
    fun markDeferred(escalationId: Long, recheckAtMs: Long)

    /** Return and forget every held escalation, as escalationId → recheckAtMs. */
    fun takeDeferred(): Map<Long, Long>
}

/** Pure decision logic behind [BluetoothPauseGate.isActive], split out for tests. */
object BluetoothPausePolicy {
    /**
     * The [selected] device addresses that are connected right now. A device's
     * [live] connection state wins when the platform can report it (null = not
     * knowable, e.g. no permission or the hidden API is unavailable); otherwise
     * we fall back to the [tracked] set maintained from ACL connect/disconnect
     * broadcasts.
     */
    fun connectedSelected(
        selected: Set<String>,
        tracked: Set<String>,
        live: (String) -> Boolean?,
    ): Set<String> = selected.filterTo(LinkedHashSet()) { addr -> live(addr) ?: (addr in tracked) }
}
