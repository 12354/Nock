package app.nock.android.data.dao

import androidx.room.*
import app.nock.android.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingsEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(s: SettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SettingsEntity>)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM settings")
    suspend fun clear()
}
