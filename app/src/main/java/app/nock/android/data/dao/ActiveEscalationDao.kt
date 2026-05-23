package app.nock.android.data.dao

import androidx.room.*
import app.nock.android.data.entity.ActiveEscalationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveEscalationDao {
    @Query("SELECT * FROM active_escalations")
    fun observeAll(): Flow<List<ActiveEscalationEntity>>

    @Query("SELECT * FROM active_escalations")
    suspend fun getAll(): List<ActiveEscalationEntity>

    @Query("SELECT * FROM active_escalations WHERE id = :id")
    suspend fun getById(id: Long): ActiveEscalationEntity?

    @Query("SELECT * FROM active_escalations WHERE reminderId = :reminderId")
    suspend fun getByReminderId(reminderId: Long): ActiveEscalationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ActiveEscalationEntity): Long

    @Update
    suspend fun update(e: ActiveEscalationEntity)

    @Delete
    suspend fun delete(e: ActiveEscalationEntity)

    @Query("DELETE FROM active_escalations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM active_escalations WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)

    @Query("DELETE FROM active_escalations")
    suspend fun clear()
}
