package app.nock.android.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.domain.model.DefaultChain
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.ui.components.GroupColorChoices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupEditorState(
    val id: Long = 0,
    val name: String = "",
    val color: Int = GroupColorChoices.first().toInt(),
    val icon: String = "Label",
    val pausedUntilMs: Long? = null,
    val overrideChain: EscalationChain? = null,
    val telegramSilentMirror: Boolean = false,
    val seedKey: String? = null,
    val reminderCount: Int = 0,
    val defaultChain: EscalationChain = DefaultChain.CHAIN,
    val loaded: Boolean = false,
) {
    val isPaused: Boolean get() = pausedUntilMs != null && pausedUntilMs > System.currentTimeMillis()
}

@HiltViewModel
class GroupEditorViewModel @Inject constructor(
    private val repo: NockRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupEditorState())
    val state: StateFlow<GroupEditorState> = _state.asStateFlow()

    fun load(groupId: Long) {
        viewModelScope.launch {
            val defaultChain = settings.getStageChain()
            if (groupId == 0L) {
                _state.update {
                    it.copy(
                        id = 0,
                        name = "",
                        color = GroupColorChoices.first().toInt(),
                        icon = "Label",
                        pausedUntilMs = null,
                        overrideChain = null,
                        telegramSilentMirror = false,
                        seedKey = null,
                        reminderCount = 0,
                        defaultChain = defaultChain,
                        loaded = true,
                    )
                }
                return@launch
            }
            val g = repo.getGroup(groupId) ?: return@launch
            val count = repo.getAllReminders().count { it.groupId == groupId }
            _state.update {
                it.copy(
                    id = g.id,
                    name = g.name,
                    color = g.color,
                    icon = g.icon,
                    pausedUntilMs = g.pausedUntilMs,
                    overrideChain = g.overrideChain,
                    telegramSilentMirror = g.telegramSilentMirror,
                    seedKey = g.seedKey,
                    reminderCount = count,
                    defaultChain = defaultChain,
                    loaded = true,
                )
            }
        }
    }

    fun updateName(s: String) = _state.update { it.copy(name = s) }
    fun updateColor(c: Int) = _state.update { it.copy(color = c) }
    fun updateIcon(i: String) = _state.update { it.copy(icon = i) }
    fun updateMirror(on: Boolean) = _state.update { it.copy(telegramSilentMirror = on) }

    fun setPauseUntil(untilMs: Long?) {
        _state.update { it.copy(pausedUntilMs = untilMs) }
    }

    fun toggleCustomChain(on: Boolean) {
        _state.update {
            it.copy(overrideChain = if (on) (it.overrideChain ?: it.defaultChain) else null)
        }
    }

    fun updateChain(chain: EscalationChain) = _state.update { it.copy(overrideChain = chain) }

    suspend fun save(): Long {
        val s = _state.value
        val group = Group(
            id = s.id,
            name = s.name.ifBlank { "Group" },
            color = s.color,
            icon = s.icon,
            overrideChain = s.overrideChain,
            pausedUntilMs = s.pausedUntilMs,
            telegramSilentMirror = s.telegramSilentMirror,
            seedKey = s.seedKey,
        )
        return repo.upsertGroup(group)
    }

    suspend fun delete() {
        val s = _state.value
        if (s.id == 0L) return
        repo.getGroup(s.id)?.let { repo.deleteGroup(it) }
    }
}
