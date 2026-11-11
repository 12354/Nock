package app.nock.android.domain.escalation

import io.mockk.coEvery
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "pause all" switch: a global pause-until that the engine applies to every
 * group exactly as it applies a group's own pause.
 */
class EscalationEngineGlobalPauseTest {

    @Test fun global_pause_blocks_arming_in_an_unpaused_group() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        coEvery { h.settings.getGlobalPauseUntil() } returns NOW + 60 * MIN

        h.engine.startEscalationAt(r, NOW + 30 * MIN)

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        assertTrue(h.dao.rows.isEmpty())
    }

    @Test fun expired_global_pause_arms_normally() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        coEvery { h.settings.getGlobalPauseUntil() } returns NOW

        h.engine.startEscalationAt(r, NOW + 30 * MIN)

        assertEquals(1, h.dao.rows.size)
    }

    @Test fun armed_stage_is_held_until_the_global_pause_ends() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(nextFireAt = NOW)
        h.stubReminderAndGroup(r, group())
        val until = NOW + 45 * MIN
        coEvery { h.settings.getGlobalPauseUntil() } returns until
        val esc = activeEntity(id = 3L, nextStageIndex = 1, nextFireAtMs = NOW)
        h.dao.upsert(esc)

        h.engine.onAlarmFired(3L)

        val row = h.dao.rows.getValue(3L)
        assertEquals(until, row.nextFireAtMs)
        assertEquals(1, row.nextStageIndex)
        verify { h.scheduler.scheduleStage(3L, until, any()) }
        verify(exactly = 0) { h.notifier.showTelegram(any(), any(), any()) }
    }

    @Test fun held_stage_waits_for_the_later_of_group_and_global_pause() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder(nextFireAt = NOW)
        val groupUntil = NOW + 90 * MIN
        h.stubReminderAndGroup(r, group(pausedUntilMs = groupUntil))
        coEvery { h.settings.getGlobalPauseUntil() } returns NOW + 30 * MIN
        h.dao.upsert(activeEntity(id = 3L, nextStageIndex = 1, nextFireAtMs = NOW))

        h.engine.onAlarmFired(3L)

        assertEquals(groupUntil, h.dao.rows.getValue(3L).nextFireAtMs)
    }

    @Test fun isReminderPaused_reflects_global_pause() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group())
        assertFalse(h.engine.isReminderPaused(r))

        coEvery { h.settings.getGlobalPauseUntil() } returns NOW + MIN
        assertTrue(h.engine.isReminderPaused(r))
    }

    @Test fun rearmAllGroups_skips_self_paused_groups() = runTest {
        val h = EngineHarness(now = NOW)
        val free = group(id = 1L)
        val held = group(id = 2L, pausedUntilMs = NOW + 60 * MIN)
        val r1 = reminder(id = 11L, groupId = 1L, nextFireAt = NOW + 30 * MIN)
        val r2 = reminder(id = 12L, groupId = 2L, nextFireAt = NOW + 30 * MIN)
        coEvery { h.repo.getGroups() } returns listOf(free, held)
        coEvery { h.repo.getGroup(1L) } returns free
        coEvery { h.repo.getGroup(2L) } returns held
        coEvery { h.repo.getAllReminders() } returns listOf(r1, r2)

        h.engine.rearmAllGroups()

        assertEquals(listOf(11L), h.dao.rows.values.map { it.reminderId })
    }
}
