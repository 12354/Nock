package app.nock.android.data.dao

import androidx.room.*
import app.nock.android.data.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortIndex ASC, id ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortIndex ASC, id ASC")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: Long): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(g: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(gs: List<GroupEntity>)

    @Update
    suspend fun update(g: GroupEntity)

    @Delete
    suspend fun delete(g: GroupEntity)

    @Query("UPDATE groups SET pausedUntilMs = :until WHERE id = :id")
    suspend fun setPause(id: Long, until: Long?)

    @Query("DELETE FROM groups")
    suspend fun clear()
}
