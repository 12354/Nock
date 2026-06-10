package app.nock.android.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.dao.WifiRoomDao
import app.nock.android.data.json.WifiLevelsJson
import app.nock.android.wifi.RoomFingerprints
import app.nock.android.wifi.SpotQuality
import app.nock.android.wifi.WifiScanProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One AP seen in the live scan: BSSID and its RSSI, plus whether it counts as "strong". */
data class ApReading(val bssid: String, val level: Int) {
    val strong: Boolean get() = level >= RoomFingerprints.STRONG_DBM
}

/** A captured room scored against the live scan, exactly as the matcher would. */
data class RoomMatchRow(
    val roomId: Long,
    val name: String,
    val sampleCount: Int,
    /** Best similarity (0..1) over the room's samples; null when the room has none. */
    val score: Double?,
)

/** The result of one live probe: the scan plus every room ranked against it. */
data class WifiProbe(
    val ageMs: Long,
    val apCount: Int,
    val strongAps: Int,
    val quality: SpotQuality,
    /** Whether the scan has enough APs for matching to even be attempted. */
    val enoughAps: Boolean,
    /** Rooms ranked by score (best first); rooms without samples sort last. */
    val rooms: List<RoomMatchRow>,
    val aps: List<ApReading>,
    /** Strong APs the top-scoring room has never seen — the negative-match signal. */
    val topRoomForeignAps: Int = 0,
    /** True when the top room scores well but is vetoed as "elsewhere" (too many foreign APs). */
    val topRoomElsewhere: Boolean = false,
) {
    /** The winning room, if any room would be detected right now. */
    val winner: RoomMatchRow? get() = rooms.firstOrNull()?.takeIf {
        enoughAps && (it.score ?: 0.0) >= RoomFingerprints.MIN_MATCH_SCORE && !topRoomElsewhere
    }
}

data class WifiDebugState(
    val hasFine: Boolean = false,
    val hasBackground: Boolean = false,
    val wifiAvailable: Boolean = false,
    val scanning: Boolean = false,
    /** No room has any captured samples — matching can never succeed. */
    val noSamples: Boolean = false,
    /** Null until the first probe; set to null again while a new scan runs. */
    val probe: WifiProbe? = null,
    /** Set when a probe returns nothing (no permission, WiFi off, empty scan). */
    val error: String? = null,
)

/**
 * Live diagnostic for WiFi indoor positioning. Mirrors what [app.nock.android.wifi.RoomCheckManager]
 * does on each background check — read a scan, score every captured room against it,
 * pick the best — but surfaces the full ranking and raw AP list so the user can stand
 * in a room and see exactly why it does (or doesn't) get detected.
 */
