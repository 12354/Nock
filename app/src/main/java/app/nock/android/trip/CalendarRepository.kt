package app.nock.android.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.nock.android.R
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
 * A raw event instance from the diagnostic [CalendarRepository.scanWindow] scan:
 * every event in the look-ahead window, including the all-day and unwatched-calendar
 * ones that the sync skips. [allDay] and [calendarId] are what the sync log uses to
 * explain *why* an event wasn't turned into an alarm.
 */
data class ScannedEvent(
    val calendarId: Long,
    val eventId: Long,
    val beginMs: Long,
    val title: String,
    val location: String,
    val allDay: Boolean,
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
                    displayName = c.getString(1) ?: ctx.getString(R.string.trips_calendar_unnamed),
                    accountName = c.getString(2) ?: "",
                )
            }
        }
        return out
    }

    /**
     * Timed (non all-day) event instances beginning in [nowMs, untilMs], restricted
     * to the calendars the user chose. Both located and location-less events are
     * returned: located ones get a traffic-aware leave-by time, the rest alert at
     * their start. Only events from [calendarIds] are returned; an empty set yields
     * nothing (the user hasn't opted any in).
     */
    fun upcomingEvents(
        nowMs: Long,
        untilMs: Long,
        calendarIds: Set<Long>,
    ): List<CalendarEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        return scanWindow(nowMs, untilMs)
            .filter { !it.allDay && it.calendarId in calendarIds }
            .map { CalendarEvent(it.calendarId, it.eventId, it.beginMs, it.title, it.location) }
    }

    /**
     * Every event instance beginning in [nowMs, untilMs] across *all* calendars,
     * all-day included — the unfiltered view the sync log needs to explain why a
     * given event was skipped (all-day, or in a calendar the user didn't check).
     * The sync itself consumes the [upcomingEvents] subset of this. Recurring
     * events are expanded into individual instances via the Instances table.
     */
    fun scanWindow(nowMs: Long, untilMs: Long): List<ScannedEvent> {
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
        val out = mutableListOf<ScannedEvent>()
        ctx.contentResolver.query(
            uri, projection, null, null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += ScannedEvent(
                    calendarId = c.getLong(4),
                    eventId = c.getLong(0),
                    beginMs = c.getLong(1),
                    title = c.getString(2)?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.trips_event_untitled),
                    location = c.getString(3)?.trim().orEmpty(),
                    allDay = c.getInt(5) != 0,
                )
            }
        }
        return out
    }
}
