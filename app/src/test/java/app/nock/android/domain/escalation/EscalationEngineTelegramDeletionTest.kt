package app.nock.android.domain.escalation

import app.nock.android.data.entity.ActiveEscalationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationEngineTelegramDeletionTest {

    private fun activeRow(reminderId: Long, csv: String) = ActiveEscalationEntity(
        reminderId = reminderId,
        label = "Test",
        fireCount = 5,
        lastFiredAt = 0L,
        snoozedUntil = null,
        sentTelegramMessageIdsCsv = csv,
    )

    @Test
    fun snooze_transientDeleteFailure_keepsIdQueuedAndRetriesLater() = runBlocking {
        val activeDao = FakeActiveEscalationDao()
        val pendingDao = FakePendingTelegramDeletionDao()
        val telegram = FakeTelegramSender(deleteResolved = false)
        val engine = EngineTestSupport.engine(
            activeDao = activeDao,
            pendingDeletionDao = pendingDao,
            telegram = telegram,
            clock = { 1_000L },
        )
        activeDao.insert(activeRow(7L, "111,222"))

        engine.snooze(7L, 60_000L)

        // csv was cleared on the row...
        assertTrue(activeDao.getByReminderId(7L)!!.sentTelegramMessageIdsCsv.isBlank())
        // ...but the ids survive in the durable queue because the delete failed.
        assertEquals(setOf(111L, 222L), pendingDao.snapshot())
        assertTrue(telegram.deleteAttempts.containsAll(listOf(111L, 222L)))

        // The network recovers; a later alarm tick on a fresh reminder triggers a
        // flush that drains the queue.
        telegram.deleteResolved = true
        telegram.deleteAttempts.clear()
        engine.onAlarmFired(999L) // no active row, but flush still runs first

        assertTrue("queue should be empty after successful retry", pendingDao.snapshot().isEmpty())
        assertTrue(telegram.deleteAttempts.containsAll(listOf(111L, 222L)))
    }

    @Test
    fun done_transientThenSuccess_eventuallyDeletes() = runBlocking {
        val activeDao = FakeActiveEscalationDao()
        val pendingDao = FakePendingTelegramDeletionDao()
        val telegram = FakeTelegramSender(deleteResolved = false)
        val engine = EngineTestSupport.engine(
            activeDao = activeDao,
            pendingDeletionDao = pendingDao,
            telegram = telegram,
        )
        activeDao.insert(activeRow(5L, "999"))

        engine.done(5L)
        assertEquals(setOf(999L), pendingDao.snapshot())

        telegram.deleteResolved = true
        engine.rescheduleAll() // flush on boot/time-change
        assertTrue(pendingDao.snapshot().isEmpty())
    }

    @Test
    fun snoozeThenDone_withWorkingDelete_messageDeletedAndQueueEmpty() = runBlocking {
        // Reproduces the original observed scenario: fire -> snooze -> done.
        val activeDao = FakeActiveEscalationDao()
        val pendingDao = FakePendingTelegramDeletionDao()
        val telegram = FakeTelegramSender(deleteResolved = true)
        val engine = EngineTestSupport.engine(
            activeDao = activeDao,
            pendingDeletionDao = pendingDao,
            telegram = telegram,
            clock = { 1_000L },
        )
        activeDao.insert(activeRow(7L, "111"))

        engine.snooze(7L, 60_000L)
        engine.done(7L)

        assertTrue(telegram.deleteAttempts.contains(111L))
        assertTrue(pendingDao.snapshot().isEmpty())
        assertFalse(activeDao.getAll().any { it.reminderId == 7L })
    }
}
