package app.nock.android.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.dao.PendingVoiceReminderDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.PendingTelegramDeletionEntity
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.entity.SettingsEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.data.json.ScheduleJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A reminder row with the checks the normal UI silently applies, made explicit. */
data class ReminderRow(
    val entity: ReminderEntity,
    /** False when scheduleJson can't be decoded — these are dropped by the live list's mapNotNull. */
    val scheduleDecodes: Boolean,
    /** False when groupId points at no group — invisible in the grouped list, but still fires. */
    val groupExists: Boolean,
) {
    val hidden: Boolean get() = !scheduleDecodes || !groupExists
}

/** An active-escalation row plus the integrity checks the firing path applies. */
data class ActiveRow(
    val entity: ActiveEscalationEntity,
    val chainDecodes: Boolean,
    /** False when the backing reminder is gone — an orphan escalation that the OS may still fire. */
    val reminderExists: Boolean,
) {
    val orphan: Boolean get() = !reminderExists || !chainDecodes
}

data class DebugSnapshot(
    val groups: List<GroupEntity> = emptyList(),
    val reminders: List<ReminderRow> = emptyList(),
    val active: List<ActiveRow> = emptyList(),
    val settings: List<SettingsEntity> = emptyList(),
    val pendingVoice: List<PendingVoiceReminderEntity> = emptyList(),
    val pendingTelegram: List<PendingTelegramDeletionEntity> = emptyList(),
    val loaded: Boolean = false,
) {
    val hiddenReminders: List<ReminderRow> get() = reminders.filter { it.hidden }
    val orphanEscalations: List<ActiveRow> get() = active.filter { it.orphan }
    val anomalyCount: Int get() = hiddenReminders.size + orphanEscalations.size
}

/**
 * Reads every table at the raw-entity level — no domain mappers, no group join, no
 * `mapNotNull` — so nothing the normal screens filter out can stay hidden. Cross-
 * references the rows to flag exactly what the live UI would drop: reminders whose
 * schedule won't decode or whose group is missing, and active escalations whose
 * reminder is gone.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val reminderDao: ReminderDao,
    private val groupDao: GroupDao,
    private val activeDao: ActiveEscalationDao,
    private val settingsDao: SettingsDao,
    private val pendingVoiceDao: PendingVoiceReminderDao,
    private val pendingTelegramDao: PendingTelegramDeletionDao,
) : ViewModel() {

    // pending_telegram_deletions has no observable query; bump this to force a re-read.
    private val refresh = MutableStateFlow(0L)

    private data class Raw(
        val reminders: List<ReminderEntity>,
        val groups: List<GroupEntity>,
        val active: List<ActiveEscalationEntity>,
        val settings: List<SettingsEntity>,
        val voice: List<PendingVoiceReminderEntity>,
    )

    val snapshot: StateFlow<DebugSnapshot> = combine(
        reminderDao.observeAll(),
        groupDao.observeAll(),
        activeDao.observeAll(),
        settingsDao.observeAll(),
        pendingVoiceDao.observeAll(),
    ) { reminders, groups, active, settings, voice ->
        Raw(reminders, groups, active, settings, voice)
    }.combine(refresh) { raw, _ ->
        val groupIds = raw.groups.mapTo(HashSet()) { it.id }
        val reminderIds = raw.reminders.mapTo(HashSet()) { it.id }
        DebugSnapshot(
            groups = raw.groups,
            reminders = raw.reminders.map { e ->
                ReminderRow(
                    entity = e,
                    scheduleDecodes = runCatching { ScheduleJson.decode(e.scheduleJson) }.isSuccess,
                    groupExists = e.groupId in groupIds,
                )
            },
            active = raw.active.map { e ->
                ActiveRow(
                    entity = e,
                    chainDecodes = runCatching { ChainJson.decode(e.chainSnapshotJson) }.isSuccess,
                    reminderExists = e.reminderId in reminderIds,
                )
            },
            settings = raw.settings,
            pendingVoice = raw.voice,
            pendingTelegram = pendingTelegramDao.getAll(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugSnapshot())

    fun refresh() {
        refresh.value = System.currentTimeMillis()
    }

    /** A flat, copy-pasteable text dump of the whole snapshot for sharing in bug reports. */
    fun buildDump(s: DebugSnapshot): String = buildString {
        appendLine("Nock raw data dump — ${ts(System.currentTimeMillis())}")
        appendLine("anomalies: ${s.anomalyCount} (hidden reminders=${s.hiddenReminders.size}, orphan escalations=${s.orphanEscalations.size})")
        appendLine()
        appendLine("== GROUPS (${s.groups.size}) ==")
        s.groups.forEach { appendLine(it.toString()) }
        appendLine()
        appendLine("== REMINDERS (${s.reminders.size}) ==")
        s.reminders.forEach { r ->
            val flags = buildList {
                if (!r.groupExists) add("ORPHAN: groupId=${r.entity.groupId} has no group")
                if (!r.scheduleDecodes) add("CORRUPT schedule JSON")
            }
            appendLine(r.entity.toString())
            if (flags.isNotEmpty()) appendLine("   !! ${flags.joinToString("; ")}")
        }
        appendLine()
        appendLine("== ACTIVE ESCALATIONS (${s.active.size}) ==")
        s.active.forEach { a ->
            val flags = buildList {
                if (!a.reminderExists) add("ORPHAN: reminderId=${a.entity.reminderId} has no reminder")
                if (!a.chainDecodes) add("CORRUPT chain JSON")
            }
            appendLine(a.entity.toString())
            if (flags.isNotEmpty()) appendLine("   !! ${flags.joinToString("; ")}")
        }
        appendLine()
        appendLine("== SETTINGS (${s.settings.size}) ==")
        s.settings.forEach { appendLine("${it.key} = ${it.value}") }
        appendLine()
        appendLine("== PENDING VOICE (${s.pendingVoice.size}) ==")
        s.pendingVoice.forEach { appendLine(it.toString()) }
        appendLine()
        appendLine("== PENDING TELEGRAM DELETIONS (${s.pendingTelegram.size}) ==")
        s.pendingTelegram.forEach { appendLine(it.toString()) }
    }

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun ts(ms: Long): String =
            FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}
