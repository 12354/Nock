package app.nock.android.domain.escalation

import app.nock.android.domain.model.Schedule
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable Telegram message-deletion queue. Regression coverage for the leak where
 * a previously-sent Telegram escalation message lingered in the chat after the
 * reminder was snoozed/done because the single fire-and-forget delete attempt
 * failed and the id was already dropped from durable storage.
 *
 * The fix enqueues the ids into [FakePendingTelegramDeletionDao] before the active
 * row's csv is cleared / the row is deleted, and only removes an id once the
 * Telegram API confirms the deletion is resolved.
 */
class EscalationEngineTelegramDeletionTest {

    @Test fun snooze_transient_delete_failure_keeps_id_queued_then_retry_drains_it() = runTest {
        val h = EngineHarness(now = NOW)
        // The delete call fails (transient network error / process torn down).
        coEvery { h.telegram.deleteMessage(any()) } returns false
        val row = activeEntity(
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "111,222",
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)

        // The csv on the row is cleared (snooze re-arms with an empty slate)...
        assertEquals("", h.dao.getById(row.id)!!.sentTelegramMessageIdsCsv)
        // ...but the ids survive durably because the delete did not resolve.
        assertEquals(setOf(111L, 222L), h.pendingDeletionDao.snapshot())

        // The network recovers; a later alarm tick flushes the queue before doing
        // anything else, even for an unrelated/now-missing escalation.
        coEvery { h.telegram.deleteMessage(any()) } returns true
        h.engine.onAlarmFired(404L) // no such row, but the flush still runs first

        assertTrue(h.pendingDeletionDao.snapshot().isEmpty())
    }

    @Test fun done_transient_failure_then_boot_retry_drains_queue() = runTest {
        val h = EngineHarness(now = NOW)
        coEvery { h.repo.getReminder(any()) } returns reminder(schedule = Schedule.OneShot(NOW))
        coEvery { h.telegram.deleteMessage(any()) } returns false
        val row = activeEntity(
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "999",
        )
        h.dao.upsert(row)

        h.engine.done(row.id)

        // Active row gone, but the id is durably queued for retry.
        assertEquals(setOf(999L), h.pendingDeletionDao.snapshot())

        // rescheduleAll runs on boot / time-change and retries the queue.
        coEvery { h.telegram.deleteMessage(any()) } returns true
        h.engine.rescheduleAll()

        assertTrue(h.pendingDeletionDao.snapshot().isEmpty())
    }

    @Test fun snooze_then_done_with_working_delete_removes_message_and_empties_queue() = runTest {
        // Reproduces the original observed scenario: telegram fired -> snooze -> done.
        val h = EngineHarness(now = NOW)
        coEvery { h.repo.getReminder(any()) } returns reminder(schedule = Schedule.OneShot(NOW))
        val row = activeEntity(
            nextStageIndex = TEST_CHAIN.lastIndex,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "111",
        )
        h.dao.upsert(row)

        h.engine.snooze(row.id)
        h.engine.done(row.id)

        coVerify { h.telegram.deleteMessage(111L) }
        assertTrue(h.pendingDeletionDao.snapshot().isEmpty())
        assertFalse(h.dao.rows.values.any { it.reminderId == row.reminderId })
    }
}
