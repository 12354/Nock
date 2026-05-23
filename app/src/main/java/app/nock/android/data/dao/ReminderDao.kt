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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
