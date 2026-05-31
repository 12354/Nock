package app.nock.android.domain.escalation

import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.entity.PendingTelegramDeletionEntity

class FakePendingTelegramDeletionDao : PendingTelegramDeletionDao {
    private val ids = linkedSetOf<Long>()

    override suspend fun insert(e: PendingTelegramDeletionEntity) {
        ids.add(e.messageId)
    }

    override suspend fun getAll(): List<PendingTelegramDeletionEntity> =
        ids.map { PendingTelegramDeletionEntity(it) }

    override suspend fun delete(messageId: Long) {
        ids.remove(messageId)
    }

    fun snapshot(): Set<Long> = ids.toSet()
}
