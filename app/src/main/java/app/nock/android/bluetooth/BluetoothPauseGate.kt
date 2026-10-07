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

    // ---- Collected alarm (several held reminders released as one) ----------

    /**
     * Attach [followerIds] to the collected alarm led by [leadId]. A follower that
     * was itself leading a collection hands its own followers to [leadId], so
     * collections never nest.
     */
    fun collect(leadId: Long, followerIds: Collection<Long>)

    /** Escalations riding along with [leadId]'s collected alarm. */
    fun followersOf(leadId: Long): Set<Long>

    /** The lead whose collected alarm [escalationId] rides along with, if any. */
    fun leadOf(escalationId: Long): Long?

    /** Drop [escalationId] from any collection, as follower or lead. */
    fun uncollect(escalationId: Long)
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
