package app.nock.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.data.SeedGroupLocaleSync
import app.nock.android.data.SettingsRepository
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.sync.DriveSyncClient
import app.nock.android.sync.SyncOutcome
import app.nock.android.history.AlarmHistoryLogger
import app.nock.android.telegram.TelegramSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val chain: EscalationChain? = null,
    val telegramToken: String = "",
    val telegramChat: String = "",
    val driveEmail: String? = null,
    val driveLastSyncMs: Long? = null,
    val telegramStatus: String? = null,
    val driveStatus: String? = null,
    val groups: List<Group> = emptyList(),
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = SettingsRepository.DEFAULT_DEEPSEEK_MODEL,
    val deepSeekBaseUrl: String = SettingsRepository.DEFAULT_DEEPSEEK_BASE_URL,
    val deepSeekContext: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val repo: NockRepository,
    private val telegram: TelegramSender,
    private val drive: DriveSyncClient,
    private val engine: EscalationEngine,
    private val seedGroupLocaleSync: SeedGroupLocaleSync,
    private val alarmHistory: AlarmHistoryLogger,
) : ViewModel() {

    fun syncSeedGroupNames() {
        viewModelScope.launch { seedGroupLocaleSync.sync() }
    }

    fun alarmHistoryDump(): String = alarmHistory.dump()
    fun clearAlarmHistory() = alarmHistory.clear()

    private val statusFlow = MutableStateFlow(Pair<String?, String?>(null, null))

    val state: StateFlow<SettingsState> = combine(
        settings.observeAll(),
        repo.observeGroups(),
        statusFlow
    ) { kv, groups, status ->
        val chain = (kv[SettingsRepository.KEY_STAGE_CHAIN])?.let {
            runCatching { app.nock.android.data.json.ChainJson.decode(it) }.getOrNull()
        } ?: app.nock.android.domain.model.DefaultChain.CHAIN
        SettingsState(
            chain = chain,
            telegramToken = kv[SettingsRepository.KEY_TELEGRAM_TOKEN].orEmpty(),
            telegramChat = kv[SettingsRepository.KEY_TELEGRAM_CHAT].orEmpty(),
            driveEmail = kv[SettingsRepository.KEY_DRIVE_ACCOUNT],
            driveLastSyncMs = kv[SettingsRepository.KEY_DRIVE_LAST_SYNC_MS]?.toLongOrNull(),
            telegramStatus = status.first,
            driveStatus = status.second,
            groups = groups,
            deepSeekApiKey = kv[SettingsRepository.KEY_DEEPSEEK_API_KEY].orEmpty(),
            deepSeekModel = kv[SettingsRepository.KEY_DEEPSEEK_MODEL]?.takeIf { it.isNotBlank() }
                ?: SettingsRepository.DEFAULT_DEEPSEEK_MODEL,
            deepSeekBaseUrl = kv[SettingsRepository.KEY_DEEPSEEK_BASE_URL]?.takeIf { it.isNotBlank() }
                ?: SettingsRepository.DEFAULT_DEEPSEEK_BASE_URL,
            deepSeekContext = kv[SettingsRepository.KEY_DEEPSEEK_CONTEXT].orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setChain(chain: EscalationChain) {
        viewModelScope.launch { settings.setStageChain(chain) }
    }

    fun setTelegram(token: String, chat: String) {
        viewModelScope.launch {
            settings.set(SettingsRepository.KEY_TELEGRAM_TOKEN, token)
            settings.set(SettingsRepository.KEY_TELEGRAM_CHAT, chat)
        }
    }

    fun testTelegram() {
        viewModelScope.launch {
            val token = settings.get(SettingsRepository.KEY_TELEGRAM_TOKEN).orEmpty()
            val chat = settings.get(SettingsRepository.KEY_TELEGRAM_CHAT).orEmpty()
            val res = telegram.sendRaw(token, chat, ctx.getString(R.string.telegram_test_message), silent = false)
            val msg = if (res.ok) ctx.getString(R.string.status_telegram_ok)
            else ctx.getString(R.string.status_telegram_error, res.errorDescription.orEmpty())
            statusFlow.value = (msg to statusFlow.value.second)
        }
    }

    fun setDriveEmail(email: String?) {
        viewModelScope.launch {
            if (email == null) settings.clear(SettingsRepository.KEY_DRIVE_ACCOUNT)
            else settings.set(SettingsRepository.KEY_DRIVE_ACCOUNT, email)
        }
    }

    fun setDriveStatus(message: String?) {
        statusFlow.value = (statusFlow.value.first to message)
    }

    fun syncPush() = runDrive { drive.pushSnapshot() }
    fun syncPull() = runDrive { drive.pullIfNewer() }

    private fun runDrive(block: suspend () -> SyncOutcome) {
        viewModelScope.launch {
            val r = block()
            val msg = when (r) {
                is SyncOutcome.Ok -> ctx.getString(R.string.status_drive_ok)
                is SyncOutcome.NotSignedIn -> ctx.getString(R.string.status_drive_sign_in_required)
                is SyncOutcome.Error -> ctx.getString(R.string.status_drive_error, r.message)
            }
            statusFlow.value = (statusFlow.value.first to msg)
        }
    }

    fun updateGroup(g: Group, sortIndex: Int = 0) {
        viewModelScope.launch { repo.upsertGroup(g, sortIndex) }
    }

    fun deleteGroup(g: Group) {
        viewModelScope.launch { repo.deleteGroup(g) }
    }

    fun setDeepSeek(apiKey: String, model: String, baseUrl: String, context: String) {
        viewModelScope.launch {
            settings.set(SettingsRepository.KEY_DEEPSEEK_API_KEY, apiKey)
            settings.set(SettingsRepository.KEY_DEEPSEEK_MODEL, model.ifBlank { SettingsRepository.DEFAULT_DEEPSEEK_MODEL })
            settings.set(SettingsRepository.KEY_DEEPSEEK_BASE_URL, baseUrl.ifBlank { SettingsRepository.DEFAULT_DEEPSEEK_BASE_URL })
            settings.set(SettingsRepository.KEY_DEEPSEEK_CONTEXT, context.trim())
        }
    }
}
