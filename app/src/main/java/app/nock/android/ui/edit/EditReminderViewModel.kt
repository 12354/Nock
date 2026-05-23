package app.nock.android.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import app.nock.android.parse.NaturalLanguageParser
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val groups: List<Group> = emptyList(),
    val nlInput: String = "",
    val nlPreview: String = "",
)

enum class ScheduleKind { ONESHOT, DAILY, WEEKLY, MONTHLY, INTERVAL }

@HiltViewModel
class EditReminderViewModel @Inject constructor(
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    savedState: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

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
            val kind = when (r.schedule) {
                is Schedule.OneShot -> ScheduleKind.ONESHOT
                is Schedule.Daily -> ScheduleKind.DAILY
                is Schedule.Weekly -> ScheduleKind.WEEKLY
                is Schedule.Monthly -> ScheduleKind.MONTHLY
                is Schedule.IntervalFromLast -> ScheduleKind.INTERVAL
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
                    intervalHours = ((r.schedule as? Schedule.IntervalFromLast)?.intervalMs ?: (it.intervalHours * 3_600_000L)).let { (it / 3_600_000L).toInt() },
                    groups = groups
                )
            }
        }
    }

    fun updateName(s: String) = _state.update { it.copy(name = s) }
    fun updateGroup(id: Long) = _state.update { it.copy(groupId = id) }
    fun updateKind(k: ScheduleKind) = _state.update { it.copy(scheduleType = k) }
    fun updateOneShot(ms: Long) = _state.update { it.copy(oneShotMs = ms) }
    fun updateDailyTimes(times: List<Int>) = _state.update { it.copy(dailyTimesMinutes = times) }
    fun updateWeeklyDays(d: Set<DayOfWeek>) = _state.update { it.copy(weeklyDays = d) }
    fun updateWeeklyTimes(t: List<Int>) = _state.update { it.copy(weeklyTimesMinutes = t) }
    fun updateMonthlyDay(d: Int) = _state.update { it.copy(monthlyDay = d) }
    fun updateMonthlyTime(t: Int) = _state.update { it.copy(monthlyTimeMinutes = t) }
    fun updateIntervalHours(h: Int) = _state.update { it.copy(intervalHours = h) }

    fun updateNl(input: String) {
        val parsed = NaturalLanguageParser.parse(input)
        _state.update { st ->
            val newGroupId = parsed.groupHint?.let { hint ->
                st.groups.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.id
            } ?: st.groupId
            val st2 = st.copy(
                nlInput = input,
                nlPreview = parsed.schedule?.let { describeSchedule(it) } ?: "",
                groupId = newGroupId,
                name = parsed.name.takeIf { it.isNotBlank() } ?: st.name
            )
            applySchedule(st2, parsed.schedule)
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
            intervalHours = (s.intervalMs / 3_600_000L).toInt().coerceAtLeast(1)
        )
    }

    private fun describeSchedule(s: Schedule): String = when (s) {
        is Schedule.OneShot -> "one-shot"
        is Schedule.Daily -> "daily at ${s.timesOfDayMinutes.joinToString { "%02d:%02d".format(it/60, it%60) }}"
        is Schedule.Weekly -> "weekly on ${s.daysOfWeek.joinToString { it.name.take(3) }}"
        is Schedule.Monthly -> "monthly day ${s.dayOfMonth}"
        is Schedule.IntervalFromLast -> "every ${s.intervalMs / 3_600_000L}h"
    }

    suspend fun save() {
        val s = _state.value
        val schedule = buildSchedule(s)
        val now = System.currentTimeMillis()
        val nextFire = schedule.nextFireFrom(now, null)
        val existing = if (s.reminderId != 0L) repo.getReminder(s.reminderId) else null
        val id = repo.saveReminder(
            id = s.reminderId,
            groupId = s.groupId,
            name = s.name.ifBlank { "Reminder" },
            schedule = schedule,
            nextFireAt = nextFire,
            lastCompletedAt = existing?.lastCompletedAt,
            createdAt = existing?.createdAt ?: now
        )
        engine.cancelActive(id)
        val r = repo.getReminder(id) ?: return
        if (nextFire != null) engine.startEscalationAt(r, nextFire)
    }

    private fun buildSchedule(s: EditState): Schedule = when (s.scheduleType) {
        ScheduleKind.ONESHOT -> Schedule.OneShot(s.oneShotMs)
        ScheduleKind.DAILY -> Schedule.Daily(s.dailyTimesMinutes)
        ScheduleKind.WEEKLY -> Schedule.Weekly(s.weeklyDays, s.weeklyTimesMinutes)
        ScheduleKind.MONTHLY -> Schedule.Monthly(s.monthlyDay, s.monthlyTimeMinutes)
        ScheduleKind.INTERVAL -> Schedule.IntervalFromLast(s.intervalHours * 3_600_000L)
    }
}
