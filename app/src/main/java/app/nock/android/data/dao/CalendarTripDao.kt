package app.nock.android.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import app.nock.android.data.entity.CalendarTripEntity

@Dao
interface CalendarTripDao {
    @Query("SELECT * FROM calendar_trips")
    suspend fun getAll(): List<CalendarTripEntity>

    @Query("SELECT * FROM calendar_trips WHERE reminderId = :reminderId")
    suspend fun getByReminderId(reminderId: Long): CalendarTripEntity?

    // Keyed by event id + start so distinct instances of a recurring event each
    // get their own trip row rather than colliding on the shared event id.
    @Query("SELECT * FROM calendar_trips WHERE eventId = :eventId AND eventStartMs = :eventStartMs")
    suspend fun getByEvent(eventId: Long, eventStartMs: Long): CalendarTripEntity?

    @Upsert
    suspend fun upsert(t: CalendarTripEntity): Long

    @Delete
    suspend fun delete(t: CalendarTripEntity)

    @Query("DELETE FROM calendar_trips WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)

    @Query("DELETE FROM calendar_trips")
    suspend fun clear()
}
