package app.nock.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.trip.CalendarEvent
import app.nock.android.trip.CalendarInfo
import app.nock.android.trip.CalendarRepository
import app.nock.android.trip.TripPreview
import app.nock.android.trip.TripSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which step of the three-step manual single-appointment importer is on screen. */
enum class ManualImportStage { PICK_CALENDAR, PICK_EVENT, PREVIEW }

data class ManualImportUiState(
    val stage: ManualImportStage = ManualImportStage.PICK_CALENDAR,
    val calendars: List<CalendarInfo> = emptyList(),
    val selectedCalendar: CalendarInfo? = null,
    val events: List<CalendarEvent> = emptyList(),
    val eventsLoading: Boolean = false,
    val query: String = "",
    val selectedEvent: CalendarEvent? = null,
    val preview: TripPreview? = null,
    val previewLoading: Boolean = false,
    val importing: Boolean = false,
    val imported: Boolean = false,
    /**
     * Buffer (in whole minutes) chosen on the preview's slider: how long before
     * departure the first heads-up fires, so the reminder starts at
     * `appointment − travel − buffer`. Seeded from the configured trip default.
     */
    val bufferMin: Int = ManualImportViewModel.DEFAULT_BUFFER_MIN,
)

/**
 * Drives the manual single-appointment importer: pick a calendar, then an
 * appointment, then preview the alarm (travel time + escalation steps) and import.
 * A dedicated screen-scoped ViewModel keeps this self-contained multi-step state
 * out of the large [SettingsViewModel].
 */
@HiltViewModel
class ManualImportViewModel @Inject constructor(
    private val trips: TripSyncManager,
    private val calendar: CalendarRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualImportUiState())
    val state: StateFlow<ManualImportUiState> = _state.asStateFlow()

    init {
        loadCalendars()
        loadDefaultBuffer()
    }

    private fun loadCalendars() {
        viewModelScope.launch {
            val cals = withContext(Dispatchers.IO) { calendar.listCalendars() }
            _state.update { it.copy(calendars = cals) }
        }
    }

    /** Seed the buffer slider from the configured trip default (defaults to 30 min). */
    private fun loadDefaultBuffer() {
        viewModelScope.launch {
            val min = withContext(Dispatchers.IO) { trips.configuredBufferMin() }
                .coerceIn(MIN_BUFFER_MIN, MAX_BUFFER_MIN)
            // Don't clobber a value the user has already nudged on the slider.
            _state.update { if (it.preview == null && !it.imported) it.copy(bufferMin = min) else it }
        }
    }

    fun selectCalendar(cal: CalendarInfo) {
        _state.update {
            it.copy(
                stage = ManualImportStage.PICK_EVENT,
                selectedCalendar = cal,
                events = emptyList(),
                eventsLoading = true,
                query = "",
            )
        }
        viewModelScope.launch {
            val events = withContext(Dispatchers.IO) { trips.eventsForCalendar(cal.id) }
            // Ignore a stale load if the user already backed out / switched calendars.
            _state.update {
                if (it.selectedCalendar?.id != cal.id) it
                else it.copy(events = events, eventsLoading = false)
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun selectEvent(event: CalendarEvent) {
        _state.update {
            it.copy(
                stage = ManualImportStage.PREVIEW,
                selectedEvent = event,
                preview = null,
                previewLoading = true,
                imported = false,
            )
        }
        refreshPreview(event)
    }

    private fun refreshPreview(event: CalendarEvent) {
        val bufferMs = _state.value.bufferMin * 60_000L
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { trips.previewEvent(event, bufferMs) }
            _state.update {
                if (it.selectedEvent != event) it
                else it.copy(preview = preview, previewLoading = false)
            }
        }
    }

    /**
     * Slider handler: re-frame the on-screen preview for the new buffer. Only the
     * escalation step offsets depend on it, so this is a cheap local recompute — no
     * re-routing — that updates the "start time = appointment − travel − buffer".
     */
    fun setBufferMin(min: Int) {
        val clamped = min.coerceIn(MIN_BUFFER_MIN, MAX_BUFFER_MIN)
        _state.update { st ->
            if (st.bufferMin == clamped) return@update st
            val reframed = st.preview?.let { trips.reframeWithBuffer(it, clamped * 60_000L) }
            st.copy(bufferMin = clamped, preview = reframed ?: st.preview)
        }
    }

    fun importSelected() {
        val event = _state.value.selectedEvent ?: return
        if (_state.value.importing) return
        val bufferMs = _state.value.bufferMin * 60_000L
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trips.importEvent(event, bufferMs) }
            _state.update { it.copy(importing = false, imported = true) }
        }
    }

    /** Restart the flow to import another appointment from the same calendar. */
    fun importAnother() {
        val cal = _state.value.selectedCalendar
        _state.update {
            it.copy(
                stage = if (cal != null) ManualImportStage.PICK_EVENT else ManualImportStage.PICK_CALENDAR,
                selectedEvent = null,
                preview = null,
                previewLoading = false,
                imported = false,
                query = "",
            )
        }
    }

    /**
     * Step back one stage. Returns false when already at the first step, so the
     * screen can leave the importer entirely.
     */
    fun back(): Boolean {
        val s = _state.value
        return when (s.stage) {
            ManualImportStage.PREVIEW -> {
                _state.update {
                    it.copy(
                        stage = ManualImportStage.PICK_EVENT,
                        selectedEvent = null,
                        preview = null,
                        previewLoading = false,
                        imported = false,
                    )
                }
                true
            }
            ManualImportStage.PICK_EVENT -> {
                _state.update {
                    it.copy(
                        stage = ManualImportStage.PICK_CALENDAR,
                        selectedCalendar = null,
                        events = emptyList(),
                        eventsLoading = false,
                        query = "",
                    )
                }
                true
            }
            ManualImportStage.PICK_CALENDAR -> false
        }
    }

    companion object {
        /** Slider bounds for the heads-up buffer, in minutes. */
        const val MIN_BUFFER_MIN = 5
        const val MAX_BUFFER_MIN = 120
        const val DEFAULT_BUFFER_MIN = 30
    }
}
