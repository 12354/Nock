package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Side table linking a (trip) reminder to the calendar event it was created
 * from, plus the metadata needed to recompute its traffic-aware leave-by time.
 *
 * The reminder itself stays a plain `OneShot(leaveBy)` in the Trips group, so
 * the escalation engine treats it like any other reminder; this row carries the
 * extra context the trip sync/recompute logic needs.
 *
 * Deliberately NOT a cascade-delete child of reminders: the engine deletes a
 * OneShot reminder when the user presses Done, but this row must outlive that so
 * the next sync can tell "dismissed" (row present, reminder gone) from "new"
 * (no row) and not resurrect a trip the user already acknowledged. The row is
 * cleaned up by [app.nock.android.trip.TripSyncManager] once its event passes.
 */
@Entity(
    tableName = "calendar_trips",
    indices = [Index("reminderId", unique = true), Index("eventId")]
)
data class CalendarTripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val calendarId: Long,
    val eventId: Long,
    val eventStartMs: Long,
    val title: String,
    val location: String,
    // Per-trip origin override; null means "use the home address from Settings".
    val originAddress: String?,
    val travelMode: String,
    val bufferMs: Long,
    // Cached geocoded coordinates so steady-state recomputes need only the
    // routing call, not two extra geocoding calls.
    val originLat: Double?,
    val originLon: Double?,
    val destLat: Double?,
    val destLon: Double?,
    // Last successful traffic-aware estimate; used as a fallback when a later
    // routing call fails so a dead network never suppresses the warning.
    val lastTravelMs: Long?,
    val lastComputedAtMs: Long?,
)
