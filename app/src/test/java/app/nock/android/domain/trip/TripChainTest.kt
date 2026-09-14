package app.nock.android.domain.trip

import app.nock.android.domain.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripChainTest {

    private val m = 60_000L

    @Test fun build_startsLoudAlarmWithBufferBeforeDeparture() {
        val chain = TripChain.build(bufferMs = 30 * m, repeatIntervalMs = 10 * m)
        assertEquals(StageType.SILENT, chain.stages.first().type)
        assertEquals(-60 * m, chain.stages.first().offsetMs)
        assertEquals(StageType.ALARM, chain.stages.last().type)
        assertEquals(-30 * m, chain.stages.last().offsetMs)
        assertEquals(10 * m, chain.repeatIntervalMs)
    }

    @Test fun dentist_alarmAt0937_doesNotMoveDepartureAt1007() {
        val appointment = (10 * 60 + 30) * m
        val leaveBy = TripMath.leaveBy(appointment, 23 * m)
        val chain = TripChain.build(30 * m, 10 * m)
        assertEquals((10 * 60 + 7) * m, leaveBy)
        assertEquals((9 * 60 + 37) * m, leaveBy + chain.stages.last().offsetMs)
        assertEquals(
            listOf((9 * 60 + 7) * m, (9 * 60 + 27) * m, (9 * 60 + 37) * m),
            chain.stages.map { leaveBy + it.offsetMs },
        )
    }

    @Test fun build_alarmHonorsSmallAndZeroBuffers() {
        for (buffer in listOf(0L, 30_000L, 5 * m, 30 * m, 120 * m)) {
            val chain = TripChain.build(buffer, 10 * m)
            assertEquals(-buffer, chain.stages.last().offsetMs)
        }
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
