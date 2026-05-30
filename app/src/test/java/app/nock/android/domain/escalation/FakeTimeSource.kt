package app.nock.android.domain.escalation

import app.nock.android.domain.time.TimeSource

/**
 * Deterministic, advanceable stand-in for the device wall clock. Every call to
 * [nowMs] returns [now], which tests mutate directly or via [advanceBy] / [set]
 * to simulate time passing, clocks jumping backwards, late alarm delivery, etc.
 */
class FakeTimeSource(var now: Long) : TimeSource() {
    override fun nowMs(): Long = now

    fun advanceBy(ms: Long) {
        now += ms
    }

    fun set(ms: Long) {
        now = ms
    }
}
