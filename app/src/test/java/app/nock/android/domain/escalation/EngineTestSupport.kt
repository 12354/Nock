package app.nock.android.domain.escalation

import app.nock.android.telegram.TelegramSender

/**
 * Shared builders for EscalationEngine unit tests.
 */
object EngineTestSupport {

    fun engine(
        activeDao: FakeActiveEscalationDao = FakeActiveEscalationDao(),
        pendingDeletionDao: FakePendingTelegramDeletionDao = FakePendingTelegramDeletionDao(),
        telegram: TelegramSender = NoopTelegramSender(),
        notifier: RecordingNotifier = RecordingNotifier(),
        clock: () -> Long = { 0L },
    ): EscalationEngine =
        EscalationEngine(
            activeDao = activeDao,
            pendingVoiceDao = FakePendingVoiceReminderDao(),
            pendingDeletionDao = pendingDeletionDao,
            telegram = telegram,
            notifier = notifier,
            clock = clock,
        )
}

/**
 * A TelegramSender test double that never touches the network.
 *
 * deleteMessage() returns true (resolved) by default so the pending-deletion
 * queue drains in tests, mirroring the real "no token configured" behavior.
 */
class NoopTelegramSender : TelegramSender(botToken = null, chatId = null)

/**
 * A controllable TelegramSender double for tests that need to simulate transient
 * delete failures. When [deleteResolved] is false, deleteMessage() reports the
 * deletion as unresolved (kept in the retry queue); flip it to true to simulate
 * a later successful retry. Records every id passed to deleteMessage().
 */
class FakeTelegramSender(
    var deleteResolved: Boolean = true,
) : TelegramSender(botToken = null, chatId = null) {
    val deleteAttempts = mutableListOf<Long>()

    override fun deleteMessage(messageId: Long): Boolean {
        deleteAttempts.add(messageId)
        return deleteResolved
    }
}
