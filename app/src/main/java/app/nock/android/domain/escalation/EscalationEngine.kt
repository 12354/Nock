package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmScheduler
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.notif.NotificationPresenter
import app.nock.android.telegram.TelegramSender
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class EscalationEngine @Inject constructor(
    private val repo: NockRepository,
    private val activeDao: ActiveEscalationDao,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
    private val notifier: NotificationPresenter,
    private val telegram: TelegramSender,
) {
    suspend fun scheduleNextFireForReminder(reminderId: Long): Long? {
        val reminder = repo.getReminder(reminderId) ?: return null
        val next = reminder.schedule.nextFireFrom(System.currentTimeMillis(), reminder.lastCompletedAt)
        repo.updateFireState(reminderId, next, reminder.lastCompletedAt)
        cancelActive(reminderId)
        if (next != null) {
            startEscalationAt(reminder.copy(nextFireAt = next), next)
        }
        return next
    }

    suspend fun startEscalationAt(reminder: Reminder, scheduledAtMs: Long) {
        val group = repo.getGroup(reminder.groupId) ?: return
        val chain = repo.effectiveChain(group)
        val now = System.currentTimeMillis()
        if (group.isPaused(now)) return

        val firstStage = chain.stages.first()
        val firstFire = max(scheduledAtMs + firstStage.offsetMs, now + 1_000L)

        val ent = ActiveEscalationEntity(
            id = 0,
            reminderId = reminder.id,
            startedAtMs = scheduledAtMs,
            nextStageIndex = 0,
            nextFireAtMs = firstFire,
            chainSnapshotJson = ChainJson.encode(chain),
            repeatIntervalMs = chain.repeatIntervalMs
        )
        val id = activeDao.upsert(ent)
        scheduler.scheduleStage(id, firstFire, firstStage.type)
    }

    suspend fun startEscalationFromBoot(reminder: Reminder) {
        val group = repo.getGroup(reminder.groupId) ?: return
        if (group.isPaused(System.currentTimeMillis())) return
        val chain = repo.effectiveChain(group)
        val firstOffset = chain.stages.first().offsetMs
        val now = System.currentTimeMillis()
        val syntheticStart = now - firstOffset
        val first = max(syntheticStart + firstOffset, now + 1_000L)

        val ent = ActiveEscalationEntity(
            id = 0,
            reminderId = reminder.id,
            startedAtMs = syntheticStart,
            nextStageIndex = 0,
            nextFireAtMs = first,
            chainSnapshotJson = ChainJson.encode(chain),
            repeatIntervalMs = chain.repeatIntervalMs
        )
        val id = activeDao.upsert(ent)
        scheduler.scheduleStage(id, first, chain.stages.first().type)
    }

    suspend fun onAlarmFired(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        val reminder = repo.getReminder(esc.reminderId) ?: run {
            activeDao.delete(esc); return
        }
        val group = repo.getGroup(reminder.groupId) ?: return
        val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull()
            ?: settings.getStageChain()

        val idx = esc.nextStageIndex.coerceIn(0, chain.lastIndex)
        val stage = chain.stage(idx)

        when (stage.type) {
            StageType.SILENT -> {
                notifier.showSilent(reminder, group, escalationId)
                if (group.telegramSilentMirror) telegram.send(reminder, silent = true)
            }
            StageType.NORMAL -> notifier.showNormal(reminder, group, escalationId)
            StageType.TELEGRAM -> {
                telegram.send(reminder, silent = false)
                notifier.showSilent(reminder, group, escalationId, suffix = " (Telegram sent)")
            }
            StageType.ALARM -> notifier.showAlarm(reminder, group, escalationId)
        }

        val isLast = idx == chain.lastIndex
        val now = System.currentTimeMillis()
        if (isLast) {
            val nextAt = now + chain.repeatIntervalMs
            val updated = esc.copy(nextStageIndex = idx, nextFireAtMs = nextAt)
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, stage.type)
        } else {
            val nextIdx = idx + 1
            val nextStage = chain.stage(nextIdx)
            val nextAt = max(esc.startedAtMs + nextStage.offsetMs, now + 1_000L)
            val updated = esc.copy(nextStageIndex = nextIdx, nextFireAtMs = nextAt)
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, nextStage.type)
        }
    }

    suspend fun done(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        scheduler.cancel(esc.id)
        notifier.cancel(esc.id)
        notifier.stopAlarm()
        val reminder = repo.getReminder(esc.reminderId)
        activeDao.delete(esc)
        if (reminder != null) {
            val now = System.currentTimeMillis()
            val next = reminder.schedule.nextFireFrom(now, now)
            repo.updateFireState(reminder.id, next, now)
            if (next != null) {
                startEscalationAt(reminder.copy(lastCompletedAt = now, nextFireAt = next), next)
            }
        }
    }

    suspend fun snooze(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull()
            ?: settings.getStageChain()
        notifier.cancelStageVisuals(esc.id)
        notifier.stopAlarm()

        val currentlyFiring = (esc.nextStageIndex).coerceAtMost(chain.lastIndex)
        val isLast = currentlyFiring == chain.lastIndex
        val now = System.currentTimeMillis()
        if (isLast) {
            val nextAt = now + chain.repeatIntervalMs
            val updated = esc.copy(nextFireAtMs = nextAt, nextStageIndex = currentlyFiring)
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, chain.stage(currentlyFiring).type)
        }
    }

    suspend fun cancelActive(reminderId: Long) {
        val esc = activeDao.getByReminderId(reminderId) ?: return
        scheduler.cancel(esc.id)
        notifier.cancel(esc.id)
        activeDao.delete(esc)
    }

    suspend fun rescheduleAll() {
        activeDao.getAll().forEach { esc ->
            val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull() ?: return@forEach
            val type = chain.stage(esc.nextStageIndex.coerceAtMost(chain.lastIndex)).type
            scheduler.scheduleStage(esc.id, esc.nextFireAtMs, type)
        }
        repo.getAllReminders().forEach { reminder ->
            val active = activeDao.getByReminderId(reminder.id)
            if (active != null) return@forEach
            val now = System.currentTimeMillis()
            val nextFromSchedule = reminder.schedule.nextFireFrom(now, reminder.lastCompletedAt) ?: return@forEach
            val overdue = reminder.nextFireAt != null && reminder.nextFireAt < now
            if (overdue) {
                startEscalationFromBoot(reminder)
            } else {
                repo.updateFireState(reminder.id, nextFromSchedule, reminder.lastCompletedAt)
                startEscalationAt(reminder.copy(nextFireAt = nextFromSchedule), nextFromSchedule)
            }
        }
    }
}
