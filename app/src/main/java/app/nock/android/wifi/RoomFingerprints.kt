package app.nock.android.wifi

import kotlin.math.abs

/** How clearly a captured spot can be told apart from other rooms. */
enum class SpotQuality { GOOD, FAIR, POOR }

/**
 * Best-scoring room for a scan and its lead over the runner-up room; the
 * caller acts on [confident]. With a single trained room there is no runner-up
 * and [margin] equals [score], so the score threshold alone decides.
 */
data class RoomMatch(val roomId: Long, val score: Double, val margin: Double) {
    val confident: Boolean
        get() = score >= RoomFingerprints.MIN_MATCH_SCORE &&
            margin >= RoomFingerprints.MIN_MATCH_MARGIN
}

/**
 * Pure WiFi-fingerprint math. A fingerprint is a BSSID → RSSI (dBm) map; a
 * room is a set of such samples. Matching scores a live scan against every
 * room and picks the best, so other rooms' fingerprints act as discriminators.
 *
 * Unmapped spots (hallways, the garden, just outside the house) have no
 * fingerprint of their own, so the argmax is forced to name some trained room
 * for them. Three guards let the matcher effectively answer "nowhere" instead:
 *
 *  - a sample scores zero outright when the scan misses most of the APs that
 *    sample saw clearly ([MIN_STRONG_OVERLAP]) — RSSI distance can't be
 *    trusted when only a weak tail of the home's APs survives;
 *  - the winning room must clear [MIN_MATCH_SCORE]; and
 *  - it must beat the runner-up by [MIN_MATCH_MARGIN] — in an unmapped spot
 *    the nearest trained rooms score close together, whereas truly being in a
 *    room produces a clear winner.
 *
 * Honest limitation, surfaced via [quality]: this works because a home
 * usually sees many access points (including the neighbours'). With only a
 * couple visible, adjacent rooms have near-identical fingerprints and
 * detection degrades — the fallback deadline is the safety net there.
 */
object RoomFingerprints {
    /** Substitute for an AP absent from one side: just below any real reading. */
    const val FLOOR_DBM = -95

    /** Mean per-AP RSSI gap (dB) at which similarity reaches zero. */
    private const val MAX_MEAN_DIFF_DB = 30.0

    /**
     * RSSI at/above which an AP carries full weight in [similarity]. Weight
     * fades linearly down to zero at [FLOOR_DBM]: a 10 dB gap on a −50 dBm AP
     * is real signal, the same gap near the noise floor is mostly noise.
     */
    private const val FULL_WEIGHT_DBM = -55

    /** Minimum APs a live scan must see before matching is meaningful at all. */
    const val MIN_SCAN_APS = 4

    /** Score a room's samples must reach for a detection to count. */
    const val MIN_MATCH_SCORE = 0.55

    /** Lead the winning room must have over the runner-up room. */
    const val MIN_MATCH_MARGIN = 0.10

    /** Fraction of a sample's strong APs the scan must still see ([sampleScore]). */
    const val MIN_STRONG_OVERLAP = 0.5

    /** RSSI above which an AP counts as reliably visible. */
    const val STRONG_DBM = -85

    /** Best sample scores averaged into a room's score ([roomScore]). */
    private const val TOP_SAMPLES = 2

    /**
     * 0..1 similarity of two fingerprints: strength-weighted mean absolute
     * RSSI difference over the union of APs, with [FLOOR_DBM] standing in for
     * an AP one side doesn't see (so disjoint AP sets score near zero, not
     * high). Each AP is weighted by the stronger of its two readings, so a
     * clearly-visible AP that moved or vanished costs a lot while jitter on
     * barely-visible APs costs almost nothing.
     */
    fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var weightSum = 0.0
        var diffSum = 0.0
        for (k in a.keys + b.keys) {
            val la = a[k] ?: FLOOR_DBM
            val lb = b[k] ?: FLOOR_DBM
            val w = ((maxOf(la, lb) - FLOOR_DBM).toDouble() / (FULL_WEIGHT_DBM - FLOOR_DBM))
                .coerceIn(0.0, 1.0)
            weightSum += w
            diffSum += w * abs(la - lb)
        }
        if (weightSum == 0.0) return 0.0
        return (1.0 - diffSum / weightSum / MAX_MEAN_DIFF_DB).coerceIn(0.0, 1.0)
    }

    /**
     * Fraction of [sample]'s strong APs (≥ [STRONG_DBM]) that appear in [scan]
     * at all; 1.0 when the sample has none to check.
     */
    fun strongOverlap(scan: Map<String, Int>, sample: Map<String, Int>): Double {
        val strong = sample.entries.filter { it.value >= STRONG_DBM }
        if (strong.isEmpty()) return 1.0
        return strong.count { it.key in scan }.toDouble() / strong.size
    }

    /** [similarity] gated on [strongOverlap]; see the class doc for why. */
    fun sampleScore(scan: Map<String, Int>, sample: Map<String, Int>): Double =
        if (strongOverlap(scan, sample) < MIN_STRONG_OVERLAP) 0.0
        else similarity(scan, sample)

    /**
     * A room's score: the mean of its [TOP_SAMPLES] best sample scores. A max
     * would let one lucky noisy sample carry the room; an unqualified mean
     * would let one stale sample sink it. Null when the room has no samples.
     */
    fun roomScore(scan: Map<String, Int>, samples: List<Map<String, Int>>): Double? {
        if (samples.isEmpty()) return null
        return samples.map { sampleScore(scan, it) }
            .sortedDescending()
            .take(TOP_SAMPLES)
            .average()
    }

    /**
     * The room whose samples best match [scan] plus its margin over the
     * runner-up, or null when the scan is too sparse to distinguish anything
     * ([MIN_SCAN_APS]) or no room has samples.
     */
    fun matchRoom(scan: Map<String, Int>, samplesByRoom: Map<Long, List<Map<String, Int>>>): RoomMatch? {
        if (scan.size < MIN_SCAN_APS) return null
        var bestId: Long? = null
        var best = 0.0
        var runnerUp = 0.0
        for ((roomId, samples) in samplesByRoom) {
            val score = roomScore(scan, samples) ?: continue
            when {
                bestId == null || score > best -> {
                    if (bestId != null) runnerUp = best
                    bestId = roomId
                    best = score
                }
                score > runnerUp -> runnerUp = score
            }
        }
        return bestId?.let { RoomMatch(it, best, best - runnerUp) }
    }

    /** APs visible clearly enough to anchor a fingerprint. */
    fun strongApCount(levels: Map<String, Int>): Int = levels.count { it.value >= STRONG_DBM }

    fun quality(strongAps: Int): SpotQuality = when {
        strongAps >= 8 -> SpotQuality.GOOD
        strongAps >= 4 -> SpotQuality.FAIR
        else -> SpotQuality.POOR
    }
}
