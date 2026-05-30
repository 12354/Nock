package app.nock.android.domain.escalation

import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.entity.ActiveEscalationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [ActiveEscalationDao] so the stateful escalation flows (snooze
 * re-reads its row, done re-arms, fire updates the cursor) behave like the real
 * Room table without a database. Mirrors Room's auto-increment id on insert.
 */
class FakeActiveEscalationDao : ActiveEscalationDao {
    val rows = LinkedHashMap<Long, ActiveEscalationEntity>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<ActiveEscalationEntity>> =
        flowOf(rows.values.sortedBy { it.nextFireAtMs })

    override suspend fun getAll(): List<ActiveEscalationEntity> =
        rows.values.sortedBy { it.nextFireAtMs }

    override suspend fun getById(id: Long): ActiveEscalationEntity? = rows[id]

    override suspend fun getByReminderId(reminderId: Long): ActiveEscalationEntity? =
        rows.values.firstOrNull { it.reminderId == reminderId }

    override suspend fun upsert(e: ActiveEscalationEntity): Long {
        val id = if (e.id == 0L) nextId++ else e.id
        rows[id] = e.copy(id = id)
        return id
    }

    override suspend fun update(e: ActiveEscalationEntity) {
        rows[e.id] = e
    }

    override suspend fun delete(e: ActiveEscalationEntity) {
        rows.remove(e.id)
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
    }

    override suspend fun deleteByReminderId(reminderId: Long) {
        rows.values.filter { it.reminderId == reminderId }.forEach { rows.remove(it.id) }
    }

    override suspend fun clear() {
        rows.clear()
    }
}
