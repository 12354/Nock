package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmScheduler
import app.nock.android.alarm.AlarmService
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.entity.PendingTelegramDeletionEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.domain.time.TimeSource
import app.nock.android.history.AlarmHistoryLogger
import app.nock.android.notif.NotificationPresenter
import app.nock.android.telegram.TelegramSender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val time: TimeSource,
    private val history: AlarmHistoryLogger,
    private val pendingDeletionDao: PendingTelegramDeletionDao,
) {
    // All escalation-state mutations are serialized through this mutex. The
    // engine is driven by independent BroadcastReceivers (alarm delivery, Done/
    // Snooze actions, unlock, boot/time-change) whose coroutines run on a shared
    // multi-threaded Dispatchers.Default scope, so without this they interleave.
    // The race that motivated it: a loud stage re-arms itself every repeat
    // interval at the TAIL of onAlarmFired (update row + scheduleStage). If Done
    // ran concurrently — cancelling the alarm and deleting the row in the window
    // between onAlarmFired's getById guard and that tail — the tail would arm a
    // fresh loud setAlarmClock alarm for a row that no longer exists. That orphan
    // fires one interval later and EscalationReceiver eagerly launches the
    // full-screen takeover for it, ringing an already-completed reminder. Holding
    // this across each operation restores the row↔alarm bijection under
    // concurrency: onAlarmFired either runs fully before Done (Done then tears the
    // re-armed repeat down) or fully after it (finds the row gone, self-heals,
    // schedules nothing).
    //
    // The best-effort Telegram deletion flush (a network call that can block for
    // tens of seconds) is deliberately kept OUTSIDE the lock so a slow flush can
    // never delay a Done/Snooze. The deletion ids are enqueued durably under the
    // lock; draining that queue afterwards is order-independent.
    private val mutex = Mutex()

    suspend fun scheduleNextFireForReminder(reminderId: Long): Long? {
        val next = mutex.withLock {
            val reminder = repo.getReminder(reminderId) ?: return@withLock null
            val n = reminder.schedule.nextFireFrom(time.nowMs(), reminder.lastCompletedAt)
            repo.updateFireState(reminderId, n, reminder.lastCompletedAt)
            cancelActiveLocked(reminderId)
            if (n != null) {
                startEscalationAtLocked(reminder.copy(nextFireAt = n), n)
            }
            n
        }
        flushPendingTelegramDeletions()
        return next
    }

    suspend fun startEscalationAt(reminder: Reminder, scheduledAtMs: Long) {
        mutex.withLock { startEscalationAtLocked(reminder, scheduledAtMs) }
        flushPendingTelegramDeletions()
    }

    private suspend fun startEscalationAtLocked(reminder: Reminder, scheduledAtMs: Long) {
        // Re-arming for this reminder (e.g. it was moved to a new time) must
        // first tear down any escalation already armed for it. upsert() below
        // REPLACEs the row on the unique reminderId index, but AlarmManager keys
        // its alarms by escalation id — a surviving prior alarm would keep firing
        // for the OLD time even though its backing row was replaced. Cancel and
        // delete the old escalation explicitly so the move actually moves every
        // already-scheduled alarm. (Callers that pre-cancel see a harmless no-op.)
        //
        // Moving the alarm also has to undo the side effects the abandoned chain
        // already produced — chiefly the Telegram messages it sent — exactly as
        // snooze()/cancelActive()/done() do. Otherwise a reminder moved after its
        // TELEGRAM stage fired would strand those messages in the chat. Queue them
        // for deletion (durably, before the row holding the ids is dropped); the
        // caller flushes the queue after releasing the lock so the new occurrence
        // starts clean.
        activeDao.getByReminderId(reminder.id)?.let { existing ->
            scheduler.cancel(existing.id)
            if (AlarmService.ringingEscalationId == existing.id) {
                notifier.stopAlarm()
            }
            enqueueTelegramDeletions(existing.sentTelegramMessageIdsCsv)
            activeDao.delete(existing)
        }
        val group = repo.getGroup(reminder.groupId) ?: return
        val chain = repo.effectiveChain(group)
        val now = time.nowMs()
        if (group.isPaused(now)) return

        // OnUnlock reminders use the unlock event itself as the trigger, not
        // as a "deadline" to warn before. Pre-trigger stages (offset ≤ 0) and
        // the at-trigger stage would otherwise all fire seconds after unlock;
        // skip them and start at the first strictly-future stage so the
        // escalation actually escalates over time.
        val firstIdx = if (reminder.schedule is Schedule.OnUnlock) {
            val firstFuture = chain.stages.indexOfFirst { it.offsetMs > 0 }
            if (firstFuture >= 0) firstFuture else chain.stageDueAt(scheduledAtMs, now)
        } else if (scheduledAtMs > now) {
            // The trigger is still in the future. Pre-trigger stages with a
            // negative offset may nonetheless already be in the past when the
            // reminder is due sooner than a stage's lead time (e.g. SILENT @
            // -10min on a reminder only 2min away, or any stage when a reminder
            // is moved far into the future and back). Start at the first stage
            // that hasn't passed so none of them — and no Telegram — fire the
            // instant the reminder is saved.
            chain.firstPendingStage(scheduledAtMs, now)
        } else {
            // The trigger time is already here or overdue (late OS delivery,
            // boot replay, etc.): jump straight to the latest stage that is
            // already due so we show the right one instead of a stale-but-queued
            // earlier stage.
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
        mutex.withLock { startEscalationFromBootLocked(reminder) }
    }

    private suspend fun startEscalationFromBootLocked(reminder: Reminder) {
        val group = repo.getGroup(reminder.groupId) ?: return
        if (group.isPaused(time.nowMs())) return
        val chain = repo.effectiveChain(group)
        val firstOffset = chain.stages.first().offsetMs
        val now = time.nowMs()
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
        history.replayedOverdue(reminder.name, reminder.nextFireAt, now)
    }

    suspend fun onAlarmFired(escalationId: Long) {
        // Every alarm tick is a chance to retry any Telegram deletions that a
        // previous Done/Snooze/Cancel couldn't complete (transient network error
        // or the receiver process dying mid-call). Run it first so it happens even
        // when this escalation has since been removed — and outside the lock so it
        // can't block a concurrent Done/Snooze.
        flushPendingTelegramDeletions()
        mutex.withLock { onAlarmFiredLocked(escalationId) }
        // Drain anything the fire just enqueued (orphan/drift teardown).
        flushPendingTelegramDeletions()
    }

    private suspend fun onAlarmFiredLocked(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: run {
            // No backing row, yet the OS still delivered this alarm. That means it
            // was orphaned in AlarmManager — e.g. a group deleted before the
            // cancel-on-delete fix cascade-removed the escalation row but not the
            // alarm it had scheduled. We can't enumerate those stale OS alarms to
            // cancel them up front, but the OS hands each one back to us here at
            // least once, so self-heal on delivery: tear down any notification or
            // ringing alarm that may have been left stuck for this id. We do NOT
            // reschedule, so the orphan lapses for good after this fire.
            notifier.cancel(escalationId)
            if (AlarmService.ringingEscalationId == escalationId) {
                notifier.stopAlarm()
            }
            return
        }
        val reminder = repo.getReminder(esc.reminderId) ?: run {
            // The reminder this chain belonged to is gone (deleted out-of-band —
            // e.g. a sync removing it — leaving an orphan escalation). Drop the
            // orphan row, but first clean up the Telegram messages it already
            // sent, exactly as every other teardown path does; otherwise those
            // messages are stranded in the chat forever. Queue durably, delete
            // the row, then the caller flushes the best-effort network deletes.
            enqueueTelegramDeletions(esc.sentTelegramMessageIdsCsv)
            activeDao.delete(esc)
            return
        }
        val group = repo.getGroup(reminder.groupId) ?: return
        val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull()
            ?: settings.getStageChain()

        val now = time.nowMs()

        // The reminder may have been moved after this chain was armed. The active
        // row records the occurrence it fires for in startedAtMs; the reminder's
        // authoritative trigger is nextFireAt, which is rewritten whenever the
        // reminder is edited/moved. If the reminder now points at a *different*
        // still-upcoming occurrence, this queued alarm is for the wrong time AND
        // date — re-arm from the reminder's current trigger instead of firing the
        // stale stage (which would ring on the old schedule and re-send Telegram).
        //
        // This holds even when the old chain had ALREADY started escalating and the
        // user had snoozed it: moving the alarm must fix the live escalation too, or
        // the snoozed chain keeps re-ringing on the old schedule (every repeat
        // interval) for an occurrence the user has since pushed to another day. So
        // the drift is detected purely from the trigger moving to a different
        // upcoming occurrence — not from whether startedAtMs is still in the future.
        //
        // The two legitimate non-moved shapes are left alone by the conditions:
        //   - a normal in-progress chain (whether still pre-trigger or already
        //     firing) has startedAtMs == nextFireAt, so the drift magnitude is ~0;
        //   - an overdue boot replay's synthetic startedAt deliberately sits in the
        //     future while nextFireAt stays in the PAST, so `trigger > now` is false.
        val trigger = reminder.nextFireAt
        if (trigger != null && trigger > now &&
            kotlin.math.abs(trigger - esc.startedAtMs) > SANITY_TOLERANCE_MS
        ) {
            // startEscalationAtLocked tears this stale escalation down first —
            // cancelling its alarm and queuing any Telegram it already sent for
            // deletion — then arms the chain for the reminder's current trigger.
            startEscalationAtLocked(reminder, trigger)
            return
        }

        // Last-second sanity check before we escalate. The escalation row records
        // nextFireAtMs — the chain-derived instant the next stage is actually due
        // — and an exact alarm should only ever deliver at or after it. If `now`
        // is meaningfully earlier, the wall clock and the chain disagree: the
        // clock moved backwards (NTP / timezone / manual change), a stale
        // PendingIntent re-fired, or the reminder was pushed further out. Firing
        // here would run the stage (and send a Telegram) ahead of schedule, so
        // instead re-arm the alarm for the real time and bail — correcting the
        // state rather than mis-firing it.
        if (now < esc.nextFireAtMs - SANITY_TOLERANCE_MS) {
            val pendingType = chain.stage(esc.nextStageIndex.coerceIn(0, chain.lastIndex)).type
            scheduler.scheduleStage(esc.id, esc.nextFireAtMs, pendingType)
            return
        }

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
            StageType.VIBRATE -> {
                notifier.showVibrate(reminder, group, escalationId)
                if (group.telegramSilentMirror) {
                    sentMessageId = telegram.send(reminder, silent = true).messageId
                }
            }
            StageType.TELEGRAM -> {
                sentMessageId = telegram.send(reminder, silent = false).messageId
                notifier.showPreAlarm(reminder, group, escalationId, suffix = " (Telegram sent)")
            }
            StageType.ALARM_VIBRATE -> notifier.showAlarmVibrate(reminder, group, escalationId)
            StageType.ALARM -> notifier.showAlarm(reminder, group, escalationId)
        }
        history.fired(reminder.name, stage.type, esc.startedAtMs, now)

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
        mutex.withLock { doneLocked(escalationId) }
        flushPendingTelegramDeletions()
    }

    private suspend fun doneLocked(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        scheduler.cancel(esc.id)
        notifier.cancel(esc.id)
        notifier.stopAlarm()

        // Durably queue the sent-message ids for deletion BEFORE we drop the row
        // that holds them, so the ids can't be lost if the delete network call
        // fails or the process dies. The queue is drained by the public wrapper
        // (outside the lock) and retried on every later alarm tick / boot until
        // Telegram confirms each deletion.
        enqueueTelegramDeletions(esc.sentTelegramMessageIdsCsv)

        // Commit all durable state — drop the active row and arm the next
        // occurrence — while still holding the lock, BEFORE the public wrapper's
        // best-effort Telegram flush. This action is delivered to a
        // BroadcastReceiver whose process is only kept alive by the alarm
        // foreground service we just stopped (plus a short goAsync window).
        // flushPendingTelegramDeletions is a network call that can block for tens
        // of seconds on a bad connection (15s connect + 20s read, per message); if
        // it ran first and the process was killed mid-call we would have already
        // cancelled the alarm but never removed the row or re-armed the chain,
        // leaving the reminder stuck at a past fire time that never rings again.
        // Keeping the network work strictly after the durable state (and outside
        // the lock) means it can't corrupt the escalation state — and the durable
        // queue above means a killed flush merely defers the deletion.
        val reminder = repo.getReminder(esc.reminderId)
        activeDao.delete(esc)
        if (reminder != null) {
            history.done(reminder.name)
            // One-time schedules have no future occurrence once dismissed, so
            // drop the row entirely instead of leaving a "spent" reminder in
            // the list. See Schedule.isOneTime.
            if (reminder.schedule.isOneTime) {
                repo.deleteReminder(reminder)
            } else {
                val now = time.nowMs()
                // Advance past the occurrence just completed. When Done is pressed
                // during the pre-trigger window — e.g. the SILENT stage fires 10 min
                // before the due time and the user acknowledges at 07:39 for a 07:40
                // reminder — `now` is still earlier than this occurrence's trigger, so
                // nextFireFrom(now) would return the SAME occurrence and re-arm the
                // chain we're dismissing. Its pre-trigger stage is already in the past,
                // so the re-armed chain jumps straight to Telegram/alarm and rings
                // anyway (the bug this guards against). Anchor the search at the
                // occurrence's own scheduled time so we always move to the next one.
                val searchFrom = max(now, reminder.nextFireAt ?: now)
                val next = reminder.schedule.nextFireFrom(searchFrom, now)
                repo.updateFireState(reminder.id, next, now)
                if (next != null) {
                    startEscalationAtLocked(reminder.copy(lastCompletedAt = now, nextFireAt = next), next)
                }
            }
        }
    }

    suspend fun snooze(escalationId: Long) {
        mutex.withLock { snoozeLocked(escalationId) }
        flushPendingTelegramDeletions()
    }

    private suspend fun snoozeLocked(escalationId: Long) {
        val esc = activeDao.getById(escalationId) ?: return
        val chain = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull()
            ?: settings.getStageChain()
        notifier.cancelStageVisuals(esc.id)
        notifier.stopAlarm()

        // Durably queue the sent-message ids BEFORE the row's csv is cleared
        // below, so a failed/interrupted delete can't lose them — the queue is
        // drained by the public wrapper (outside the lock) and retried on every
        // later alarm tick / boot.
        enqueueTelegramDeletions(esc.sentTelegramMessageIdsCsv)

        // Persist the snooze (re-armed repeat / advanced cursor) while holding the
        // lock, BEFORE the best-effort Telegram flush, for the same reason as
        // done(): the network delete can block past the receiver's lifetime once
        // the alarm foreground service is gone, and we must not lose the snoozed
        // state if the process is killed mid-call.
        val currentlyFiring = (esc.nextStageIndex).coerceAtMost(chain.lastIndex)
        val isLast = currentlyFiring == chain.lastIndex
        val now = time.nowMs()
        val nextAt: Long
        if (isLast) {
            nextAt = now + chain.repeatIntervalMs
            val updated = esc.copy(
                nextFireAtMs = nextAt,
                nextStageIndex = currentlyFiring,
                sentTelegramMessageIdsCsv = ""
            )
            activeDao.update(updated)
            scheduler.scheduleStage(updated.id, nextAt, chain.stage(currentlyFiring).type)
        } else {
            // Cursor is untouched — the next stage stays armed at its existing time.
            nextAt = esc.nextFireAtMs
            activeDao.update(esc.copy(sentTelegramMessageIdsCsv = ""))
        }
        repo.getReminder(esc.reminderId)?.let { history.snoozed(it.name, nextAt) }
    }

    // Deleting a group cascade-deletes its reminders and their active_escalations
    // rows (FK ON DELETE CASCADE), but a row deletion does NOT cancel the alarm
    // AlarmManager already holds for it — that alarm keeps its status-bar icon and
    // still fires (or, if a reminder was mid-escalation, keeps ringing through the
    // foreground service) for a reminder that now exists in no list. Tear every
    // in-flight escalation down explicitly before the group is removed, exactly as
    // the single-reminder delete paths do. Must run BEFORE repo.deleteGroup: once
    // the cascade fires, the reminders are gone and these rows can't be found.
    suspend fun cancelActiveForGroup(groupId: Long) {
        mutex.withLock {
            repo.getAllReminders()
                .filter { it.groupId == groupId }
                .forEach { cancelActiveLocked(it.id) }
        }
        flushPendingTelegramDeletions()
    }

    // Editing a group's chain/timing only writes the group row; the escalations
    // already armed for its reminders keep the chain they snapshotted when armed,
    // so a changed chain wouldn't take effect until each reminder next fires (or a
    // reboot). Re-arm them now so the edit applies immediately — including chains
    // already in flight: a user who changes the schedule does not expect the old
    // one to keep firing. This mirrors moving a reminder — startEscalationAt tears
    // the stale chain down (cancelling its alarm, stopping a live ring, deleting
    // any Telegram it already sent) and re-arms from the reminder's current trigger
    // at the stage that is due now under the new chain.
    //
    // Paused groups are left untouched — pause owns arming, and startEscalationAt
    // would tear the chain down without re-arming.
    suspend fun rearmGroup(groupId: Long) {
        mutex.withLock { rearmGroupLocked(groupId) }
        flushPendingTelegramDeletions()
    }

    private suspend fun rearmGroupLocked(groupId: Long) {
        val group = repo.getGroup(groupId) ?: return
        if (group.isPaused(time.nowMs())) return
        repo.getAllReminders()
            .filter { it.groupId == groupId }
            .forEach { reminder ->
                // OnUnlock reminders are event-triggered, not time-armed.
                if (reminder.schedule is Schedule.OnUnlock) return@forEach
                val trigger = reminder.nextFireAt ?: return@forEach
                startEscalationAtLocked(reminder, trigger)
            }
    }

    // The global stage chain is the effective chain for every group that has no
    // override, so editing it has to re-arm those groups' reminders exactly as
    // editing a group's own chain does. Groups carrying an override are unaffected
    // by a global change and are left alone.
    suspend fun rearmDefaultChainGroups() {
        mutex.withLock {
            repo.getGroups()
                .filter { it.overrideChain == null }
                .forEach { rearmGroupLocked(it.id) }
        }
        flushPendingTelegramDeletions()
    }

    suspend fun cancelActive(reminderId: Long) {
        mutex.withLock { cancelActiveLocked(reminderId) }
        flushPendingTelegramDeletions()
    }

    private suspend fun cancelActiveLocked(reminderId: Long) {
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
        // Queue durably before the row holding the ids is dropped; the caller
        // flushes the queue after releasing the lock.
        enqueueTelegramDeletions(esc.sentTelegramMessageIdsCsv)
        activeDao.delete(esc)
    }

    private fun appendMessageId(csv: String, messageId: Long): String =
        if (csv.isEmpty()) messageId.toString() else "$csv,$messageId"

    /** Persist each id in [csv] into the durable pending-deletion queue. */
    private suspend fun enqueueTelegramDeletions(csv: String) {
        if (csv.isEmpty()) return
        csv.split(',').forEach { token ->
            token.toLongOrNull()?.let { pendingDeletionDao.insert(PendingTelegramDeletionEntity(it)) }
        }
    }

    /**
     * Best-effort drain of the durable pending-deletion queue. An id is removed
     * only once [TelegramSender.deleteMessage] reports the deletion is resolved
     * (succeeded, or permanently un-deletable); transient failures stay queued
     * and are retried on the next alarm tick / boot.
     *
     * Intentionally NOT guarded by [mutex]: it touches only the independent
     * pending-deletion table (never escalation rows) and performs blocking
     * network I/O, so holding the lock across it would let a slow flush delay an
     * incoming Done/Snooze. Callers run it after releasing the lock.
     */
    private suspend fun flushPendingTelegramDeletions() {
        pendingDeletionDao.getAll().forEach { pending ->
            if (telegram.deleteMessage(pending.messageId)) {
                pendingDeletionDao.delete(pending.messageId)
            }
        }
    }

    // Triggered by UnlockReceiver. Fires any armed OnUnlock reminders by
    // starting their escalation chain "now". Skips reminders that are
    // already actively escalating so a re-unlock during the escalation
    // window doesn't spawn a second chain for the same reminder.
    suspend fun fireUnlockReminders() {
        mutex.withLock {
            val now = time.nowMs()
            repo.getAllReminders().forEach { reminder ->
                if (reminder.schedule !is Schedule.OnUnlock) return@forEach
                if (reminder.nextFireAt == null) return@forEach
                if (activeDao.getByReminderId(reminder.id) != null) return@forEach
                startEscalationAtLocked(reminder, now)
            }
        }
        flushPendingTelegramDeletions()
    }

    suspend fun rescheduleAll() {
        // Boot / time-change is also a good moment to retry any deletions that
        // never completed before the device restarted.
        flushPendingTelegramDeletions()
        mutex.withLock { rescheduleAllLocked() }
    }

    private suspend fun rescheduleAllLocked() {
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
                val now = time.nowMs()
                val nextFromSchedule = reminder.schedule.nextFireFrom(now, reminder.lastCompletedAt) ?: return@forEach
                val overdue = reminder.nextFireAt != null && reminder.nextFireAt < now
                if (overdue) {
                    startEscalationFromBootLocked(reminder)
                } else {
                    repo.updateFireState(reminder.id, nextFromSchedule, reminder.lastCompletedAt)
                    startEscalationAtLocked(reminder.copy(nextFireAt = nextFromSchedule), nextFromSchedule)
                }
            }
    }

    // ---- Compound operations for callers OUTSIDE the engine ----------------
    // These run under the same [mutex] as the engine's own fire/dismiss paths so
    // that code which mutates the reminders/escalations tables from elsewhere
    // (snapshot import, calendar-trip sync, the edit/done/delete ViewModels) can
    // no longer interleave with onAlarmFired/done/snooze and orphan an alarm or
    // tear the row↔alarm bijection. Each uses the lock-free *Locked cores and
    // flushes the Telegram deletion queue afterwards, like the engine's own
    // public entry points.

    /**
     * Atomically apply a wholesale data replacement (e.g. a Drive snapshot
     * import's clear+repopulate transaction) and re-arm every surviving reminder.
     * [mutateDb] runs under the engine lock so no concurrent fire can read a row,
     * then have its backing data wiped, and then re-arm an alarm for the deleted
     * id. rescheduleAllLocked rebuilds the bijection from whatever [mutateDb] left.
     */
    suspend fun replaceAllAndRearm(mutateDb: suspend () -> Unit) {
        mutex.withLock {
            mutateDb()
            rescheduleAllLocked()
        }
        flushPendingTelegramDeletions()
    }

    /**
     * Persist a reminder and (re-)arm its escalation as one atomic step — the
     * editor's Save. Replaces the non-atomic saveReminder + cancelActive +
     * startEscalationAt sequence the ViewModel used to do across the lock.
     */
    suspend fun saveReminderAndArm(
        id: Long,
        groupId: Long,
        name: String,
        schedule: Schedule,
        nextFireAt: Long?,
        lastCompletedAt: Long?,
        createdAt: Long,
    ): Long {
        val savedId = mutex.withLock {
            val sid = repo.saveReminder(id, groupId, name, schedule, nextFireAt, lastCompletedAt, createdAt)
            // Tear down any escalation armed for the prior version of this reminder
            // before arming the new one (REPLACE-on-reminderId would orphan its alarm).
            cancelActiveLocked(sid)
            // OnUnlock reminders are armed in the DB and fire on ACTION_USER_PRESENT,
            // not on save, so they get no time-based escalation here.
            if (nextFireAt != null && schedule !is Schedule.OnUnlock) {
                repo.getReminder(sid)?.let { startEscalationAtLocked(it, nextFireAt) }
            }
            sid
        }
        flushPendingTelegramDeletions()
        return savedId
    }

    /**
     * Complete a reminder regardless of whether it is currently escalating — the
     * Today screen's Done. If an escalation is live this is exactly [done];
     * otherwise it advances/retires the reminder the same way done() does for a
     * recurring/one-time schedule, all under the lock.
     */
    suspend fun completeReminder(reminderId: Long) {
        mutex.withLock {
            val active = activeDao.getByReminderId(reminderId)
            if (active != null) {
                doneLocked(active.id)
                return@withLock
            }
            val reminder = repo.getReminder(reminderId) ?: return@withLock
            history.done(reminder.name)
            if (reminder.schedule.isOneTime) {
                repo.deleteReminder(reminder)
                return@withLock
            }
            val now = time.nowMs()
            // Skip the occurrence just completed (mirrors done()): anchor the search
            // at the occurrence's own scheduled time so we advance to the next one
            // instead of re-arming this one when Done lands before the due time.
            val searchFrom = max(now, reminder.nextFireAt ?: now)
            val next = reminder.schedule.nextFireFrom(searchFrom, now)
            repo.updateFireState(reminderId, next, now)
            if (next != null) {
                startEscalationAtLocked(reminder.copy(lastCompletedAt = now, nextFireAt = next), next)
            }
        }
        flushPendingTelegramDeletions()
    }

    /** Cancel any escalation and delete the reminder as one atomic step. */
    suspend fun deleteReminderAndCancel(reminderId: Long) {
        mutex.withLock {
            cancelActiveLocked(reminderId)
            repo.getReminder(reminderId)?.let { repo.deleteReminder(it) }
        }
        flushPendingTelegramDeletions()
    }

    /**
     * Re-insert a previously deleted reminder and re-arm it — the undo for
     * [deleteReminderAndCancel]. The captured row carries its original id, so the
     * @Upsert restores it in place rather than minting a new one.
     */
    suspend fun restoreReminder(reminder: Reminder) {
        saveReminderAndArm(
            id = reminder.id,
            groupId = reminder.groupId,
            name = reminder.name,
            schedule = reminder.schedule,
            nextFireAt = reminder.nextFireAt,
            lastCompletedAt = reminder.lastCompletedAt,
            createdAt = reminder.createdAt,
        )
    }

    /**
     * Run [block] under the engine lock with access to the lock-free arm/cancel
     * cores. For callers (calendar-trip sync) that must interleave their own
     * cross-table writes (the trip row) with a reminder save + (re-)arm and need
     * the whole sequence atomic w.r.t. the engine and snapshot import.
     */
    suspend fun <T> runExclusive(block: suspend Exclusive.() -> T): T {
        val result = mutex.withLock { exclusive.block() }
        flushPendingTelegramDeletions()
        return result
    }

    /** Lock-held facade exposing only the safe-to-call-under-lock primitives. */
    inner class Exclusive internal constructor() {
        suspend fun arm(reminder: Reminder, scheduledAtMs: Long) =
            startEscalationAtLocked(reminder, scheduledAtMs)

        suspend fun cancel(reminderId: Long) = cancelActiveLocked(reminderId)
    }

    private val exclusive = Exclusive()

    companion object {
        // Slack allowed between an alarm's delivery and the stage's due time
        // before we treat the early fire as a clock/state inconsistency. Exact
        // alarms are floored a second out when scheduled, so legitimate jitter
        // stays well under this; anything earlier is a real disagreement.
        private const val SANITY_TOLERANCE_MS = 1_000L
    }
}
