package app.nock.android.domain.escalation

import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.domain.model.VibrationPattern
import app.nock.android.domain.model.VibrationPulse
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A "regular" reminder fires its custom vibration pattern once and auto-completes:
 * no Telegram, no loud stage, no repeat, no Done required. Its snapshotted chain is
 * a single VIBRATE stage at offset 0.
 */
class EscalationEngineRegularVibrationTest {

    private val regularChain = EscalationChain(
        stages = listOf(StageConfig(StageType.VIBRATE, 0L)),
        repeatIntervalMs = 10 * MIN,
    )

    private val pattern = VibrationPattern(listOf(VibrationPulse.SHORT, VibrationPulse.LONG))

    @Test fun recurring_regular_reminder_buzzes_once_then_advances() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(
            schedule = Schedule.Daily(listOf(9 * 60)),
            simpleVibration = true,
            vibrationPattern = pattern,
        )
        h.stubReminderAndGroup(r, group())
        val row = activeEntity(
            startedAtMs = NOW,
            nextStageIndex = 0,
            nextFireAtMs = NOW,
            chain = regularChain,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        // The gentle nudge fired with this reminder's pattern…
        val patternSlot = slot<VibrationPattern>()
        verify { h.notifier.showRegular(any(), any(), eq(row.id), capture(patternSlot)) }
        assertEquals(pattern, patternSlot.captured)

        // …no escalation side effects…
        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
        verify(exactly = 0) { h.notifier.showTelegram(any(), any(), any()) }
        coVerify(exactly = 0) { h.telegram.send(any(), any()) }

        // …the fired occurrence auto-completed (no Done needed) and the recurring
        // schedule rolled forward: exactly one row remains for the reminder and it's
        // armed for a LATER occurrence than the one that just fired.
        val next = h.dao.getByReminderId(REMINDER_ID)!!
        assertEquals(0, next.nextStageIndex)
        assertTrue("next occurrence must be after the one that fired", next.nextFireAtMs > NOW)
        val typeSlot = slot<StageType>()
        verify { h.scheduler.scheduleStage(eq(next.id), any(), capture(typeSlot)) }
        assertEquals(StageType.VIBRATE, typeSlot.captured)
    }

    @Test fun one_time_regular_reminder_is_retired_after_firing() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(
            schedule = Schedule.OneShot(NOW),
            simpleVibration = true,
            vibrationPattern = pattern,
        )
        h.stubReminderAndGroup(r, group())
        val row = activeEntity(
            startedAtMs = NOW,
            nextStageIndex = 0,
            nextFireAtMs = NOW,
            chain = regularChain,
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showRegular(any(), any(), eq(row.id), any()) }
        // A one-time reminder has no next occurrence: the row is dropped and the
        // reminder retired, so nothing stays armed.
        coVerify { h.repo.deleteReminder(any()) }
        assertNull(h.dao.getByReminderId(REMINDER_ID))
    }

    @Test fun null_pattern_falls_back_to_default() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(
            schedule = Schedule.OneShot(NOW),
            simpleVibration = true,
            vibrationPattern = null,
        )
        h.stubReminderAndGroup(r, group())
        val row = activeEntity(nextStageIndex = 0, nextFireAtMs = NOW, chain = regularChain)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        val patternSlot = slot<VibrationPattern>()
        verify { h.notifier.showRegular(any(), any(), any(), capture(patternSlot)) }
        assertEquals(VibrationPattern.DEFAULT, patternSlot.captured)
    }
}
