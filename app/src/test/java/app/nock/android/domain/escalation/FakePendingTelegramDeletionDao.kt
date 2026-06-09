package app.nock.android.domain.escalation

import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.entity.PendingTelegramDeletionEntity

/**
 * In-memory stand-in for the durable pending-deletion queue.
 *
 * Internally synchronized: the engine drains this queue from
 * [EscalationEngine.flushPendingTelegramDeletions], which runs OUTSIDE the
 * engine's serialization mutex (so a slow network delete can't block a Done).
 * The concurrent fuzzer therefore hits it from several real threads at once, and
 * the real collaborator (a Room DAO over SQLite) is itself thread-safe — so this
 * fake models that rather than corrupting under the race.
 */
class FakePendingTelegramDeletionDao : PendingTelegramDeletionDao {
    private val lock = Any()
    private val ids = linkedSetOf<Long>()

    override suspend fun insert(e: PendingTelegramDeletionEntity) {
        synchronized(lock) { ids.add(e.messageId) }
    }

    override suspend fun getAll(): List<PendingTelegramDeletionEntity> =
        synchronized(lock) { ids.map { PendingTelegramDeletionEntity(it) } }

    override suspend fun delete(messageId: Long) {
        synchronized(lock) { ids.remove(messageId) }
    }

    fun snapshot(): Set<Long> = synchronized(lock) { ids.toSet() }
}
