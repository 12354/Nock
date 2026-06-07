package app.nock.android.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

data class CalendarEvent(
    val calendarId: Long,
    val eventId: Long,
    val beginMs: Long,
    val title: String,
    val location: String,
)

/**
 * Reads events from the on-device calendar provider (CalendarContract). Google
 * Calendar (and any other account) already syncs into this provider, so trip
 * alarms need no OAuth, API key, or network — just READ_CALENDAR.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun listCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val out = mutableListOf<CalendarInfo>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    displayName = c.getString(1) ?: "(unnamed)",
                    accountName = c.getString(2) ?: "",
                )
            }
        }
        return out
    }

    /**
     * Located, timed (non all-day) event instances beginning in [nowMs, untilMs].
     * Recurring events are expanded into individual instances via the Instances
     * table. When [calendarIds] is empty, all visible calendars are included.
     */
    fun upcomingLocatedEvents(
        nowMs: Long,
        untilMs: Long,
        calendarIds: Set<Long>,
    ): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(nowMs.toString())
            .appendPath(untilMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalendarEvent>()
        ctx.contentResolver.query(
            uri, projection,
            // All-day events have no meaningful arrival time to route for.
            "${CalendarContract.Instances.ALL_DAY} = 0",
            null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val location = c.getString(3)?.trim().orEmpty()
                if (location.isEmpty()) continue
                val calId = c.getLong(4)
                if (calendarIds.isNotEmpty() && calId !in calendarIds) continue
                out += CalendarEvent(
                    calendarId = calId,
                    eventId = c.getLong(0),
                    beginMs = c.getLong(1),
                    title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(event)",
                    location = location,
                )
            }
        }
        return out
    }
}
