package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmService
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dismiss ("done") and cancel paths. Includes the ringing-alarm teardown which
 * keys off the [AlarmService.ringingEscalationId] companion static.
 */
class EscalationEngineDoneTest {

    // AlarmService.ringingEscalationId has a private setter (only the service
    // lifecycle mutates it in production); set it via reflection so we can
    // exercise the "is this escalation the one currently ringing?" branch.
    private fun setRinging(id: Long?) {
        val field = AlarmService::class.java.getDeclaredField("ringingEscalationId")
        field.isAccessible = true
        field.set(null, id)
    }

    @After fun resetRinging() = setRinging(null)

    @Test fun done_tears_down_alarm_and_removes_row() = runTest {
        val h = EngineHarness(now = NOW)
        coEvery { h.repo.getReminder(any()) } returns reminder(schedule = Schedule.OnUnlock(NOW))
        val row = activeEntity(
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "1,2",
        )
        h.dao.upsert(row)

        h.engine.done(row.id)

        verify { h.scheduler.cancel(row.id) }
        verify { h.notifier.cancel(row.id) }
        verify { h.notifier.stopAlarm() }
        coVerify { h.telegram.deleteMessage(1L) }
        coVerify { h.telegram.deleteMessage(2L) }
        assertNull(h.dao.getById(row.id))
    }

    @Test fun done_on_one_time_reminder_deletes_the_reminder() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(schedule = Schedule.OneShot(NOW))
        coEvery { h.repo.getReminder(any()) } returns r
        val row = activeEntity(nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.done(row.id)

        coVerify { h.repo.deleteReminder(r) }
        // No re-arm for a spent one-shot.
        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
    }

    @Test fun done_on_recurring_reminder_rearms_next_occurrence() = runTest {
        val h = EngineHarness(now = NOW)
        // Daily at 10:00; completing at 09:00 -> next is today 10:00.
        val r = reminder(schedule = Schedule.Daily(listOf(10 * 60)))
        h.stubReminderAndGroup(r, group())
        val row = activeEntity(nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.done(row.id)

        // lastCompleted is recorded as "now" and a fresh escalation is armed.
        coVerify { h.repo.updateFireState(eq(r.id), any(), eq(NOW)) }
        assertTrue(h.dao.rows.values.any { it.reminderId == r.id })
    }

    @Test fun done_during_pretrigger_window_advances_past_the_completed_occurrence() = runTest {
        // Regression: a daily reminder whose SILENT pre-trigger stage has already
        // fired (e.g. 10 min before the due time). The user presses Done a minute
        // BEFORE the trigger. nextFireFrom(now) alone would return today's same
        // occurrence (its time is still in the future), re-arming the chain we just
        // dismissed — and since the pre-trigger stage is in the past, the re-armed
        // chain would jump straight to Telegram and ring anyway.
        val zone = java.time.ZoneId.systemDefault()
        // Today's trigger, expressed as the schedule's local time-of-day so the
        // recurring computation lands on exactly this instant regardless of zone.
        val trigger = epochMs(2026, 5, 30, 7, 40)
        val tod = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(trigger), zone
        )
        val schedule = Schedule.Daily(listOf(tod.hour * 60 + tod.minute))
        val pressedAt = trigger - MIN // inside the pre-trigger window, before the due time

        val h = EngineHarness(now = pressedAt)
        val r = reminder(schedule = schedule, nextFireAt = trigger)
        h.stubReminderAndGroup(r, group())
        // Active row started at the occurrence's trigger, currently on the SILENT stage.
        val row = activeEntity(startedAtMs = trigger, nextStageIndex = 1, nextFireAtMs = trigger + 5 * MIN)
        h.dao.upsert(row)

        h.engine.done(row.id)

        // lastCompleted is recorded as the press time...
        coVerify { h.repo.updateFireState(eq(r.id), any(), eq(pressedAt)) }
        // ...and the freshly-armed escalation must be for a LATER occurrence, never
        // today's again — startedAtMs is the occurrence trigger the chain runs from.
        val rearmed = h.dao.rows.values.single { it.reminderId == r.id }
        assertTrue(
            "re-armed occurrence ${rearmed.startedAtMs} must be after the completed trigger $trigger",
            rearmed.startedAtMs > trigger
        )
    }

    @Test fun done_completes_and_rearms_before_attempting_telegram_cleanup() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(schedule = Schedule.Daily(listOf(10 * 60)))
        h.stubReminderAndGroup(r, group())
        val row = activeEntity(
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "1,2",
        )
        h.dao.upsert(row)
        // The Telegram delete is a network call; in production the receiver's
        // process can be killed mid-call once the alarm foreground service is
        // gone (here we model that as a thrown failure). The escalation must
        // already be torn down and the next occurrence armed before we get here,
        // otherwise a slow/failed delete strands the reminder at a past fire time.
        coEvery { h.telegram.deleteMessage(any()) } throws RuntimeException("network down")

        runCatching { h.engine.done(row.id) }

