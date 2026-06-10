package app.nock.android.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomFingerprintsTest {

    private val bedroom = mapOf("aa:1" to -45, "aa:2" to -60, "aa:3" to -72, "aa:4" to -80)
    private val kitchen = mapOf("aa:1" to -75, "aa:2" to -50, "aa:5" to -55, "aa:6" to -68)

    @Test fun identical_scan_scores_one() {
        assertEquals(1.0, RoomFingerprints.similarity(bedroom, bedroom), 1e-9)
    }

    @Test fun small_rssi_jitter_scores_high() {
        val jittered = bedroom.mapValues { it.value + 4 }
        assertTrue(RoomFingerprints.similarity(bedroom, jittered) > 0.8)
    }

    @Test fun disjoint_ap_sets_score_low() {
        val elsewhere = mapOf("bb:1" to -50, "bb:2" to -60, "bb:3" to -70)
        assertTrue(RoomFingerprints.similarity(bedroom, elsewhere) < 0.3)
    }

    @Test fun empty_scan_scores_zero() {
        assertEquals(0.0, RoomFingerprints.similarity(emptyMap(), bedroom), 1e-9)
    }

    @Test fun match_picks_best_room() {
        val samples = mapOf(
            1L to listOf(bedroom),
            2L to listOf(kitchen),
        )
        val nearBedroom = bedroom.mapValues { it.value - 3 }
        val match = RoomFingerprints.matchRoom(nearBedroom, samples)!!
        assertEquals(1L, match.roomId)
        assertTrue(match.score >= RoomFingerprints.MIN_MATCH_SCORE)
    }

    @Test fun match_uses_best_sample_of_a_room() {
        val staleSample = bedroom.mapValues { it.value - 25 }
        val samples = mapOf(1L to listOf(staleSample, bedroom))
        val match = RoomFingerprints.matchRoom(bedroom, samples)!!
        assertEquals(1.0, match.score, 1e-9)
    }

    @Test fun sparse_scan_matches_nothing() {
        assertNull(RoomFingerprints.matchRoom(mapOf("aa:1" to -50), mapOf(1L to listOf(bedroom))))
    }

    @Test fun no_samples_matches_nothing() {
        assertNull(RoomFingerprints.matchRoom(bedroom, emptyMap()))
        assertNull(RoomFingerprints.matchRoom(bedroom, mapOf(1L to emptyList())))
    }

    @Test fun quality_buckets() {
        assertEquals(SpotQuality.POOR, RoomFingerprints.quality(1))
        assertEquals(SpotQuality.FAIR, RoomFingerprints.quality(4))
        assertEquals(SpotQuality.GOOD, RoomFingerprints.quality(9))
    }

    @Test fun strong_ap_count_ignores_faint_aps() {
        val levels = mapOf("a" to -50, "b" to -84, "c" to -86, "d" to -92)
        assertEquals(2, RoomFingerprints.strongApCount(levels))
    }
}
