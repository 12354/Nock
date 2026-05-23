package app.nock.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.data.SeedData
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodayItem(
    val reminder: Reminder,
    val group: Group,
    val isActive: Boolean
)

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
        val activeIds = active.map { it.reminderId }.toSet()
        reminders.mapNotNull { r ->
            val g = byId[r.groupId] ?: return@mapNotNull null
            TodayItem(r, g, r.id in activeIds)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markDone(reminderId: Long) {
        viewModelScope.launch {
            val active = activeDao.getByReminderId(reminderId)
            if (active != null) engine.done(active.id)
            else {
                val r = repo.getReminder(reminderId) ?: return@launch
                val now = System.currentTimeMillis()
                val next = r.schedule.nextFireFrom(now, now)
                repo.updateFireState(reminderId, next, now)
                engine.cancelActive(reminderId)
                if (next != null) {
                    engine.startEscalationAt(r.copy(lastCompletedAt = now, nextFireAt = next), next)
                }
            }
        }
    }
}
