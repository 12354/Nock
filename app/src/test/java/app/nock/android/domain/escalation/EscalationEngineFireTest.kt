package app.nock.android.domain.escalation

import app.nock.android.domain.model.StageType
import app.nock.android.telegram.TelegramResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [EscalationEngine.onAlarmFired] — the heart of the timing logic. Time is faked
 * so we can drive late delivery, clocks moving backwards, and repeat scheduling
 * deterministically.
 */
class EscalationEngineFireTest {

    private fun harnessWithReminder(
        telegramSilentMirror: Boolean = false,
        now: Long = NOW,
    ): EngineHarness {
        val h = EngineHarness(now = now)
        h.stubReminderAndGroup(
            reminder(),
            group(telegramSilentMirror = telegramSilentMirror),
        )
        return h
    }

    // --- Clock-rewind sanity check -----------------------------------------

    @Test fun fire_too_early_rearms_and_does_not_escalate() = runTest {
        val h = harnessWithReminder()
        // Row says the next stage is due at NOW + 10 min, but the clock reads NOW
        // (10 min early): the wall clock and the chain disagree (NTP / rewind).
        val dueAt = NOW + 10 * MIN
        val row = activeEntity(nextStageIndex = 3, nextFireAtMs = dueAt)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        // Re-armed at the real due time, nothing fired.
        val atSlot = slot<Long>()
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), capture(typeSlot)) }
        assertEquals(dueAt, atSlot.captured)
        assertEquals(StageType.ALARM, typeSlot.captured)
        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
        coVerify(exactly = 0) { h.telegram.send(any(), any()) }
    }

    @Test fun fire_within_tolerance_still_escalates() = runTest {
        val h = harnessWithReminder()
        // Exactly 1000ms early == the sanity tolerance, so it must NOT be treated
        // as a rewind: it fires normally.
        val dueAt = NOW + 1_000L
        val row = activeEntity(nextStageIndex = 3, nextFireAtMs = dueAt)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showAlarm(any(), any(), eq(row.id)) }
    }

    // --- Per-stage dispatch -------------------------------------------------

    @Test fun fire_silent_stage_shows_silent_notification() = runTest {
        val h = harnessWithReminder()
        // Started 10 min ago so SILENT (offset -10) is the due stage and now is
        // well past nextFireAtMs.
        val row = activeEntity(
            startedAtMs = NOW + 10 * MIN, // trigger is 10 min out; SILENT due now
            nextStageIndex = 0,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showSilent(any(), any(), eq(row.id), eq("")) }
        coVerify(exactly = 0) { h.telegram.send(any(), any()) }
    }

    @Test fun fire_silent_stage_mirrors_to_telegram_when_group_opts_in() = runTest {
        val h = harnessWithReminder(telegramSilentMirror = true)
        coEvery { h.telegram.send(any(), eq(true)) } returns TelegramResult(ok = true, messageId = 555L)
        val row = activeEntity(
            startedAtMs = NOW + 10 * MIN,
            nextStageIndex = 0,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        coVerify { h.telegram.send(any(), eq(true)) }
        // The mirrored message id is recorded for later cleanup.
        assertEquals("555", h.dao.getById(row.id)!!.sentTelegramMessageIdsCsv)
    }

    @Test fun fire_telegram_stage_sends_and_shows_suffix() = runTest {
        val h = harnessWithReminder()
        coEvery { h.telegram.send(any(), eq(false)) } returns TelegramResult(ok = true, messageId = 999L)
        // Started 5 min ago -> TELEGRAM (offset +5) is the due stage.
        val row = activeEntity(
            startedAtMs = NOW - 5 * MIN,
            nextStageIndex = 1,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        coVerify { h.telegram.send(any(), eq(false)) }
        verify { h.notifier.showSilent(any(), any(), eq(row.id), eq(" (Telegram sent)")) }
        assertEquals("999", h.dao.getById(row.id)!!.sentTelegramMessageIdsCsv)
    }

    @Test fun fire_alarm_vibrate_stage() = runTest {
        val h = harnessWithReminder()
        val row = activeEntity(
            startedAtMs = NOW - 8 * MIN,
            nextStageIndex = 2,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showAlarmVibrate(any(), any(), eq(row.id)) }
    }

    @Test fun fire_alarm_stage() = runTest {
        val h = harnessWithReminder()
        val row = activeEntity(
            startedAtMs = NOW - 10 * MIN,
            nextStageIndex = 3,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showAlarm(any(), any(), eq(row.id)) }
    }

    // --- Late delivery / catch-up ------------------------------------------

    @Test fun late_fire_jumps_to_the_currently_due_stage() = runTest {
        val h = harnessWithReminder()
        // Trigger was at NOW - 16 min. The queued cursor is still TELEGRAM (idx 1),
        // but 16 min elapsed means ALARM (idx 3) is what the user should see.
        val started = NOW - 16 * MIN
        val row = activeEntity(
            startedAtMs = started,
            nextStageIndex = 1,
            nextFireAtMs = NOW - 11 * MIN, // long overdue, well clear of sanity check
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showAlarm(any(), any(), eq(row.id)) }
        verify(exactly = 0) { h.notifier.showSilent(any(), any(), any(), any()) }
    }

    // --- Last vs non-last rescheduling -------------------------------------

    @Test fun last_stage_reschedules_one_repeat_interval_out_and_appends_message() = runTest {
        val h = harnessWithReminder()
        coEvery { h.telegram.send(any(), any()) } returns TelegramResult(ok = true, messageId = null)
        val row = activeEntity(
            startedAtMs = NOW - 10 * MIN,
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "7",
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        val atSlot = slot<Long>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), eq(StageType.ALARM)) }
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
        val updated = h.dao.getById(row.id)!!
        assertEquals(3, updated.nextStageIndex)
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals("7", updated.sentTelegramMessageIdsCsv) // unchanged: no new id
    }

    @Test fun non_last_stage_advances_cursor_to_next_stage() = runTest {
        val h = harnessWithReminder()
        coEvery { h.telegram.send(any(), eq(false)) } returns TelegramResult(ok = true, messageId = 1L)
        // Firing TELEGRAM (idx 1); next is ALARM_VIBRATE (idx 2) at started + 8 min.
        val started = NOW - 5 * MIN
        val row = activeEntity(
            startedAtMs = started,
            nextStageIndex = 1,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        val atSlot = slot<Long>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), eq(StageType.ALARM_VIBRATE)) }
        assertEquals(started + 8 * MIN, atSlot.captured)
        val updated = h.dao.getById(row.id)!!
        assertEquals(2, updated.nextStageIndex)
    }

    @Test fun non_last_next_fire_is_floored_at_now_plus_one_second() = runTest {
        val h = harnessWithReminder()
        coEvery { h.telegram.send(any(), eq(false)) } returns TelegramResult(ok = true, messageId = 1L)
        // started + nextOffset (8 min) is already in the past relative to now, so
        // the next fire must be floored to now + 1s.
        val started = NOW - 30 * MIN
        val row = activeEntity(
            startedAtMs = started,
            nextStageIndex = 1,
            nextFireAtMs = NOW - 25 * MIN,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        // Late delivery jumps to ALARM (last) at 30 min elapsed -> reschedules as repeat.
        val atSlot = slot<Long>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), any()) }
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
    }

    // --- Missing data -------------------------------------------------------

    @Test fun fire_with_missing_reminder_deletes_the_row() = runTest {
        val h = EngineHarness(now = NOW)
        coEvery { h.repo.getReminder(any()) } returns null
        val row = activeEntity(nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        assertNull(h.dao.getById(row.id))
        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
    }

    @Test fun fire_unknown_escalation_is_a_noop() = runTest {
        val h = harnessWithReminder()

        h.engine.onAlarmFired(12345L)

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
    }
}
