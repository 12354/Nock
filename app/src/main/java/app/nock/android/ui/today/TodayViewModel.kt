package app.nock.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.data.SeedData
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.trip.TripAlarmInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ActiveEscalationInfo(
    val escalationId: Long,
    val chain: EscalationChain,
    val nextStageIndex: Int,
    val nextFireAtMs: Long,
    /**
     * True once the main alarm time is reached (departure minus buffer for
     * trips). Earlier quiet stages can already have fired while the reminder
     * still appears in the upcoming list.
     */
    val hasStarted: Boolean,
)

data class TodayItem(
    val reminder: Reminder,
    val group: Group,
    val active: ActiveEscalationInfo?,
    val trip: TripAlarmInfo? = null,
) {
    val displayTimeMs: Long? get() = trip?.alarmAtMs ?: reminder.nextFireAt

    /** Currently escalating *and* the chain has actually started firing. */
    val isActive: Boolean get() = active != null && active.hasStarted
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val groupDao: GroupDao,
    private val activeDao: ActiveEscalationDao,
    private val settings: SettingsRepository,
    private val seed: SeedData,
    private val tripDao: CalendarTripDao,
) : ViewModel() {

    init {
        viewModelScope.launch {
            if (groupDao.getAll().isEmpty()) {
                groupDao.upsertAll(seed.toEntities())
            }
        }
    }

    // Re-emit on a wall-clock cadence so time-derived state (a reminder's
    // trigger time passing → hasStarted flipping to "firing now") updates on its
    // own. Without this the list only recomputes on a DB write, so the live
    // "active now" card could lag behind the actual trigger time. Only runs
    // while the screen is subscribed (WhileSubscribed below).
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MS)
        }
    }

    val items: StateFlow<List<TodayItem>> = combine(
        repo.observeReminders(),
        repo.observeGroups(),
        activeDao.observeAll(),
        tripDao.observeAll(),
        ticker
    ) { reminders, groups, active, trips, _ ->
        val now = System.currentTimeMillis()
        val byId = groups.associateBy { it.id }
        val activeByReminder = active.associateBy { it.reminderId }
        val tripsByReminder = trips.associateBy { it.reminderId }
        reminders.mapNotNull { r ->
            val g = byId[r.groupId] ?: return@mapNotNull null
            val trip = tripsByReminder[r.id]?.let { t ->
                r.nextFireAt?.let { TripAlarmInfo(it, t.lastTravelMs, t.bufferMs) }
            }
            val a = activeByReminder[r.id]?.let { ent ->
                val chain = runCatching { ChainJson.decode(ent.chainSnapshotJson) }.getOrNull()
                if (chain != null) ActiveEscalationInfo(
                    escalationId = ent.id,
                    chain = chain,
                    nextStageIndex = ent.nextStageIndex.coerceIn(0, chain.lastIndex),
                    nextFireAtMs = ent.nextFireAtMs,
                    hasStarted = now >= (trip?.alarmAtMs ?: ent.startedAtMs),
                ) else null
            }
            TodayItem(r, g, a, trip)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pendingJobs = mutableMapOf<Long, Job>()
    private val _pendingDoneIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDoneIds: StateFlow<Set<Long>> = _pendingDoneIds.asStateFlow()

    // Mark as pending and commit after UNDO_WINDOW_MS unless undoDone cancels it.
    // The Today UI hides pending items in the meantime so no replacement card
    // can pop into the active slot and steal a stray tap.
    fun markDone(reminderId: Long) {
        if (_pendingDoneIds.value.contains(reminderId)) return
        _pendingDoneIds.update { it + reminderId }
        pendingJobs[reminderId] = viewModelScope.launch {
            try {
                delay(UNDO_WINDOW_MS)
            } catch (e: CancellationException) {
                _pendingDoneIds.update { it - reminderId }
                pendingJobs.remove(reminderId)
                throw e
            }
            withContext(NonCancellable) {
                commitDone(reminderId)
                _pendingDoneIds.update { it - reminderId }
                pendingJobs.remove(reminderId)
            }
        }
    }

    fun undoDone(reminderId: Long) {
        pendingJobs[reminderId]?.cancel()
    }

    private suspend fun commitDone(reminderId: Long) {
        // Whether or not an escalation is live, completion runs as one
        // mutex-guarded step inside the engine. The old inline version did the
        // "no active escalation" advance (getReminder -> updateFireState ->
        // startEscalationAt) as separate calls outside the lock, which could race
        // a concurrent edit/delete of the same reminder during the 5s undo window.
        engine.completeReminder(reminderId)
    }

    fun deleteReminder(r: Reminder) {
        // Cancel + delete atomically so a concurrent alarm fire can't re-arm the
        // reminder between the two steps. Mirrors RemindersViewModel.deleteReminder;
        // the screen surfaces an undo snackbar that calls back into restore.
        viewModelScope.launch { engine.deleteReminderAndCancel(r.id) }
    }

    fun snooze(reminderId: Long) {
        viewModelScope.launch {
            val active = activeDao.getByReminderId(reminderId) ?: return@launch
            engine.snooze(active.id)
        }
    }

    companion object {
        const val UNDO_WINDOW_MS: Long = 5_000L
        private const val TICK_INTERVAL_MS: Long = 30_000L
    }
}
