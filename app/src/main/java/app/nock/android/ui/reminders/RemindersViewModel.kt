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
        // Cancel + delete atomically so a concurrent alarm fire can't re-arm the
        // reminder between the two steps.
        viewModelScope.launch { engine.deleteReminderAndCancel(r.id) }
    }

    fun retryPending(p: PendingVoiceReminderEntity) {
        pendingProcessor.kick(p.id)
    }

    fun deletePending(p: PendingVoiceReminderEntity) {
        viewModelScope.launch { pendingProcessor.delete(p.id) }
    }

    fun pauseGroupUntil(g: Group, untilMs: Long) {
        viewModelScope.launch {
            repo.setGroupPause(g.id, untilMs)
        }
    }

    fun unpauseGroup(g: Group) {
        viewModelScope.launch {
            repo.setGroupPause(g.id, null)
            // Re-arm the group's reminders immediately. While paused, an armed
            // escalation pushes its next fire out to the pause end; lifting the
            // pause early must restore each reminder's real (earlier) schedule
            // rather than wait for that deferred time. Also revives any reminder
            // saved/completed during the pause, which got no escalation then.
            engine.rearmGroup(g.id)
        }
    }

    fun saveGroup(g: Group, sortIndex: Int = 0) {
        viewModelScope.launch { repo.upsertGroup(g, sortIndex) }
    }
}
