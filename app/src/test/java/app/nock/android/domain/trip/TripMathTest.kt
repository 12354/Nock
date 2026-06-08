package app.nock.android.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMathTest {

    private val h = 60 * 60_000L
    private val m = 60_000L

    @Test fun leaveBy_subtractsTravel() {
        val event = 1_000_000_000L
        assertEquals(event - 30 * m, TripMath.leaveBy(event, 30 * m))
    }

    @Test fun nextRecompute_picksEarliestFuturePoint() {
        val leaveBy = 100 * h
        // now well before all leads: earliest point is leaveBy - 3h
        assertEquals(leaveBy - 3 * h, TripMath.nextRecomputeAt(leaveBy, leaveBy - 10 * h))
    }

    @Test fun nextRecompute_skipsPassedPoints() {
        val leaveBy = 100 * h
        // now between the 3h and 1h leads -> next is the 1h point
        val now = leaveBy - 2 * h
        assertEquals(leaveBy - 1 * h, TripMath.nextRecomputeAt(leaveBy, now))
    }

    @Test fun nextRecompute_insideFinalWindow_returnsNull() {
        val leaveBy = 100 * h
        // within the last 15 minutes: escalation owns it, no more routing
        assertNull(TripMath.nextRecomputeAt(leaveBy, leaveBy - 5 * m))
        assertNull(TripMath.nextRecomputeAt(leaveBy, leaveBy))
        assertNull(TripMath.nextRecomputeAt(leaveBy, leaveBy + 1))
    }

    @Test fun nextRecompute_exactlyAtAPoint_treatsItAsPassed() {
        val leaveBy = 100 * h
        // now == leaveBy - 1h: that point is not strictly future, take the 15m one
        assertEquals(leaveBy - 15 * m, TripMath.nextRecomputeAt(leaveBy, leaveBy - 1 * h))
    }
}
