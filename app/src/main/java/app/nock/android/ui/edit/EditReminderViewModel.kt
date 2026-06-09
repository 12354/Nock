package app.nock.android.ui.edit

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Schedule
import app.nock.android.history.AlarmHistoryLogger
import app.nock.android.trip.TripSyncManager
import app.nock.android.voice.DeepSeekParseResult
import app.nock.android.voice.DeepSeekReminderParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class EditState(
    val reminderId: Long = 0,
    val name: String = "",
    val groupId: Long = 0,
    val scheduleType: ScheduleKind = ScheduleKind.ONESHOT,
    val oneShotMs: Long = System.currentTimeMillis() + 60 * 60_000L,
    val dailyTimesMinutes: List<Int> = listOf(8 * 60),
    val weeklyDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
    val weeklyTimesMinutes: List<Int> = listOf(8 * 60),
    val monthlyDay: Int = 1,
    val monthlyTimeMinutes: Int = 9 * 60,
    val intervalHours: Int = 8,
    val intervalStartMs: Long? = null,
    val groups: List<Group> = emptyList(),
    // Location and buffer are only meaningful — and only shown — for reminders
    // imported from the calendar; isCalendarReminder gates their visibility.
    val isCalendarReminder: Boolean = false,
    val location: String = "",
    // Per-reminder heads-up buffer in whole minutes: the reminder starts at
    // appointment − travel − buffer. Drives this trip's escalation chain alone.
    val bufferMin: Int = EditReminderViewModel.DEFAULT_TRIP_BUFFER_MIN,
    val nlInput: String = "",
    val nlThinking: Boolean = false,
    val nlError: String? = null,
)

enum class ScheduleKind { ONESHOT, DAILY, WEEKLY, MONTHLY, INTERVAL, ON_UNLOCK }

