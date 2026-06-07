package app.nock.android.domain.trip

import app.nock.android.domain.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripChainTest {

    private val m = 60_000L

    @Test fun build_anchorsAlarmAtDeparture() {
        val chain = TripChain.build(bufferMs = 30 * m, repeatIntervalMs = 10 * m)
        assertEquals(StageType.SILENT, chain.stages.first().type)
        assertEquals(-30 * m, chain.stages.first().offsetMs)
        assertEquals(StageType.ALARM, chain.stages.last().type)
        assertEquals(0L, chain.stages.last().offsetMs) // loud alarm exactly at leave-by
        assertEquals(10 * m, chain.repeatIntervalMs)
    }

    @Test fun build_offsetsStrictlyIncreasingAndTypesUnique() {
        for (bufMin in intArrayOf(2, 5, 10, 30, 45, 90)) {
            val chain = TripChain.build(bufMin * m, 10 * m)
            val offsets = chain.stages.map { it.offsetMs }
            assertTrue("offsets must increase for buffer=$bufMin", offsets.zipWithNext().all { it.first < it.second })
            val types = chain.stages.map { it.type }
            assertEquals("types must be unique for buffer=$bufMin", types.size, types.toSet().size)
        }
    }

    @Test fun build_tinyBufferStillValid() {
        // Below the minimum buffer the chain must not collapse stages onto the
        // same offset (EscalationChain forbids duplicate types / would mis-order).
        val chain = TripChain.build(bufferMs = 30_000L, repeatIntervalMs = 10 * m)
        val offsets = chain.stages.map { it.offsetMs }
        assertTrue(offsets.zipWithNext().all { it.first < it.second })
    }
}
