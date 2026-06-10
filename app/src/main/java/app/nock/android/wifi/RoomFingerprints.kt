package app.nock.android.wifi

import kotlin.math.abs

/** How clearly a captured spot can be told apart from other rooms. */
enum class SpotQuality { GOOD, FAIR, POOR }

/** Best-scoring room for a scan; the caller decides if the score suffices. */
data class RoomMatch(val roomId: Long, val score: Double)

/**
 * Pure WiFi-fingerprint math. A fingerprint is a BSSID → RSSI (dBm) map; a
 * room is a set of such samples. Matching scores a live scan against every
 * sample of every room and picks the best room, so other rooms' fingerprints
 * act as discriminators — a reminder only fires when its target room wins
 * outright AND clears [MIN_MATCH_SCORE].
 *
 * Negative match: a high score alone is not enough. Outside the building the
 * room's own APs fade while unfamiliar networks (the street, other flats) come
 * up strong, yet a couple of bleed-through home APs can still inflate the
 * score. So [matchRoom] also vetoes any winner whose live scan is dominated by
 * strong APs the room has never recorded ([isElsewhere]) — "too many wrong
 * WiFis" means you are somewhere else, not in the bedroom.
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

    /** Minimum APs a live scan must see before matching is meaningful at all. */
    const val MIN_SCAN_APS = 2

    /** Score a room's best sample must reach for a detection to count. */
    const val MIN_MATCH_SCORE = 0.55

    /** RSSI above which an AP counts as reliably visible for [quality]. */
    const val STRONG_DBM = -85

    /**
     * How many strong APs unknown to the matched room it takes to start
     * doubting the match. Below this a lone foreign hotspot (a guest's phone,
     * a passing car) can never veto a real detection; at or above it the scan
     * is only rejected when those foreign APs also *outnumber* the familiar
     * ones (see [isElsewhere]).
     */
    const val MIN_FOREIGN_STRONG_TO_VETO = 2

    /**
     * 0..1 similarity of two fingerprints: mean absolute RSSI difference over
     * the union of APs, with [FLOOR_DBM] standing in for an AP one side
     * doesn't see (so disjoint AP sets score near zero, not high).
     */
    fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val keys = a.keys + b.keys
        var sum = 0.0
        for (k in keys) {
            sum += abs((a[k] ?: FLOOR_DBM) - (b[k] ?: FLOOR_DBM))
        }
        return (1.0 - sum / keys.size / MAX_MEAN_DIFF_DB).coerceIn(0.0, 1.0)
    }

    /**
     * The room whose samples best match [scan], or null when the scan is too
     * sparse to distinguish anything ([MIN_SCAN_APS]), no room has samples, or
     * the scan is dominated by APs the best room has never seen ([isElsewhere])
     * — the negative match that keeps "outside the house" from reading as a room.
     */
    fun matchRoom(scan: Map<String, Int>, samplesByRoom: Map<Long, List<Map<String, Int>>>): RoomMatch? {
        if (scan.size < MIN_SCAN_APS) return null
        var best: RoomMatch? = null
        var bestAps: Set<String> = emptySet()
        for ((roomId, samples) in samplesByRoom) {
            if (samples.isEmpty()) continue
            val score = samples.maxOf { similarity(scan, it) }
            if (best == null || score > best.score) {
                best = RoomMatch(roomId, score)
                bestAps = samples.flatMapTo(HashSet()) { it.keys }
            }
        }
        if (best == null || isElsewhere(scan, bestAps)) return null
        return best
    }

    /** APs visible clearly enough to anchor a fingerprint. */
    fun strongApCount(levels: Map<String, Int>): Int = levels.count { it.value >= STRONG_DBM }

    /**
     * Strong APs in [scan] that [roomAps] (the union of a room's sampled
     * BSSIDs) has never recorded — nearby networks foreign to the room.
     */
    fun foreignStrongApCount(scan: Map<String, Int>, roomAps: Set<String>): Int =
        scan.count { it.value >= STRONG_DBM && it.key !in roomAps }

    /**
     * Negative match: true when [scan]'s strong APs are dominated by ones
     * foreign to [roomAps]. Outside a building the room's own APs drop below
     * [STRONG_DBM] while unfamiliar networks read strong, so requiring the
     * familiar strong APs to at least match the foreign ones rejects a high
     * score earned from a few walls-bleeding home APs. Needs at least
     * [MIN_FOREIGN_STRONG_TO_VETO] foreign APs so a single stray one is never
     * enough to suppress a genuine detection.
     */
    fun isElsewhere(scan: Map<String, Int>, roomAps: Set<String>): Boolean {
        val foreign = foreignStrongApCount(scan, roomAps)
        val familiar = strongApCount(scan) - foreign
        return foreign >= MIN_FOREIGN_STRONG_TO_VETO && foreign > familiar
    }

    fun quality(strongAps: Int): SpotQuality = when {
        strongAps >= 8 -> SpotQuality.GOOD
        strongAps >= 4 -> SpotQuality.FAIR
        else -> SpotQuality.POOR
    }
}
