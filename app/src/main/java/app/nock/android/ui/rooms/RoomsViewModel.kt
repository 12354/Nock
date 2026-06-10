package app.nock.android.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.dao.WifiRoomDao
import app.nock.android.data.entity.WifiRoomEntity
import app.nock.android.data.entity.WifiRoomSampleEntity
import app.nock.android.data.json.WifiLevelsJson
import app.nock.android.wifi.RoomFingerprints
import app.nock.android.wifi.SpotQuality
import app.nock.android.wifi.WifiScanProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomUi(
    val id: Long,
    val name: String,
    val sampleCount: Int,
    /** Best strong-AP count across the room's samples; null when unsampled. */
    val strongAps: Int?,
) {
    val quality: SpotQuality? get() = strongAps?.let { RoomFingerprints.quality(it) }
}

/**
 * The in-flight or just-finished capture of one room. [scansDone] counts the
 * scans already stored this run; the result fields are set when it finishes.
 */
data class CaptureUi(
    val roomId: Long,
    val scansDone: Int = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val strongAps: Int? = null,
) {
    val quality: SpotQuality? get() = strongAps?.let { RoomFingerprints.quality(it) }
}

data class RoomsPermissions(
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val wifiAvailable: Boolean = false,
)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val dao: WifiRoomDao,
    private val scanner: WifiScanProvider,
) : ViewModel() {

    val rooms: StateFlow<List<RoomUi>> =
        combine(dao.observeRooms(), dao.observeSamples()) { rooms, samples ->
            val byRoom = samples.groupBy { it.roomId }
            rooms.map { room ->
                val roomSamples = byRoom[room.id].orEmpty()
                RoomUi(
                    id = room.id,
                    name = room.name,
                    sampleCount = roomSamples.size,
                    strongAps = roomSamples
                        .mapNotNull { WifiLevelsJson.decode(it.levelsJson) }
                        .maxOfOrNull { RoomFingerprints.strongApCount(it) }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _capture = MutableStateFlow<CaptureUi?>(null)
    val capture: StateFlow<CaptureUi?> = _capture.asStateFlow()

    private val _permissions = MutableStateFlow(RoomsPermissions())
    val permissions: StateFlow<RoomsPermissions> = _permissions.asStateFlow()

    private var captureJob: Job? = null

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        _permissions.value = RoomsPermissions(
            fineLocation = scanner.hasLocationPermission(),
            backgroundLocation = scanner.hasBackgroundLocationPermission(),
            wifiAvailable = scanner.isWifiScanningAvailable(),
        )
    }

    fun addRoom(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            dao.upsertRoom(WifiRoomEntity(name = trimmed, createdAt = System.currentTimeMillis()))
        }
    }

    fun renameRoom(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            dao.getRoom(id)?.let { dao.upsertRoom(it.copy(name = trimmed)) }
        }
    }

    fun deleteRoom(id: Long) {
        viewModelScope.launch { dao.deleteRoom(id) }
    }

    /**
     * Sample the WiFi environment from the user's current spot for ~15 s and
     * store the scans as fingerprint samples of [roomId]. The screen is in the
     * foreground here, so real scans are allowed (4 per 2 minutes) — pacing
     * [CAPTURE_SCANS] of them [CAPTURE_GAP_MS] apart stays inside that budget.
     * [replaceExisting] is the "re-capture" flavour; off, it adds samples.
     */
    fun startCapture(roomId: Long, replaceExisting: Boolean) {
        if (captureJob?.isActive == true) return
        captureJob = viewModelScope.launch {
            _capture.value = CaptureUi(roomId)
            if (replaceExisting) dao.deleteSamplesForRoom(roomId)
            var stored = 0
            var bestStrongAps: Int? = null
            repeat(CAPTURE_SCANS) { i ->
                val scan = scanner.freshScan(timeoutMs = 6_000L)
                if (scan != null && scan.levels.isNotEmpty()) {
                    dao.insertSample(
                        WifiRoomSampleEntity(
                            roomId = roomId,
                            capturedAt = System.currentTimeMillis(),
                            levelsJson = WifiLevelsJson.encode(scan.levels)
                        )
                    )
                    stored++
                    val strong = RoomFingerprints.strongApCount(scan.levels)
                    if (bestStrongAps == null || strong > bestStrongAps!!) bestStrongAps = strong
                }
                _capture.update { it?.copy(scansDone = stored) }
                if (i < CAPTURE_SCANS - 1) delay(CAPTURE_GAP_MS)
            }
            _capture.update {
                it?.copy(finished = true, failed = stored == 0, strongAps = bestStrongAps)
            }
        }
    }

    fun dismissCaptureResult() {
        if (captureJob?.isActive == true) return
        _capture.value = null
    }

    companion object {
        const val CAPTURE_SCANS = 3
        const val CAPTURE_GAP_MS = 5_000L
    }
}
