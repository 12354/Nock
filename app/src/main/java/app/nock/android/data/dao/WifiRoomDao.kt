package app.nock.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.nock.android.data.entity.WifiRoomEntity
import app.nock.android.data.entity.WifiRoomSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WifiRoomDao {
    @Query("SELECT * FROM wifi_rooms ORDER BY name ASC")
    fun observeRooms(): Flow<List<WifiRoomEntity>>

    @Query("SELECT * FROM wifi_rooms ORDER BY name ASC")
    suspend fun getRooms(): List<WifiRoomEntity>

    @Query("SELECT * FROM wifi_rooms WHERE id = :id")
    suspend fun getRoom(id: Long): WifiRoomEntity?

    @Upsert
    suspend fun upsertRoom(room: WifiRoomEntity): Long

    @Query("DELETE FROM wifi_rooms WHERE id = :id")
    suspend fun deleteRoom(id: Long)

    @Query("SELECT * FROM wifi_room_samples")
    fun observeSamples(): Flow<List<WifiRoomSampleEntity>>

    @Query("SELECT * FROM wifi_room_samples")
    suspend fun getAllSamples(): List<WifiRoomSampleEntity>

    @Insert
    suspend fun insertSample(sample: WifiRoomSampleEntity): Long

    @Query("DELETE FROM wifi_room_samples WHERE roomId = :roomId")
    suspend fun deleteSamplesForRoom(roomId: Long)
}
