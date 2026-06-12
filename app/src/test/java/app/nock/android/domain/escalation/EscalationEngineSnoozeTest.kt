package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmService
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.domain.model.StageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snooze behaviour of [EscalationEngine]. The device clock is fully faked via
 * [FakeTimeSource], so every "reschedule N minutes from now" assertion is exact.
 */
class EscalationEngineSnoozeTest {

    // AlarmService.ringingEscalationId has a private setter; set it via reflection
    // so we can exercise the "is this escalation the one currently ringing?" branch
    // that gates stopAlarm().
    private fun setRinging(id: Long?) {
        val field = AlarmService::class.java.getDeclaredField("ringingEscalationId")
        field.isAccessible = true
        field.set(null, id)
    }

    @After fun resetRinging() = setRinging(null)

    // The ALARM (last) stage fires at startedAt + its offset, so a row that is
    // genuinely firing the last stage at NOW must have started one offset earlier.
    // snooze() derives the firing stage from elapsed time, so the fixture has to be
    // consistent with that or it would look like the pre-trigger stage is firing.
    private val lastStageStartedAt = NOW - TEST_CHAIN.stages.last().offsetMs

    @Test fun snooze_on_last_stage_reschedules_one_repeat_interval_from_now() = runTest {
        val h = EngineHarness(now = NOW)
        // Currently firing the ALARM (last) stage.
        val row = activeEntity(
            startedAtMs = lastStageStartedAt,
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        val atSlot = slot<Long>()
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), capture(typeSlot)) }
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
        assertEquals(StageType.ALARM, typeSlot.captured)

        val updated = h.dao.getById(row.id)!!
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals(TEST_CHAIN.lastIndex, updated.nextStageIndex)
        assertEquals("", updated.sentTelegramMessageIdsCsv)
    }

    @Test fun snooze_uses_current_clock_not_the_baseline() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(
            startedAtMs = lastStageStartedAt,
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        // Wall clock advances 3 minutes before the user taps snooze.
        h.clock.advanceBy(3 * MIN)
        h.engine.snooze(row.id)

        val atSlot = slot<Long>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), any()) }
        assertEquals(NOW + 3 * MIN + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
    }

    @Test fun snooze_on_non_last_stage_suppresses_whole_chain_for_one_interval() = runTest {
        val h = EngineHarness(now = NOW)
        // The TELEGRAM stage (idx 1, offset +5) is the one firing now: started 5 min
        // ago and the cursor is at it. The later ALARM is still ahead in the chain.
        val row = activeEntity(
            startedAtMs = NOW - 5 * MIN,
            nextStageIndex = 1,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "111,222",
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        // The chain is pushed out a full repeat interval and re-rings the stage that
        // was firing (TELEGRAM) — rather than leaving the later ALARM armed to ring
        // on schedule during the snooze (the old behaviour did nothing here).
        val atSlot = slot<Long>()
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), capture(typeSlot)) }
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
        assertEquals(StageType.TELEGRAM, typeSlot.captured)
        val updated = h.dao.getById(row.id)!!
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals(1, updated.nextStageIndex)
        assertEquals("", updated.sentTelegramMessageIdsCsv)
    }

    @Test fun re_snooze_of_an_already_snoozed_alarm_does_not_regress_the_stage() = runTest {
        val h = EngineHarness(now = NOW)
        // A row already snoozed at the loud ALARM: its anchor is parked in the future
        // (the re-ring hasn't fired yet), so stageDueAt alone would read stage 0.
        // Snoozing again must keep it on the ALARM, not drop back to the heads-up.
        val row = activeEntity(
            startedAtMs = NOW - 5 * MIN,
            anchorMs = NOW + 30 * MIN, // parked ahead by a prior snooze
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW + 30 * MIN,
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(row.id), eq(NOW + TEST_CHAIN.repeatIntervalMs), capture(typeSlot)) }
        assertEquals(StageType.ALARM, typeSlot.captured)
        assertEquals(TEST_CHAIN.lastIndex, h.dao.getById(row.id)!!.nextStageIndex)
    }

    @Test fun snooze_of_pre_alarm_delays_the_loud_alarm_past_its_scheduled_time() = runTest {
        // The bug this fixes: snoozing the SILENT pre-alarm heads-up used to leave the
        // loud ALARM armed to ring at the reminder's scheduled time anyway. Now the
        // whole chain is delayed, so the ALARM lands one repeat interval later than it
        // would have. SILENT (offset -10) is due now, with the trigger 10 min out.
        val h = EngineHarness(now = NOW)
        h.stubReminderAndGroup(reminder(nextFireAt = NOW + 10 * MIN), group())
        val triggerAt = NOW + 10 * MIN
        val row = activeEntity(
            startedAtMs = triggerAt, // SILENT (offset -10) is due now
            nextStageIndex = 0,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        // SILENT re-rings one interval out; startedAtMs (the occurrence) is untouched,
        // and the anchor is shifted so the rest of the chain follows from there.
        val updated = h.dao.getById(row.id)!!
        assertEquals(triggerAt, updated.startedAtMs)
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals(0, updated.nextStageIndex)

        // The loud ALARM (last stage) is now anchored to fire AFTER the original
        // scheduled time (triggerAt), proving the snooze actually held it back.
        // SILENT re-rings at NOW+10, the anchor is NOW+20, so ALARM (offset +10)
        // lands at NOW+30 — well past the NOW+10 trigger.
        val alarmFireAt = updated.anchorMs + TEST_CHAIN.stages.last().offsetMs
        assertTrue(
            "loud alarm must be delayed past the scheduled time ($alarmFireAt vs $triggerAt)",
            alarmFireAt > triggerAt,
        )
    }

    @Test fun snooze_always_tears_down_current_alarm_visuals() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(
            startedAtMs = lastStageStartedAt,
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)
        // This escalation is the one currently ringing, so snooze must silence it.
        setRinging(row.id)

        h.engine.snooze(row.id)

        verify { h.notifier.cancelStageVisuals(row.id) }
        verify { h.notifier.stopAlarm() }
    }

    @Test fun snooze_deletes_every_previously_sent_telegram_message() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "10,20,30",
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        coVerify(exactly = 1) { h.telegram.deleteMessage(10L) }
        coVerify(exactly = 1) { h.telegram.deleteMessage(20L) }
        coVerify(exactly = 1) { h.telegram.deleteMessage(30L) }
    }

    @Test fun snooze_with_no_sent_messages_deletes_nothing() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(nextStageIndex = TEST_CHAIN.lastIndex, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        coVerify(exactly = 0) { h.telegram.deleteMessage(any()) }
    }

    @Test fun snooze_persists_rearm_before_attempting_telegram_cleanup() = runTest {
        val h = EngineHarness(now = NOW)
        val row = activeEntity(
            startedAtMs = lastStageStartedAt,
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "10,20",
        )
        h.dao.upsert(row)
        // A failing/slow Telegram delete must not lose the snoozed state: the
        // repeat is re-armed before the network cleanup runs.
        coEvery { h.telegram.deleteMessage(any()) } throws RuntimeException("network down")

        runCatching { h.engine.snooze(row.id) }

        val updated = h.dao.getById(row.id)!!
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        verify { h.scheduler.scheduleStage(eq(row.id), eq(NOW + TEST_CHAIN.repeatIntervalMs), any()) }
    }

    @Test fun snooze_unknown_escalation_is_a_noop() = runTest {
        val h = EngineHarness(now = NOW)

        h.engine.snooze(999L)

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        verify(exactly = 0) { h.notifier.stopAlarm() }
    }

    @Test fun snooze_falls_back_to_settings_chain_when_snapshot_is_corrupt() = runTest {
        val h = EngineHarness(now = NOW)
        // Corrupt snapshot JSON forces the settings-chain fallback path.
        val row = ActiveEscalationEntity(
            id = 0,
            reminderId = REMINDER_ID,
            startedAtMs = lastStageStartedAt,
            anchorMs = lastStageStartedAt,
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
            chainSnapshotJson = "{not valid json",
            repeatIntervalMs = TEST_CHAIN.repeatIntervalMs,
        )
        val id = h.dao.upsert(row)

        h.engine.snooze(id)

        // Last stage of the settings chain -> reschedules a repeat interval out.
        val atSlot = slot<Long>()
        verify { h.scheduler.scheduleStage(eq(id), capture(atSlot), any()) }
        assertTrue(atSlot.captured >= NOW + TEST_CHAIN.repeatIntervalMs)
        coVerify { h.settings.getStageChain() }
    }
}
