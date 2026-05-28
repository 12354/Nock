package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmScheduler
import app.nock.android.alarm.AlarmService
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
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

        // OnUnlock reminders use the unlock event itself as the trigger, not
        // as a "deadline" to warn before. Pre-trigger stages (offset ≤ 0) and
        // the at-trigger stage would otherwise all fire seconds after unlock;
        // skip them and start at the first strictly-future stage so the
        // escalation actually escalates over time.
        val firstIdx = if (reminder.schedule is Schedule.OnUnlock) {
            val firstFuture = chain.stages.indexOfFirst { it.offsetMs > 0 }
            if (firstFuture >= 0) firstFuture else chain.stageDueAt(scheduledAtMs, now)
        } else {
            // If the user schedules an alarm whose early stages are already in
            // the past (e.g. SILENT @ -10min when the alarm is only 2min away),
            // jump straight to the latest stage that is already due. Otherwise
            // those skipped stages would all fire immediately as a burst.
            chain.stageDueAt(scheduledAtMs, now)
        }
        val firstStage = chain.stage(firstIdx)
        val firstFire = max(scheduledAtMs + firstStage.offsetMs, now + 1_000L)

        val ent = ActiveEscalationEntity(
            id = 0,
            reminderId = reminder.id,
            startedAtMs = scheduledAtMs,
            nextStageIndex = firstIdx,
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

        val now = System.currentTimeMillis()
        // The stage to fire is the latest one due at `now`, never behind the
        // stored cursor. This catches up correctly when the OS delivers an
        // alarm late (Doze, system busy, boot replay, etc.) — instead of
        // showing whatever earlier stage was queued, we jump to the stage
        // the user should currently be seeing based on elapsed time.
        val storedIdx = esc.nextStageIndex.coerceIn(0, chain.lastIndex)
        val dueIdx = chain.stageDueAt(esc.startedAtMs, now)
        val idx = max(storedIdx, dueIdx).coerceAtMost(chain.lastIndex)
        val stage = chain.stage(idx)

        var sentMessageId: Long? = null
        when (stage.type) {
            StageType.SILENT -> {
                notifier.showSilent(reminder, group, escalationId)
                if (group.telegramSilentMirror) {
                    sentMessageId = telegram.send(reminder, silent = true).messageId
                }
            }
            StageType.TELEGRAM -> {
                sentMessageId = telegram.send(reminder, silent = false).messageId
                notifier.showSilent(reminder, group, escalationId, suffix = " (Telegram sent)")
            }
            StageType.ALARM_VIBRATE -> notifier.showAlarmVibrate(reminder, group, escalationId)
            StageType.ALARM -> notifier.showAlarm(reminder, group, escalationId)
        }

        val updatedSentCsv = if (sentMessageId != null) {
            appendMessageId(esc.sentTelegramMessageIdsCsv, sentMessageId)
        } else {
            esc.sentTelegramMessageIdsCsv
        }

        val isLast = idx == chain.lastIndex
        if (isLast) {
            val nextAt = now + chain.repeatIntervalMs
            val updated = esc.copy(
                nextStageIndex = idx,
                nextFireAtMs = nextAt,
                sentTelegramMessageIdsCsv = updatedSentCsv
            )
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, stage.type)
        } else {
            val nextIdx = idx + 1
            val nextStage = chain.stage(nextIdx)
            val nextAt = max(esc.startedAtMs + nextStage.offsetMs, now + 1_000L)
            val updated = esc.copy(
                nextStageIndex = nextIdx,
                nextFireAtMs = nextAt,
                sentTelegramMessageIdsCsv = updatedSentCsv
            )
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, nextStage.type)
        }
    }

    suspend fun done(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        scheduler.cancel(esc.id)
        notifier.cancel(esc.id)
        notifier.stopAlarm()
        deleteSentTelegramMessages(esc.sentTelegramMessageIdsCsv)
        val reminder = repo.getReminder(esc.reminderId)
        activeDao.delete(esc)
        if (reminder != null) {
            // One-time schedules have no future occurrence once dismissed, so
            // drop the row entirely instead of leaving a "spent" reminder in
            // the list. See Schedule.isOneTime.
            if (reminder.schedule.isOneTime) {
                repo.deleteReminder(reminder)
                return
            }
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
        deleteSentTelegramMessages(esc.sentTelegramMessageIdsCsv)

        val currentlyFiring = (esc.nextStageIndex).coerceAtMost(chain.lastIndex)
        val isLast = currentlyFiring == chain.lastIndex
        val now = System.currentTimeMillis()
        if (isLast) {
            val nextAt = now + chain.repeatIntervalMs
            val updated = esc.copy(
                nextFireAtMs = nextAt,
                nextStageIndex = currentlyFiring,
                sentTelegramMessageIdsCsv = ""
            )
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, chain.stage(currentlyFiring).type)
        } else {
            activeDao.update(esc.copy(sentTelegramMessageIdsCsv = ""))
        }
    }

    suspend fun cancelActive(reminderId: Long) {
        val esc = activeDao.getByReminderId(reminderId) ?: return
        scheduler.cancel(esc.id)
        notifier.cancel(esc.id)
        // If this reminder is the one currently ringing, tear down the alarm
        // service too. Moving (edit/save) or removing a reminder mid-alarm
        // otherwise leaves the sound, vibration and full-screen alarm running
        // with no backing escalation — and the foreground-service notification
        // can't be dismissed by notifier.cancel() alone. Guarded so cancelling
        // a different reminder doesn't silence whatever is actually ringing.
        if (AlarmService.ringingEscalationId == esc.id) {
            notifier.stopAlarm()
        }
        deleteSentTelegramMessages(esc.sentTelegramMessageIdsCsv)
        activeDao.delete(esc)
    }

    private fun appendMessageId(csv: String, messageId: Long): String =
        if (csv.isEmpty()) messageId.toString() else "$csv,$messageId"

    private suspend fun deleteSentTelegramMessages(csv: String) {
        if (csv.isEmpty()) return
        csv.split(',').forEach { token ->
            token.toLongOrNull()?.let { telegram.deleteMessage(it) }
        }
    }

    // Triggered by UnlockReceiver. Fires any armed OnUnlock reminders by
    // starting their escalation chain "now". Skips reminders that are
    // already actively escalating so a re-unlock during the escalation
    // window doesn't spawn a second chain for the same reminder.
    suspend fun fireUnlockReminders() {
        val now = System.currentTimeMillis()
        repo.getAllReminders().forEach { reminder ->
            if (reminder.schedule !is Schedule.OnUnlock) return@forEach
            if (reminder.nextFireAt == null) return@forEach
            if (activeDao.getByReminderId(reminder.id) != null) return@forEach
            startEscalationAt(reminder, now)
        }
    }

    suspend fun rescheduleAll() {
        activeDao.getAll().forEach { esc ->
            val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull() ?: return@forEach
            val type = chain.stage(esc.nextStageIndex.coerceAtMost(chain.lastIndex)).type
            scheduler.scheduleStage(esc.id, esc.nextFireAtMs, type)
        }
        repo.getAllReminders().forEach { reminder ->
            // OnUnlock reminders are event-triggered, never time-triggered;
            // boot/time changes must not fire them.
            if (reminder.schedule is Schedule.OnUnlock) return@forEach
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
