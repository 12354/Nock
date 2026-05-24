package app.nock.android.data

import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.data.json.ScheduleJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NockRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val reminderDao: ReminderDao,
    private val activeDao: ActiveEscalationDao,
    private val settings: SettingsRepository
) {
    fun observeGroups(): Flow<List<Group>> = groupDao.observeAll().map { it.map(GroupEntity::toDomain) }
    fun observeReminders(): Flow<List<Reminder>> = reminderDao.observeAll().map { it.map(ReminderEntity::toDomain) }
    fun observeRemindersByGroup(groupId: Long): Flow<List<Reminder>> =
        reminderDao.observeByGroup(groupId).map { it.map(ReminderEntity::toDomain) }

    suspend fun getGroup(id: Long): Group? = groupDao.getById(id)?.toDomain()
    suspend fun getGroups(): List<Group> = groupDao.getAll().map(GroupEntity::toDomain)
    suspend fun getReminder(id: Long): Reminder? = reminderDao.getById(id)?.toDomain()
    suspend fun getAllReminders(): List<Reminder> = reminderDao.getAll().map(ReminderEntity::toDomain)

    suspend fun upsertGroup(g: Group, sortIndex: Int = 0): Long {
        val entity = GroupEntity(
            id = g.id,
            name = g.name,
            color = g.color,
            icon = g.icon,
            overrideChainJson = g.overrideChain?.let { ChainJson.encode(it) },
            overrideRepeatIntervalMs = g.overrideChain?.repeatIntervalMs,
            pausedUntilMs = g.pausedUntilMs,
            telegramSilentMirror = g.telegramSilentMirror,
            sortIndex = sortIndex,
            seedKey = g.seedKey
        )
        return groupDao.upsert(entity)
    }

    suspend fun deleteGroup(g: Group) {
        groupDao.getById(g.id)?.let { groupDao.delete(it) }
    }

    suspend fun setGroupPause(id: Long, untilMs: Long?) = groupDao.setPause(id, untilMs)

    suspend fun saveReminder(
        id: Long,
        groupId: Long,
        name: String,
        schedule: Schedule,
        nextFireAt: Long?,
        lastCompletedAt: Long?,
        createdAt: Long = System.currentTimeMillis()
    ): Long {
        val (type, json) = scheduleAsRow(schedule)
        val entity = ReminderEntity(
            id = id,
            groupId = groupId,
            name = name,
            scheduleType = type,
            scheduleJson = json,
            nextFireAt = nextFireAt,
            lastCompletedAt = lastCompletedAt,
            createdAt = createdAt
        )
        return reminderDao.insert(entity)
    }

    suspend fun deleteReminder(r: Reminder) {
        reminderDao.getById(r.id)?.let { reminderDao.delete(it) }
    }

    suspend fun updateFireState(reminderId: Long, nextFire: Long?, lastCompleted: Long?) {
        reminderDao.updateFireState(reminderId, nextFire, lastCompleted)
    }

    suspend fun effectiveChain(g: Group): EscalationChain = g.overrideChain ?: settings.getStageChain()

    private fun scheduleAsRow(s: Schedule): Pair<String, String> {
        val type = when (s) {
            is Schedule.OneShot -> "ONESHOT"
            is Schedule.Daily -> "DAILY"
            is Schedule.Weekly -> "WEEKLY"
            is Schedule.Monthly -> "MONTHLY"
            is Schedule.IntervalFromLast -> "INTERVAL"
            is Schedule.OnUnlock -> "ON_UNLOCK"
        }
        return type to ScheduleJson.encode(s)
    }
}
