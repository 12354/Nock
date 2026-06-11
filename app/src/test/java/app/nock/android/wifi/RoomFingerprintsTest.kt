package app.nock.android.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun equal_rssi_gap_on_a_strong_ap_costs_more() {
        val strongApMoved = bedroom + ("aa:1" to -60) // -45 → -60
        val weakApMoved = bedroom + ("aa:4" to -95) // -80 → -95
        assertTrue(
            RoomFingerprints.similarity(bedroom, strongApMoved) <
                RoomFingerprints.similarity(bedroom, weakApMoved)
        )
    }

    @Test fun match_picks_best_room_confidently() {
        val samples = mapOf(
            1L to listOf(bedroom),
            2L to listOf(kitchen),
        )
        val nearBedroom = bedroom.mapValues { it.value - 3 }
        val match = RoomFingerprints.matchRoom(nearBedroom, samples)!!
        assertEquals(1L, match.roomId)
        assertTrue(match.confident)
    }

    @Test fun near_tie_between_rooms_is_not_confident() {
        val twinRoom = bedroom.mapValues { it.value - 2 }
        val samples = mapOf(1L to listOf(bedroom), 2L to listOf(twinRoom))
        val match = RoomFingerprints.matchRoom(bedroom.mapValues { it.value - 1 }, samples)!!
        assertTrue(match.score >= RoomFingerprints.MIN_MATCH_SCORE)
        assertTrue(match.margin < RoomFingerprints.MIN_MATCH_MARGIN)
        assertFalse(match.confident)
    }

    @Test fun a_modest_but_real_lead_is_confident() {
        // Field case: adjacent rooms share the home's strong APs, so the correct
        // (top-scoring) room leads the runner-up by only a few points. A clear
        // winner like 73% over 65% must fire rather than be suppressed as a tie.
        val match = RoomMatch(roomId = 1L, score = 0.73, margin = 0.08)
        assertTrue(match.confident)
    }

    @Test fun single_trained_room_needs_only_the_score() {
        val match = RoomFingerprints.matchRoom(bedroom, mapOf(1L to listOf(bedroom)))!!
        assertEquals(match.score, match.margin, 1e-9)
        assertTrue(match.confident)
    }

    @Test fun one_lucky_sample_cannot_carry_a_room() {
        val staleSample = bedroom.mapValues { it.value - 25 }
        val samples = mapOf(1L to listOf(staleSample, bedroom))
        val match = RoomFingerprints.matchRoom(bedroom, samples)!!
        val expected = (1.0 + RoomFingerprints.sampleScore(bedroom, staleSample)) / 2
        assertEquals(expected, match.score, 1e-9)
        assertTrue(match.score < 1.0)
    }

    @Test fun room_score_averages_only_the_best_samples() {
        val junkSample = mapOf("zz:1" to -50, "zz:2" to -60, "zz:3" to -70, "zz:4" to -80)
        val samples = listOf(bedroom, bedroom, junkSample)
        assertEquals(1.0, RoomFingerprints.roomScore(bedroom, samples)!!, 1e-9)
    }

    @Test fun missing_strong_aps_zero_a_sample() {
        // Just outside the house: only the weakest of bedroom's APs survives,
        // padded out with neighbours' networks.
        val outside = mapOf("aa:4" to -92, "nn:1" to -80, "nn:2" to -85, "nn:3" to -90)
        assertEquals(0.25, RoomFingerprints.strongOverlap(outside, bedroom), 1e-9)
        assertEquals(0.0, RoomFingerprints.sampleScore(outside, bedroom), 1e-9)
    }

    @Test fun sparse_scan_matches_nothing() {
        val sparse = mapOf("aa:1" to -45, "aa:2" to -60, "aa:3" to -72)
        assertNull(RoomFingerprints.matchRoom(sparse, mapOf(1L to listOf(bedroom))))
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
