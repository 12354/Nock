package app.nock.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.data.SeedData
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ActiveEscalationInfo(
    val escalationId: Long,
    val chain: EscalationChain,
    val currentStageIndex: Int,
    val nextFireAtMs: Long,
)

data class TodayItem(
    val reminder: Reminder,
    val group: Group,
    val active: ActiveEscalationInfo?,
) {
    val isActive: Boolean get() = active != null
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val groupDao: GroupDao,
    private val activeDao: ActiveEscalationDao,
    private val settings: SettingsRepository,
    private val seed: SeedData,
) : ViewModel() {

    init {
        viewModelScope.launch {
            if (groupDao.getAll().isEmpty()) {
                groupDao.upsertAll(seed.toEntities())
            }
        }
    }

    val items: StateFlow<List<TodayItem>> = combine(
        repo.observeReminders(),
        repo.observeGroups(),
        activeDao.observeAll()
    ) { reminders, groups, active ->
        val byId = groups.associateBy { it.id }
        val activeByReminder = active.associateBy { it.reminderId }
        reminders.mapNotNull { r ->
            val g = byId[r.groupId] ?: return@mapNotNull null
            val a = activeByReminder[r.id]?.let { ent ->
                val chain = runCatching { ChainJson.decode(ent.chainSnapshotJson) }.getOrNull()
                if (chain != null) ActiveEscalationInfo(
                    escalationId = ent.id,
                    chain = chain,
                    currentStageIndex = ent.nextStageIndex.coerceIn(0, chain.lastIndex),
                    nextFireAtMs = ent.nextFireAtMs,
                ) else null
            }
            TodayItem(r, g, a)
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
        val active = activeDao.getByReminderId(reminderId)
        if (active != null) engine.done(active.id)
        else {
            val r = repo.getReminder(reminderId) ?: return
            engine.cancelActive(reminderId)
            if (r.schedule.isOneTime) {
                repo.deleteReminder(r)
                return
            }
            val now = System.currentTimeMillis()
            val next = r.schedule.nextFireFrom(now, now)
            repo.updateFireState(reminderId, next, now)
            if (next != null) {
                engine.startEscalationAt(r.copy(lastCompletedAt = now, nextFireAt = next), next)
            }
        }
    }

    fun snooze(reminderId: Long) {
        viewModelScope.launch {
            val active = activeDao.getByReminderId(reminderId) ?: return@launch
            engine.snooze(active.id)
        }
    }

    companion object {
        const val UNDO_WINDOW_MS: Long = 5_000L
    }
}
