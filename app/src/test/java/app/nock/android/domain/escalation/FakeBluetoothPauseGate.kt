package app.nock.android.domain.escalation

import app.nock.android.bluetooth.BluetoothPauseGate

/** In-memory [BluetoothPauseGate]; tests flip [active] to simulate a connected car. */
class FakeBluetoothPauseGate(var active: Boolean = false) : BluetoothPauseGate {
    val deferred = mutableMapOf<Long, Long>()

    /** follower escalationId → lead escalationId */
    val collected = mutableMapOf<Long, Long>()

    override fun isActive(): Boolean = active

    override fun markDeferred(escalationId: Long, recheckAtMs: Long) {
        deferred[escalationId] = recheckAtMs
    }

    override fun takeDeferred(): Map<Long, Long> = deferred.toMap().also { deferred.clear() }

    override fun collect(leadId: Long, followerIds: Collection<Long>) {
        val followers = followerIds.toSet() - leadId
        collected.keys.removeAll { it in followers || it == leadId }
        collected.replaceAll { _, lead -> if (lead in followers) leadId else lead }
        followers.forEach { collected[it] = leadId }
    }

    override fun followersOf(leadId: Long): Set<Long> = collected.filterValues { it == leadId }.keys

    override fun leadOf(escalationId: Long): Long? = collected[escalationId]

    override fun uncollect(escalationId: Long) {
        collected.entries.removeAll { (f, l) -> f == escalationId || l == escalationId }
    }
}
