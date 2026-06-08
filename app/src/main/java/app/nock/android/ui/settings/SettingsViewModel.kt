package app.nock.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.data.SeedGroupLocaleSync
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.di.ApplicationScope
import app.nock.android.sync.DriveSyncClient
import app.nock.android.sync.SyncOutcome
import app.nock.android.history.AlarmHistoryLogger
import app.nock.android.telegram.TelegramSender
import app.nock.android.trip.CalendarInfo
import app.nock.android.trip.CalendarRepository
import app.nock.android.trip.TomTomClient
import app.nock.android.trip.TripSyncManager
import app.nock.android.domain.trip.TripDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
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
    val tripsEnabled: Boolean = false,
    val tomtomKey: String = "",
    val tripHomeAddress: String = "",
    val tripBufferMin: Int = (TripDefaults.BUFFER_MS / 60_000L).toInt(),
    val tripStatus: String? = null,
    val tripHasCalendarPermission: Boolean = false,
    val tripCalendars: List<CalendarInfo> = emptyList(),
    val tripSelectedCalendarIds: Set<Long> = emptySet(),
    // null = not set (default notification tone); "" = silent; else a sound URI.
    val preAlarmSoundUri: String? = null,
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
    private val activeDao: ActiveEscalationDao,
    private val trips: TripSyncManager,
    private val tomtom: TomTomClient,
    private val calendar: CalendarRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    fun syncSeedGroupNames() {
        viewModelScope.launch { seedGroupLocaleSync.sync() }
    }

    fun alarmHistoryDump(): String = alarmHistory.dump()
    fun clearAlarmHistory() = alarmHistory.clear()

    /**
     * The history log with a live snapshot of every current alarm and its
     * state appended — used by the Copy button so a pasted report shows both
     * what happened and what is scheduled right now.
     */
    suspend fun alarmHistoryWithCurrentState(): String {
        val now = System.currentTimeMillis()
        val groups = repo.getGroups().associateBy { it.id }
        val states = repo.getAllReminders().map { r ->
            val group = groups[r.groupId]
            val active = activeDao.getByReminderId(r.id)
            val activeState = active?.let { row ->
                val chain = runCatching { ChainJson.decode(row.chainSnapshotJson) }.getOrNull()
                val stageType = chain?.let { it.stage(row.nextStageIndex.coerceIn(0, it.lastIndex)).type }
                stageType?.let { AlarmHistoryLogger.ActiveState(it, row.nextFireAtMs) }
            }
            // The chain currently in flight (snapshotted when it started) if the
            // alarm is mid-escalation, else the chain it would walk next fire:
            // the group's override or the global default.
            val chain = active?.let { runCatching { ChainJson.decode(it.chainSnapshotJson) }.getOrNull() }
                ?: group?.let { repo.effectiveChain(it) }
                ?: settings.getStageChain()
            AlarmHistoryLogger.AlarmState(
                name = r.name,
                group = group?.name,
                schedule = r.schedule,
                nextFireAtMs = r.nextFireAt,
                lastCompletedAt = r.lastCompletedAt,
                active = activeState,
                chain = chain,
            )
        }
        return alarmHistory.dump() + alarmHistory.snapshot(states, now)
    }

    private val statusFlow = MutableStateFlow(Pair<String?, String?>(null, null))
    private val tripStatusFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsState> = combine(
        settings.observeAll(),
        repo.observeGroups(),
        statusFlow,
        tripStatusFlow
    ) { kv, groups, status, tripStatus ->
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
            tripsEnabled = kv[SettingsRepository.KEY_TRIPS_ENABLED]?.toBooleanStrictOrNull() == true,
            tomtomKey = kv[SettingsRepository.KEY_TOMTOM_KEY].orEmpty(),
            tripHomeAddress = kv[SettingsRepository.KEY_TRIP_HOME_ADDRESS].orEmpty(),
            tripBufferMin = kv[SettingsRepository.KEY_TRIP_BUFFER_MIN]?.toIntOrNull()
                ?: (TripDefaults.BUFFER_MS / 60_000L).toInt(),
            tripStatus = tripStatus,
            tripHasCalendarPermission = calendar.hasPermission(),
            tripCalendars = if (calendar.hasPermission()) calendar.listCalendars() else emptyList(),
            tripSelectedCalendarIds = kv[SettingsRepository.KEY_TRIP_CALENDAR_IDS]
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty(),
            preAlarmSoundUri = kv[SettingsRepository.KEY_PREALARM_SOUND],
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    // The global chain editor saves on every tweak; mark it dirty so we re-arm the
    // affected (no-override) groups once, when the user leaves the screen.
    @Volatile private var chainEdited = false

    fun setChain(chain: EscalationChain) {
        chainEdited = true
        viewModelScope.launch { settings.setStageChain(chain) }
    }

    /**
     * Apply a global-chain edit to already-armed reminders, called when the
     * Notifications screen is left. Runs on the application scope (not
     * viewModelScope, which is cancelled as the screen's ViewModel is cleared) so
     * the re-arm always completes. Re-persists the latest chain first so the
     * re-arm can't read a stale value if the final keystroke's write was still in
     * flight (or lost to the same cancellation).
     */
    fun applyChainEditsIfDirty() {
        if (!chainEdited) return
        chainEdited = false
        val chain = state.value.chain ?: return
        appScope.launch {
            settings.setStageChain(chain)
            engine.rearmDefaultChainGroups()
        }
    }

    /** Persist the chosen pre-alarm sound: null URI = silent, else the sound URI. */
    fun setPreAlarmSound(uri: String?) {
        viewModelScope.launch {
            settings.set(SettingsRepository.KEY_PREALARM_SOUND, uri ?: "")
        }
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
        viewModelScope.launch {
            // Cancel scheduled/ringing alarms for the group's reminders before the
            // FK cascade deletes them, otherwise their OS alarms are orphaned and
            // still fire for a reminder that no longer appears in any list.
            engine.cancelActiveForGroup(g.id)
            repo.deleteGroup(g)
        }
    }

    fun setTrips(enabled: Boolean, tomtomKey: String, homeAddress: String, bufferMin: Int) {
        appScope.launch {
            val prevHome = settings.get(SettingsRepository.KEY_TRIP_HOME_ADDRESS).orEmpty()
            settings.set(SettingsRepository.KEY_TRIPS_ENABLED, enabled.toString())
            settings.set(SettingsRepository.KEY_TOMTOM_KEY, tomtomKey.trim())
            settings.set(SettingsRepository.KEY_TRIP_HOME_ADDRESS, homeAddress.trim())
            settings.set(SettingsRepository.KEY_TRIP_BUFFER_MIN, bufferMin.coerceAtLeast(1).toString())
            // The cached home coordinates are only valid for the old address.
            if (homeAddress.trim() != prevHome) {
                settings.clear(SettingsRepository.KEY_TRIP_HOME_LAT)
                settings.clear(SettingsRepository.KEY_TRIP_HOME_LON)
            }
            runCatching { trips.syncNow() }
        }
    }

    fun setTripCalendars(ids: Set<Long>) {
        appScope.launch {
            settings.set(SettingsRepository.KEY_TRIP_CALENDAR_IDS, ids.joinToString(","))
            runCatching { trips.syncNow() }
        }
    }

    /** Called after the calendar permission is granted so trips import immediately. */
    fun onCalendarPermissionResult() {
        appScope.launch { runCatching { trips.syncNow() } }
        // Nudge the state flow so the calendar list / permission flag re-evaluate.
        tripStatusFlow.value = tripStatusFlow.value
    }

    /** Validates the TomTom key + home address by geocoding the home address. */
    fun testRoute() {
        viewModelScope.launch {
            val home = settings.get(SettingsRepository.KEY_TRIP_HOME_ADDRESS).orEmpty()
            val key = settings.get(SettingsRepository.KEY_TOMTOM_KEY).orEmpty()
            tripStatusFlow.value = when {
                key.isBlank() -> ctx.getString(R.string.trips_status_no_key)
                home.isBlank() -> ctx.getString(R.string.trips_status_no_home)
                else -> {
                    val geo = tomtom.geocode(home)
                    if (geo != null) ctx.getString(R.string.trips_status_ok)
                    else ctx.getString(R.string.trips_status_error)
                }
            }
        }
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
