package app.nock.android.data.dao

import androidx.room.*
import app.nock.android.data.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY COALESCE(nextFireAt, 9223372036854775807) ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE groupId = :groupId ORDER BY COALESCE(nextFireAt, 9223372036854775807) ASC")
    fun observeByGroup(groupId: Long): Flow<List<ReminderEntity>>

    // Upsert (not @Insert(REPLACE)): REPLACE would delete the existing row and
    // re-insert it, cascade-deleting any in-flight active_escalations
    // (active_escalations FK reminderId ON DELETE CASCADE). @Upsert updates in
    // place, so editing a reminder preserves its running escalation.
    @Upsert
    suspend fun insert(r: ReminderEntity): Long

    @Upsert
    suspend fun upsertAll(rs: List<ReminderEntity>)

    @Update
    suspend fun update(r: ReminderEntity)

    @Delete
    suspend fun delete(r: ReminderEntity)

    @Query("UPDATE reminders SET nextFireAt = :next, lastCompletedAt = :completed WHERE id = :id")
    suspend fun updateFireState(id: Long, next: Long?, completed: Long?)

    @Query("DELETE FROM reminders")
    suspend fun clear()
}
