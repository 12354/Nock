package app.nock.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.nock.android.data.entity.PendingTelegramDeletionEntity

@Dao
interface PendingTelegramDeletionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: PendingTelegramDeletionEntity)

    @Query("SELECT * FROM pending_telegram_deletions")
    suspend fun getAll(): List<PendingTelegramDeletionEntity>

    @Query("DELETE FROM pending_telegram_deletions WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)
}
