package app.nock.android.domain.escalation

import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arming / rescheduling paths: [startEscalationAt], boot replay, [rescheduleAll],
 * [fireUnlockReminders]. Time and the device clock are faked throughout.
 */
class EscalationEngineScheduleTest {

    // --- Pausing ------------------------------------------------------------

    @Test fun paused_group_does_not_arm_anything() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(schedule = Schedule.Daily(listOf(10 * 60)))
        h.stubReminderAndGroup(r, group(pausedUntilMs = NOW + 60 * MIN))

        h.engine.startEscalationAt(r, NOW + 30 * MIN)

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        assertTrue(h.dao.rows.isEmpty())
    }

    @Test fun pause_window_just_expired_arms_normally() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        // pausedUntil == now -> isPaused is false (strictly greater required).
        h.stubReminderAndGroup(r, group(pausedUntilMs = NOW))

        h.engine.startEscalationAt(r, NOW + 30 * MIN)

        verify { h.scheduler.scheduleStage(any(), any(), any()) }
        assertEquals(1, h.dao.rows.size)
    }

    // --- Re-arming a moved reminder cancels its prior alarm ----------------

    @Test fun rearming_cancels_and_replaces_the_previous_escalation() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // An escalation is already armed for this reminder (e.g. it is mid-chain
        // with ALARM_VIBRATE queued 2 min out).
        val old = activeEntity(id = 5, nextStageIndex = 2, nextFireAtMs = NOW + 2 * MIN)
        h.dao.upsert(old)

        // The reminder is moved far into the future and re-armed.
        h.engine.startEscalationAt(r, NOW + 24 * 60 * MIN)

        // The previously scheduled alarm is cancelled, the old row is gone, and
        // only the freshly-armed escalation remains.
        verify { h.scheduler.cancel(5L) }
        val row = h.dao.rows.values.single()
        assertEquals(0, row.nextStageIndex)
        assertEquals(NOW + 24 * 60 * MIN - 10 * MIN, row.nextFireAtMs)
    }

    @Test fun rearming_dismisses_the_previous_chains_posted_notification() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // The reminder is mid-escalation: a NOTIFICATION/pre-alarm stage has
        // already posted a status-bar notification keyed by the escalation id (5).
        h.dao.upsert(activeEntity(id = 5, nextStageIndex = 3, nextFireAtMs = NOW + 2 * MIN))

        // Move the reminder a day into the future.
        h.engine.startEscalationAt(r, NOW + 24 * 60 * MIN)

        // The notification posted for the old occurrence must be dismissed, not
        // left validating for a time the reminder no longer fires at.
        verify { h.notifier.cancel(5L) }
    }

    @Test fun rearming_deletes_the_previous_chains_sent_telegrams() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // The escalation being moved already sent two Telegram messages.
        h.dao.upsert(
            activeEntity(
                id = 5,
                nextStageIndex = 2,
                nextFireAtMs = NOW + 2 * MIN,
                sentTelegramMessageIdsCsv = "11,22",
            )
        )

        h.engine.startEscalationAt(r, NOW + 24 * 60 * MIN)

        // Moving the alarm undoes the abandoned chain's side effects, just like
        // snooze/cancel/done do.
        coVerify { h.telegram.deleteMessage(11L) }
        coVerify { h.telegram.deleteMessage(22L) }
    }

    // --- Stage selection on arm --------------------------------------------

    @Test fun future_trigger_with_short_lead_skips_past_silent_pre_stage() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // Trigger only 2 min out: SILENT (-10 min) is already 8 min in the past,
        // so we must start at TELEGRAM (idx 1), not fire SILENT immediately.
        val scheduledAt = NOW + 2 * MIN

        h.engine.startEscalationAt(r, scheduledAt)

        val row = h.dao.rows.values.single()
        assertEquals(1, row.nextStageIndex)
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(any(), any(), capture(typeSlot)) }
        assertEquals(StageType.TELEGRAM, typeSlot.captured)
    }

    @Test fun far_future_trigger_starts_at_silent() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // A full day out: even the -10 min SILENT pre-stage is still in the future.
        val scheduledAt = NOW + 24 * 60 * MIN

        h.engine.startEscalationAt(r, scheduledAt)

        val row = h.dao.rows.values.single()
        assertEquals(0, row.nextStageIndex)
        assertEquals(scheduledAt - 10 * MIN, row.nextFireAtMs)
    }

    @Test fun overdue_trigger_jumps_to_the_due_stage() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // Trigger was 16 min ago -> ALARM (last) is due.
        val scheduledAt = NOW - 16 * MIN

        h.engine.startEscalationAt(r, scheduledAt)

        val row = h.dao.rows.values.single()
        assertEquals(TEST_CHAIN.lastIndex, row.nextStageIndex)
    }

    @Test fun first_fire_is_floored_at_now_plus_one_second() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        // Overdue trigger means the computed absolute time is in the past; the
        // armed alarm must be floored to now + 1s, never the past.
        val scheduledAt = NOW - 16 * MIN

        h.engine.startEscalationAt(r, scheduledAt)

        val row = h.dao.rows.values.single()
        assertEquals(NOW + 1_000L, row.nextFireAtMs)
    }

    @Test fun onUnlock_skips_pre_trigger_stages() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(schedule = Schedule.OnUnlock(NOW))
        h.stubReminderAndGroup(r, group())

        // Unlock happens "now"; pre-trigger (SILENT @ -10) and at-trigger stages
        // would all be due immediately, so we start at the first strictly-future
        // stage: TELEGRAM (+5 min).
        h.engine.startEscalationAt(r, NOW)

        val row = h.dao.rows.values.single()
        assertEquals(1, row.nextStageIndex)
        assertEquals(NOW + 5 * MIN, row.nextFireAtMs)
    }

    // --- Boot replay --------------------------------------------------------

    @Test fun boot_arms_first_stage_at_now_plus_one_second() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())

        h.engine.startEscalationFromBoot(r)

        val row = h.dao.rows.values.single()
        assertEquals(0, row.nextStageIndex)
        assertEquals(NOW + 1_000L, row.nextFireAtMs)
        verify { h.scheduler.scheduleStage(any(), eq(NOW + 1_000L), eq(StageType.SILENT)) }
    }

    @Test fun boot_does_nothing_for_paused_group() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group(pausedUntilMs = NOW + 60 * MIN))

        h.engine.startEscalationFromBoot(r)

        assertTrue(h.dao.rows.isEmpty())
    }

    // --- scheduleNextFireForReminder ---------------------------------------

    @Test fun scheduleNextFire_updates_state_and_arms_next_occurrence() = runTest {
        val h = EngineHarness(now = NOW)
        // Daily at 10:00; now is 09:00 UTC -> next fire is today 10:00.
        val r = reminder(schedule = Schedule.Daily(listOf(10 * 60)))
        coEvery { h.repo.getReminder(r.id) } returns r
        coEvery { h.repo.getGroup(any()) } returns group()

        val next = h.engine.scheduleNextFireForReminder(r.id)

        coVerify { h.repo.updateFireState(r.id, next, r.lastCompletedAt) }
        assertEquals(1, h.dao.rows.size)
    }

    @Test fun scheduleNextFire_returns_null_for_unknown_reminder() = runTest {
        val h = EngineHarness(now = NOW)
        coEvery { h.repo.getReminder(any()) } returns null

        val next = h.engine.scheduleNextFireForReminder(999L)

        assertEquals(null, next)
    }

    // --- rescheduleAll ------------------------------------------------------

    @Test fun rescheduleAll_rearms_existing_actives() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(nextStageIndex = 2, nextFireAtMs = NOW + 5 * MIN)
        h.dao.upsert(row)
        coEvery { h.repo.getAllReminders() } returns emptyList()

        h.engine.rescheduleAll()

        verify {
            h.scheduler.scheduleStage(eq(row.id), eq(NOW + 5 * MIN), eq(StageType.ALARM_VIBRATE))
        }
    }

    @Test fun rescheduleAll_never_fires_onUnlock_reminders() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(schedule = Schedule.OnUnlock(NOW), nextFireAt = NOW - 5 * MIN)
        coEvery { h.repo.getAllReminders() } returns listOf(r)
        coEvery { h.repo.getReminder(r.id) } returns r
        coEvery { h.repo.getGroup(any()) } returns group()

        h.engine.rescheduleAll()

        // No escalation row created for the OnUnlock reminder.
        assertTrue(h.dao.rows.isEmpty())
        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
    }

    @Test fun rescheduleAll_replays_overdue_time_based_reminder_via_boot_path() = runTest {
        val h = EngineHarness(now = NOW)
        // Overdue: nextFireAt in the past -> boot replay arms first stage at now+1s.
        val r = reminder(
            schedule = Schedule.Daily(listOf(10 * 60)),
            nextFireAt = NOW - 30 * MIN,
        )
        coEvery { h.repo.getAllReminders() } returns listOf(r)
        coEvery { h.repo.getReminder(r.id) } returns r
        coEvery { h.repo.getGroup(any()) } returns group()

        h.engine.rescheduleAll()

        val row = h.dao.rows.values.single()
        assertEquals(0, row.nextStageIndex)
        assertEquals(NOW + 1_000L, row.nextFireAtMs)
    }

    // --- fireUnlockReminders -----------------------------------------------

    @Test fun fireUnlock_starts_only_armed_onUnlock_reminders() = runTest {
        val h = EngineHarness(now = NOW)
        val armed = reminder(id = 1, schedule = Schedule.OnUnlock(NOW), nextFireAt = NOW)
        val disarmed = reminder(id = 2, schedule = Schedule.OnUnlock(NOW), nextFireAt = null)
        val timeBased = reminder(id = 3, schedule = Schedule.Daily(listOf(10 * 60)), nextFireAt = NOW)
        coEvery { h.repo.getAllReminders() } returns listOf(armed, disarmed, timeBased)
        coEvery { h.repo.getGroup(any()) } returns group()

        h.engine.fireUnlockReminders()

        // Only the armed OnUnlock reminder spawns an escalation.
        assertEquals(1, h.dao.rows.size)
        assertEquals(armed.id, h.dao.rows.values.single().reminderId)
    }

    @Test fun fireUnlock_skips_reminder_already_escalating() = runTest {
        val h = EngineHarness(now = NOW)
        val armed = reminder(id = 1, schedule = Schedule.OnUnlock(NOW), nextFireAt = NOW)
        // Pre-existing active row for the same reminder.
        h.dao.upsert(activeEntity(reminderId = armed.id, nextStageIndex = 1, nextFireAtMs = NOW))
        coEvery { h.repo.getAllReminders() } returns listOf(armed)
        coEvery { h.repo.getGroup(any()) } returns group()

        h.engine.fireUnlockReminders()

        // Still just the one pre-existing row; no duplicate chain spawned.
        assertEquals(1, h.dao.rows.size)
    }
}
