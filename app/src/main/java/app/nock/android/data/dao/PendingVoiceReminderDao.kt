package app.nock.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.nock.android.data.entity.PendingVoiceReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingVoiceReminderDao {
    @Query("SELECT * FROM pending_voice_reminders ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingVoiceReminderEntity>>

    @Query("SELECT * FROM pending_voice_reminders ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingVoiceReminderEntity>

    @Query("SELECT * FROM pending_voice_reminders WHERE id = :id")
    suspend fun getById(id: Long): PendingVoiceReminderEntity?

    @Insert
    suspend fun insert(p: PendingVoiceReminderEntity): Long

    @Update
    suspend fun update(p: PendingVoiceReminderEntity)

    @Query("DELETE FROM pending_voice_reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
}