        // The alarm was cancelled and the next occurrence re-armed (a fresh row
        // for the reminder exists) — all before the failing Telegram cleanup,
        // so a slow/failed delete can no longer strand the escalation.
        verify { h.scheduler.cancel(row.id) }
        coVerify { h.repo.updateFireState(eq(r.id), any(), eq(NOW)) }
        assertTrue(h.dao.rows.values.any { it.reminderId == r.id })
    }

    @Test fun done_unknown_escalation_is_a_noop() = runTest {
        val h = EngineHarness(now = NOW)

        h.engine.done(404L)

        verify(exactly = 0) { h.notifier.stopAlarm() }
        verify(exactly = 0) { h.scheduler.cancel(any()) }
    }

    // --- cancelActive -------------------------------------------------------

    @Test fun cancelActive_stops_alarm_only_when_this_escalation_is_ringing() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(reminderId = REMINDER_ID, nextStageIndex = 3, nextFireAtMs = NOW)
        val id = h.dao.upsert(row)
        setRinging(id)

        h.engine.cancelActive(REMINDER_ID)

        verify { h.scheduler.cancel(id) }
        verify { h.notifier.cancel(id) }
        verify { h.notifier.stopAlarm() }
        assertNull(h.dao.getById(id))
    }

    @Test fun cancelActive_leaves_other_ringing_alarm_untouched() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(reminderId = REMINDER_ID, nextStageIndex = 3, nextFireAtMs = NOW)
        val id = h.dao.upsert(row)
        // A *different* escalation is the one currently ringing.
        setRinging(id + 999)

        h.engine.cancelActive(REMINDER_ID)

        verify { h.scheduler.cancel(id) }
        verify { h.notifier.cancel(id) }
        verify(exactly = 0) { h.notifier.stopAlarm() }
        assertNull(h.dao.getById(id))
    }

    @Test fun cancelActive_for_unknown_reminder_is_a_noop() = runTest {
        val h = EngineHarness(now = NOW)

        h.engine.cancelActive(123456L)

        verify(exactly = 0) { h.scheduler.cancel(any()) }
        verify(exactly = 0) { h.notifier.cancel(any()) }
    }

    @Test fun cancelActive_deletes_sent_telegram_messages() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(
            reminderId = REMINDER_ID,
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "5,6,7",
        )
        val id = h.dao.upsert(row)

        h.engine.cancelActive(REMINDER_ID)

        coVerify { h.telegram.deleteMessage(5L) }
        coVerify { h.telegram.deleteMessage(6L) }
        coVerify { h.telegram.deleteMessage(7L) }
        assertEquals(0, h.dao.rows.size)
    }

    // --- cancelActiveForGroup ----------------------------------------------

    @Test fun cancelActiveForGroup_cancels_every_reminder_in_the_group_only() = runTest {
        // Regression: deleting a group cascade-deletes its reminders + active rows
        // but never cancelled the OS alarms, so a phantom alarm kept firing for a
        // reminder that exists in no list. cancelActiveForGroup must tear down each
        // in-group escalation (cancelling its alarm) and leave other groups alone.
        val h = EngineHarness(now = NOW)
        val inGroupA = reminder(id = 1L, groupId = 100L)
        val inGroupB = reminder(id = 2L, groupId = 100L)
        val otherGroup = reminder(id = 3L, groupId = 200L)
        coEvery { h.repo.getAllReminders() } returns listOf(inGroupA, inGroupB, otherGroup)
        val a = h.dao.upsert(activeEntity(id = 0L, reminderId = 1L, nextStageIndex = 0, nextFireAtMs = NOW))
        val b = h.dao.upsert(activeEntity(id = 0L, reminderId = 2L, nextStageIndex = 0, nextFireAtMs = NOW))
        val c = h.dao.upsert(activeEntity(id = 0L, reminderId = 3L, nextStageIndex = 0, nextFireAtMs = NOW))

        h.engine.cancelActiveForGroup(100L)

        verify { h.scheduler.cancel(a) }
        verify { h.scheduler.cancel(b) }
        verify(exactly = 0) { h.scheduler.cancel(c) }
        assertNull(h.dao.getById(a))
        assertNull(h.dao.getById(b))
        // The unrelated group's escalation survives.
        assertEquals(c, h.dao.getById(c)?.id)
    }

    @Test fun cancelActiveForGroup_with_no_active_reminders_is_a_noop() = runTest {
        val h = EngineHarness(now = NOW)
        // A reminder in the group exists but has no in-flight escalation.
        coEvery { h.repo.getAllReminders() } returns listOf(reminder(id = 1L, groupId = 100L))

        h.engine.cancelActiveForGroup(100L)

        verify(exactly = 0) { h.scheduler.cancel(any()) }
    }

    // --- rearmGroup --------------------------------------------------------

    @Test fun rearmGroup_replaces_not_started_escalation_with_current_chain() = runTest {
        // Editing a group's chain must take effect on its already-armed (but not
        // yet firing) reminders right away — the stale escalation is torn down and
        // a fresh one carrying the new chain is armed.
        val h = EngineHarness(now = NOW)
        val g = group(id = 100L)
        val r = reminder(id = 1L, groupId = 100L, nextFireAt = NOW + 60 * MIN)
        coEvery { h.repo.getGroup(100L) } returns g
        coEvery { h.repo.getAllReminders() } returns listOf(r)
        val newChain = EscalationChain(
            stages = listOf(StageConfig(StageType.ALARM, 0L)),
            repeatIntervalMs = 5 * MIN,
        )
        coEvery { h.repo.effectiveChain(g) } returns newChain
        // Armed but not started: its occurrence trigger is still in the future.
        val old = activeEntity(
            id = 0L, reminderId = 1L, startedAtMs = NOW + 60 * MIN,
            nextStageIndex = 0, nextFireAtMs = NOW + 50 * MIN, chain = TEST_CHAIN,
        )
        val oldId = h.dao.upsert(old)

        h.engine.rearmGroup(100L)

        verify { h.scheduler.cancel(oldId) }
        val rows = h.dao.rows.values.filter { it.reminderId == 1L }
        assertEquals(1, rows.size)
        assertEquals(ChainJson.encode(newChain), rows.single().chainSnapshotJson)
    }

    @Test fun rearmGroup_rearms_in_flight_escalation_too() = runTest {
        // A user who changes a group's schedule does not expect an already-firing
        // chain to keep escalating on the old one. The stale in-flight escalation
        // is torn down and re-armed under the new chain (like moving a task).
        val h = EngineHarness(now = NOW)
        val g = group(id = 100L)
        val r = reminder(id = 1L, groupId = 100L, nextFireAt = NOW - 5 * MIN)
        coEvery { h.repo.getGroup(100L) } returns g
        coEvery { h.repo.getAllReminders() } returns listOf(r)
        val newChain = EscalationChain(
            stages = listOf(StageConfig(StageType.ALARM, 0L)),
            repeatIntervalMs = 5 * MIN,
        )
        coEvery { h.repo.effectiveChain(g) } returns newChain
        val inflight = activeEntity(
            id = 0L, reminderId = 1L, startedAtMs = NOW - 5 * MIN,
            nextStageIndex = 2, nextFireAtMs = NOW + MIN, chain = TEST_CHAIN,
        )
        val oldId = h.dao.upsert(inflight)

        h.engine.rearmGroup(100L)

        verify { h.scheduler.cancel(oldId) }
        val rows = h.dao.rows.values.filter { it.reminderId == 1L }
        assertEquals(1, rows.size)
        assertEquals(ChainJson.encode(newChain), rows.single().chainSnapshotJson)
    }

    @Test fun rearmDefaultChainGroups_rearms_only_groups_without_override() = runTest {
        // A global stage-chain edit must reach reminders in groups that use the
        // default chain, but leave groups with their own override alone.
        val h = EngineHarness(now = NOW)
        val defaultGroup = group(id = 10L, overrideChain = null)
        val overrideGroup = group(id = 20L, overrideChain = TEST_CHAIN)
        coEvery { h.repo.getGroups() } returns listOf(defaultGroup, overrideGroup)
        coEvery { h.repo.getGroup(10L) } returns defaultGroup
        coEvery { h.repo.getGroup(20L) } returns overrideGroup
        coEvery { h.repo.getAllReminders() } returns listOf(
            reminder(id = 1L, groupId = 10L, nextFireAt = NOW + 60 * MIN),
            reminder(id = 2L, groupId = 20L, nextFireAt = NOW + 60 * MIN),
        )
        val defaultEsc = h.dao.upsert(
            activeEntity(id = 0L, reminderId = 1L, startedAtMs = NOW + 60 * MIN, nextStageIndex = 0, nextFireAtMs = NOW + 50 * MIN)
        )
        val overrideEsc = h.dao.upsert(
            activeEntity(id = 0L, reminderId = 2L, startedAtMs = NOW + 60 * MIN, nextStageIndex = 0, nextFireAtMs = NOW + 50 * MIN)
        )

        h.engine.rearmDefaultChainGroups()

        verify { h.scheduler.cancel(defaultEsc) }
        verify(exactly = 0) { h.scheduler.cancel(overrideEsc) }
    }

    @Test fun rearmGroup_paused_group_is_a_noop() = runTest {
        // Pause owns arming; rearm must not tear down a paused group's escalations.
        val h = EngineHarness(now = NOW)
        val g = group(id = 100L, pausedUntilMs = NOW + 60 * MIN)
        coEvery { h.repo.getGroup(100L) } returns g
        coEvery { h.repo.getAllReminders() } returns listOf(reminder(id = 1L, groupId = 100L, nextFireAt = NOW + 60 * MIN))
        val old = activeEntity(
            id = 0L, reminderId = 1L, startedAtMs = NOW + 60 * MIN,
            nextStageIndex = 0, nextFireAtMs = NOW + 50 * MIN,
        )
        val id = h.dao.upsert(old)

        h.engine.rearmGroup(100L)

        verify(exactly = 0) { h.scheduler.cancel(any()) }
        assertEquals(id, h.dao.getById(id)?.id)
    }
}
