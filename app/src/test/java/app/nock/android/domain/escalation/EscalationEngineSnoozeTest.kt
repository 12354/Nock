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

    @Test fun snooze_on_non_last_stage_mutes_one_interval_then_resumes_at_due_stage() = runTest {
        val h = EngineHarness(now = NOW)
        // The TELEGRAM stage (idx 1, offset +5) is firing now: started 5 min ago. The
        // later ALARM_VIBRATE (+8) and ALARM (+10) are due within the snooze window.
        val row = activeEntity(
            startedAtMs = NOW - 5 * MIN,
            nextStageIndex = 1,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "111,222",
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        // Muted for exactly one repeat interval (no later stage rings during it),
        // then resumes at the LAST stage that came due in that window on the unchanged
        // timeline — here the ALARM, due 10 min after the started-at (NOW+5). The
        // intermediate ALARM_VIBRATE it skipped stays skipped.
        val atSlot = slot<Long>()
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(row.id), capture(atSlot), capture(typeSlot)) }
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, atSlot.captured)
        assertEquals(StageType.ALARM, typeSlot.captured)
        val updated = h.dao.getById(row.id)!!
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals(TEST_CHAIN.lastIndex, updated.nextStageIndex)
        assertEquals(NOW - 5 * MIN, updated.startedAtMs) // timeline NOT shifted
        assertEquals("", updated.sentTelegramMessageIdsCsv)
    }

    @Test fun snooze_does_not_delay_the_escalation_timeline() = runTest {
        // The crux of the ADHD design: snooze must never push the escalation out, or
        // the loud alarm could be buried by snoozing forever. Snoozing the SILENT
        // pre-alarm heads-up gives one interval of silence but leaves startedAtMs
        // untouched, so the loud ALARM still arrives on its original schedule.
        val h = EngineHarness(now = NOW)
        // SILENT (offset -10) firing now; trigger (and thus the ALARM at +10) sits
        // 10 min out so the ALARM is NOT inside the snooze window this time.
        val triggerAt = NOW + 10 * MIN
        val row = activeEntity(
            startedAtMs = triggerAt,
            nextStageIndex = 0,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        val updated = h.dao.getById(row.id)!!
        // Timeline pinned to the occurrence — the ALARM is still anchored at
        // startedAtMs + 10 min, exactly where it would have been with no snooze.
        assertEquals(triggerAt, updated.startedAtMs)
        // Only SILENT came due within the muted window, so the chain resumes there
        // (a gentle re-nudge), and the louder stages stay on the original schedule.
        assertEquals(NOW + TEST_CHAIN.repeatIntervalMs, updated.nextFireAtMs)
        assertEquals(0, updated.nextStageIndex)
        verify { h.scheduler.scheduleStage(eq(row.id), eq(NOW + TEST_CHAIN.repeatIntervalMs), eq(StageType.SILENT)) }
    }

    @Test fun repeated_snooze_cannot_push_the_loud_alarm_out_indefinitely() = runTest {
        // Snoozing the same early stage twice must not keep deferring the alarm: the
        // second snooze still resumes off the original timeline, so by then the ALARM
        // is the due stage and fires — it cannot be buried.
        val h = EngineHarness(now = NOW)
        val triggerAt = NOW + 10 * MIN
        val row = activeEntity(
            startedAtMs = triggerAt, // SILENT due now; ALARM due at NOW+20
            nextStageIndex = 0,
            nextFireAtMs = NOW,
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)                 // mute until NOW+10, resumes SILENT
        h.clock.advanceBy(10 * MIN)             // ... user snoozes again at NOW+10
        h.engine.snooze(row.id)

        // Second snooze ends at NOW+20, which is the ALARM's original time, so the
        // chain resumes at the ALARM — not pushed any further out.
        val updated = h.dao.getById(row.id)!!
        assertEquals(NOW + 20 * MIN, updated.nextFireAtMs)
        assertEquals(TEST_CHAIN.lastIndex, updated.nextStageIndex)
        verify { h.scheduler.scheduleStage(eq(row.id), eq(NOW + 20 * MIN), eq(StageType.ALARM)) }
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
