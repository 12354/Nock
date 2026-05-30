package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmService
import app.nock.android.domain.model.Schedule
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
}
