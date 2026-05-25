package app.nock.android.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.voice.PendingVoiceProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupSection(val group: Group, val reminders: List<Reminder>)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val groupDao: GroupDao,
    private val settings: SettingsRepository,
    private val pendingProcessor: PendingVoiceProcessor,
) : ViewModel() {

    val sections: StateFlow<List<GroupSection>> = combine(
        repo.observeGroups(),
        repo.observeReminders()
    ) { groups, reminders ->
        val byGroup = reminders.groupBy { it.groupId }
        groups.map { g -> GroupSection(g, byGroup[g.id].orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<PendingVoiceReminderEntity>> = pendingProcessor.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteReminder(r: Reminder) {
        viewModelScope.launch {
            engine.cancelActive(r.id)
            repo.deleteReminder(r)
        }
    }

    fun retryPending(p: PendingVoiceReminderEntity) {
        pendingProcessor.kick(p.id)
    }

    fun deletePending(p: PendingVoiceReminderEntity) {
        viewModelScope.launch { pendingProcessor.delete(p.id) }
    }

    fun pauseGroup(g: Group, durationMs: Long?) {
        viewModelScope.launch {
            val until = if (durationMs == null) Long.MAX_VALUE
            else System.currentTimeMillis() + durationMs
            repo.setGroupPause(g.id, until)
        }
    }

    fun unpauseGroup(g: Group) {
        viewModelScope.launch {
            repo.setGroupPause(g.id, null)
        }
    }

    fun saveGroup(g: Group, sortIndex: Int = 0) {
        viewModelScope.launch { repo.upsertGroup(g, sortIndex) }
    }
}