@HiltViewModel
class WifiDebugViewModel @Inject constructor(
    private val scanner: WifiScanProvider,
    private val roomDao: WifiRoomDao,
) : ViewModel() {

    private val _state = MutableStateFlow(WifiDebugState())
    val state: StateFlow<WifiDebugState> = _state.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        _state.update {
            it.copy(
                hasFine = scanner.hasLocationPermission(),
                hasBackground = scanner.hasBackgroundLocationPermission(),
                wifiAvailable = scanner.isWifiScanningAvailable(),
            )
        }
    }

    /**
     * Request a real scan (foreground, so the throttle is generous) and score every
     * room against it. [forceFresh] always asks the radio; otherwise a recent cached
     * scan is reused, matching the cheap path the background checks prefer.
     */
    fun scan(forceFresh: Boolean = true) {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, probe = null, error = null) }
        viewModelScope.launch {
            refreshPermissions()

            val scan = if (forceFresh) scanner.freshScan() else (scanner.cachedScan() ?: scanner.freshScan())
            if (scan == null || scan.levels.isEmpty()) {
                _state.update {
                    it.copy(
                        scanning = false,
                        error = when {
                            !it.hasFine -> "No location permission — scan results are gated behind it."
                            !it.wifiAvailable -> "WiFi scanning is off — enable WiFi (or scanning-always-available)."
                            else -> "Scan returned no access points (throttled, or none in range)."
                        },
                    )
                }
                return@launch
            }

            val samplesByRoom = roomDao.getAllSamples()
                .groupBy({ it.roomId }, { WifiLevelsJson.decode(it.levelsJson) })
                .mapValues { (_, v) -> v.filterNotNull() }
            val rooms = roomDao.getRooms()

            val matchRows = rooms.map { room ->
                val samples = samplesByRoom[room.id].orEmpty()
                RoomMatchRow(
                    roomId = room.id,
                    name = room.name,
                    sampleCount = samples.size,
                    score = if (samples.isEmpty()) null
                    else samples.maxOf { RoomFingerprints.similarity(scan.levels, it) },
                )
            }.sortedWith(compareByDescending { it.score ?: -1.0 })

            // Apply the matcher's negative match to the top scored room with samples,
            // so the diagnostic suppresses (and explains) the same "outside" false
            // positives the background check now rejects.
            val topRoom = matchRows.firstOrNull { it.sampleCount > 0 }
            val topRoomAps = topRoom?.let { samplesByRoom[it.roomId].orEmpty().flatMapTo(HashSet()) { s -> s.keys } }
                ?: emptySet()
            val topForeign = RoomFingerprints.foreignStrongApCount(scan.levels, topRoomAps)
            val topElsewhere = topRoom != null && RoomFingerprints.isElsewhere(scan.levels, topRoomAps)

            val strong = RoomFingerprints.strongApCount(scan.levels)
            val aps = scan.levels.entries
                .map { ApReading(it.key, it.value) }
                .sortedByDescending { it.level }

            _state.update {
                it.copy(
                    scanning = false,
                    noSamples = matchRows.none { r -> r.sampleCount > 0 },
                    error = null,
                    probe = WifiProbe(
                        ageMs = scan.ageMs,
                        apCount = scan.levels.size,
                        strongAps = strong,
                        quality = RoomFingerprints.quality(strong),
                        enoughAps = scan.levels.size >= RoomFingerprints.MIN_SCAN_APS,
                        rooms = matchRows,
                        aps = aps,
                        topRoomForeignAps = topForeign,
                        topRoomElsewhere = topElsewhere,
                    ),
                )
            }
        }
    }

    /** A copy-pasteable text dump of the current probe for bug reports. */
    fun buildDump(s: WifiDebugState): String = buildString {
        appendLine("Nock WiFi positioning probe")
        appendLine("permissions: fine=${s.hasFine} background=${s.hasBackground} wifiOn=${s.wifiAvailable}")
        appendLine("thresholds: minScanAps=${RoomFingerprints.MIN_SCAN_APS} minMatchScore=${RoomFingerprints.MIN_MATCH_SCORE}")
        val p = s.probe
        if (p == null) {
            appendLine(s.error ?: "no probe yet")
            return@buildString
        }
        appendLine()
        appendLine("scan: aps=${p.apCount} strong=${p.strongAps} quality=${p.quality} age=${p.ageMs / 1000}s enoughAps=${p.enoughAps}")
        val noWinnerReason = if (p.topRoomElsewhere)
            "none — best room vetoed: ${p.topRoomForeignAps} strong foreign APs (looks like outside the room)"
        else "none — no room clears the match threshold"
        appendLine("winner: ${p.winner?.let { "${it.name} (${pct(it.score)})" } ?: noWinnerReason}")
        appendLine()
        appendLine("== ROOM SCORES (${p.rooms.size}) ==")
        p.rooms.forEach { r ->
            val score = if (r.score == null) "no samples" else pct(r.score)
            appendLine("${r.name}: $score  (samples=${r.sampleCount})")
        }
        appendLine()
        appendLine("== ACCESS POINTS (${p.aps.size}) ==")
        p.aps.forEach { appendLine("${it.bssid}  ${it.level} dBm${if (it.strong) "  [strong]" else ""}") }
    }

    companion object {
        fun pct(score: Double?): String =
            if (score == null) "—" else "${(score * 100).toInt()}%"
    }
}