@HiltViewModel
class EditReminderViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val deepSeekParser: DeepSeekReminderParser,
    private val history: AlarmHistoryLogger,
    private val tripSync: TripSyncManager,
    savedState: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    private var aiDebounceJob: Job? = null

    init {
        viewModelScope.launch {
            val groups = repo.getGroups()
            _state.update { it.copy(groups = groups, groupId = groups.firstOrNull()?.id ?: 0L) }
        }
    }

    fun load(reminderId: Long) {
        if (reminderId == 0L) return
        viewModelScope.launch {
            val r = repo.getReminder(reminderId) ?: return@launch
            val groups = repo.getGroups()
            // A non-null location means this reminder was imported from the calendar;
            // null means it's an ordinary reminder with no editable location.
            val tripLocation = tripSync.tripLocation(reminderId)
            val tripBuffer = tripSync.tripBufferMin(reminderId)
            val kind = when (r.schedule) {
                is Schedule.OneShot -> ScheduleKind.ONESHOT
                is Schedule.Daily -> ScheduleKind.DAILY
                is Schedule.Weekly -> ScheduleKind.WEEKLY
                is Schedule.Monthly -> ScheduleKind.MONTHLY
                is Schedule.IntervalFromLast -> ScheduleKind.INTERVAL
                is Schedule.OnUnlock -> ScheduleKind.ON_UNLOCK
            }
            _state.update {
                it.copy(
                    reminderId = r.id,
                    name = r.name,
                    groupId = r.groupId,
                    scheduleType = kind,
                    oneShotMs = (r.schedule as? Schedule.OneShot)?.atEpochMs ?: it.oneShotMs,
                    dailyTimesMinutes = (r.schedule as? Schedule.Daily)?.timesOfDayMinutes ?: it.dailyTimesMinutes,
                    weeklyDays = (r.schedule as? Schedule.Weekly)?.daysOfWeek ?: it.weeklyDays,
                    weeklyTimesMinutes = (r.schedule as? Schedule.Weekly)?.timesOfDayMinutes ?: it.weeklyTimesMinutes,
                    monthlyDay = (r.schedule as? Schedule.Monthly)?.dayOfMonth ?: it.monthlyDay,
                    monthlyTimeMinutes = (r.schedule as? Schedule.Monthly)?.timeOfDayMinutes ?: it.monthlyTimeMinutes,
                    intervalHours = ((r.schedule as? Schedule.IntervalFromLast)?.intervalMs ?: (it.intervalHours * 3_600_000L)).let { (it / 3_600_000L).toInt().coerceAtLeast(1) },
                    intervalStartMs = (r.schedule as? Schedule.IntervalFromLast)?.startAtMs,
                    isCalendarReminder = tripLocation != null,
                    location = tripLocation.orEmpty(),
                    bufferMin = tripBuffer ?: DEFAULT_TRIP_BUFFER_MIN,
                    groups = groups
                )
            }
        }
    }

    fun updateName(s: String) = _state.update { it.copy(name = s) }
    fun updateLocation(s: String) = _state.update { it.copy(location = s) }
    fun updateBufferMin(min: Int) =
        _state.update { it.copy(bufferMin = min.coerceIn(MIN_TRIP_BUFFER_MIN, MAX_TRIP_BUFFER_MIN)) }
    fun updateGroup(id: Long) = _state.update { it.copy(groupId = id) }
    fun updateKind(k: ScheduleKind) = _state.update { it.copy(scheduleType = k) }
    fun updateOneShot(ms: Long) = _state.update { it.copy(oneShotMs = ms) }
    fun updateDailyTimes(times: List<Int>) = _state.update { it.copy(dailyTimesMinutes = times) }
    fun updateWeeklyDays(d: Set<DayOfWeek>) = _state.update { it.copy(weeklyDays = d) }
    fun updateWeeklyTimes(t: List<Int>) = _state.update { it.copy(weeklyTimesMinutes = t) }
    fun updateMonthlyDay(d: Int) = _state.update { it.copy(monthlyDay = d) }
    fun updateMonthlyTime(t: Int) = _state.update { it.copy(monthlyTimeMinutes = t) }
    fun updateIntervalHours(h: Int) = _state.update { it.copy(intervalHours = h) }
    fun updateIntervalStart(ms: Long?) = _state.update { it.copy(intervalStartMs = ms) }

    fun updateNl(input: String) {
        _state.update {
            it.copy(
                nlInput = input,
                nlError = null,
            )
        }
        scheduleDeepSeek(input)
    }

    private fun scheduleDeepSeek(input: String) {
        aiDebounceJob?.cancel()
        if (input.trim().isEmpty()) return
        aiDebounceJob = viewModelScope.launch {
            delay(AI_DEBOUNCE_MS)
            val groups = _state.value.groups
            if (groups.isEmpty()) return@launch
            if (!deepSeekParser.isConfigured()) return@launch
            _state.update { it.copy(nlThinking = true) }
            val result = deepSeekParser.parse(input, groups)
            // Drop result if the user has typed more since we sent the request.
            if (_state.value.nlInput != input) return@launch
            when (result) {
                is DeepSeekParseResult.Ok -> applyDeepSeek(result)
                is DeepSeekParseResult.NotConfigured -> _state.update { it.copy(nlThinking = false) }
                is DeepSeekParseResult.Failed -> _state.update {
                    it.copy(nlThinking = false, nlError = result.message)
                }
            }
        }
    }

    private fun applyDeepSeek(r: DeepSeekParseResult.Ok) {
        _state.update { st ->
            // Never route a voice reminder into the calendar-import group (seedKey "trips",
            // shown as Appointments / Termine); it is populated from the device calendar.
            val newGroupId = r.groupHint?.let { hint ->
                st.groups.firstOrNull { it.name.equals(hint, ignoreCase = true) && it.seedKey != "trips" }?.id
            } ?: st.groupId
            val st2 = st.copy(
                groupId = newGroupId,
                name = r.name?.takeIf { it.isNotBlank() } ?: st.name,
                nlThinking = false,
                nlError = null,
            )
            applySchedule(st2, r.schedule)
        }
    }

    private fun applySchedule(st: EditState, s: Schedule?): EditState = when (s) {
        null -> st
        is Schedule.OneShot -> st.copy(scheduleType = ScheduleKind.ONESHOT, oneShotMs = s.atEpochMs)
        is Schedule.Daily -> st.copy(scheduleType = ScheduleKind.DAILY, dailyTimesMinutes = s.timesOfDayMinutes)
        is Schedule.Weekly -> st.copy(
            scheduleType = ScheduleKind.WEEKLY,
            weeklyDays = s.daysOfWeek,
            weeklyTimesMinutes = s.timesOfDayMinutes
        )
        is Schedule.Monthly -> st.copy(
            scheduleType = ScheduleKind.MONTHLY,
            monthlyDay = s.dayOfMonth,
            monthlyTimeMinutes = s.timeOfDayMinutes
        )
        is Schedule.IntervalFromLast -> st.copy(
            scheduleType = ScheduleKind.INTERVAL,
            intervalHours = (s.intervalMs / 3_600_000L).toInt().coerceAtLeast(1),
            intervalStartMs = s.startAtMs
        )
        is Schedule.OnUnlock -> st.copy(scheduleType = ScheduleKind.ON_UNLOCK)
    }

    /**
     * Deletes the reminder being edited (no-op for a new, unsaved one). Returns
     * the deleted row so the caller can offer an undo, or null when there was
     * nothing to delete.
     */
    suspend fun delete(): app.nock.android.domain.model.Reminder? {
        val id = _state.value.reminderId
        if (id == 0L) return null
        // Snapshot before deleting so undo can re-insert the exact same row.
        val snapshot = repo.getReminder(id)
        // Cancel-then-delete as one mutex-guarded step so a concurrent alarm fire
        // can't re-arm the reminder between the cancel and the delete.
        engine.deleteReminderAndCancel(id)
        return snapshot
    }

    suspend fun save() {
        val s = _state.value
        val schedule = buildSchedule(s)
        val now = System.currentTimeMillis()
        val nextFire = schedule.nextFireFrom(now, null)
        val existing = if (s.reminderId != 0L) repo.getReminder(s.reminderId) else null
        val name = s.name.ifBlank { ctx.getString(R.string.default_reminder_name) }
        // Persist + (re-)arm as one mutex-guarded step. Doing saveReminder outside
        // the engine lock (as before) let a concurrent alarm fire observe the new
        // trigger against the old escalation row, or slip between the cancel and the
        // re-arm. saveReminderAndArm closes that window.
        engine.saveReminderAndArm(
            id = s.reminderId,
            groupId = s.groupId,
            name = name,
            schedule = schedule,
            nextFireAt = nextFire,
            lastCompletedAt = existing?.lastCompletedAt,
            createdAt = existing?.createdAt ?: now,
        )
        recordHistory(existing, name, schedule, nextFire, s.groupId, s.groups)
        // For a calendar-imported reminder, persist an edited buffer and location.
        // The buffer re-arms locally (no network). A changed location needs a fresh
        // traffic-aware leave-by, so kick the recompute off the save path rather than
        // blocking the screen from closing — it re-arms using the just-saved buffer.
        if (s.isCalendarReminder && s.reminderId != 0L) {
            tripSync.setTripBufferMin(s.reminderId, s.bufferMin)
            val locationChanged = tripSync.setTripLocation(s.reminderId, s.location.trim())
            if (locationChanged) viewModelScope.launch { tripSync.recompute(s.reminderId) }
        }
    }

    private fun recordHistory(
        existing: app.nock.android.domain.model.Reminder?,
        name: String,
        schedule: Schedule,
        nextFire: Long?,
        groupId: Long,
        groups: List<Group>,
    ) {
        val groupName = groups.firstOrNull { it.id == groupId }?.name
        if (existing == null) {
            history.created(name, groupName, schedule, nextFire)
            return
        }
        val changes = buildList {
            if (existing.name != name) add("name \"${existing.name}\" → \"$name\"")
            if (existing.groupId != groupId) {
                val oldGroup = groups.firstOrNull { it.id == existing.groupId }?.name
                    ?: existing.groupId.toString()
                add("group ${oldGroup} → ${groupName ?: groupId}")
            }
            if (existing.schedule != schedule) {
                add("schedule ${history.describeSchedule(existing.schedule)} → ${history.describeSchedule(schedule)}")
            }
        }
        history.modified(name, changes)
    }

    private fun buildSchedule(s: EditState): Schedule = when (s.scheduleType) {
        ScheduleKind.ONESHOT -> Schedule.OneShot(s.oneShotMs)
        ScheduleKind.DAILY -> Schedule.Daily(s.dailyTimesMinutes)
        ScheduleKind.WEEKLY -> Schedule.Weekly(s.weeklyDays, s.weeklyTimesMinutes)
        ScheduleKind.MONTHLY -> Schedule.Monthly(s.monthlyDay, s.monthlyTimeMinutes)
        ScheduleKind.INTERVAL -> Schedule.IntervalFromLast(s.intervalHours * 3_600_000L, s.intervalStartMs)
        ScheduleKind.ON_UNLOCK -> Schedule.OnUnlock(System.currentTimeMillis())
    }

    companion object {
        private const val AI_DEBOUNCE_MS = 800L

        // Per-reminder trip buffer bounds (minutes), shared with the editor's slider.
        const val MIN_TRIP_BUFFER_MIN = 5
        const val MAX_TRIP_BUFFER_MIN = 120
        const val DEFAULT_TRIP_BUFFER_MIN = 30
    }
}
