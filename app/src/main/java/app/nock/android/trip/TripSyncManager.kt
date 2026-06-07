package app.nock.android.trip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.entity.CalendarTripEntity
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.time.TimeSource
import app.nock.android.domain.trip.TripChain
import app.nock.android.domain.trip.TripDefaults
import app.nock.android.domain.trip.TripMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns located calendar events into traffic-aware "leave-by" trip reminders and
 * keeps their departure times fresh as traffic predictions firm up.
 *
 * Flow per event:
 *   travel = TomTom(arriveAt = eventStart, traffic)   // geocode origin+dest first
 *   leaveBy = eventStart − travel
 *   reminder = OneShot(leaveBy) in the Trips group     // engine escalates to a
 *                                                       // loud alarm at leaveBy
 *   schedule a recompute at the next of leaveBy − {3h, 1h, 15m}
 *
 * A failed routing call never drops the warning: it falls back to the last good
 * estimate, then to a fixed default travel time.
 */
@Singleton
class TripSyncManager @Inject constructor(
    private val settings: SettingsRepository,
    private val calendar: CalendarRepository,
    private val tomtom: TomTomClient,
    private val tripDao: CalendarTripDao,
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val scheduler: TripScheduler,
    private val time: TimeSource,
) {
    // Serializes sync passes so an app-open sync and a recompute alarm can't
    // race into duplicate reminders for the same event.
    private val mutex = Mutex()

    private val tripsSeedKey = "trips"
    private val defaultFallbackTravelMs = 20 * 60_000L

    suspend fun enabled(): Boolean =
        settings.get(SettingsRepository.KEY_TRIPS_ENABLED)?.toBooleanStrictOrNull() == true

    private suspend fun bufferMs(): Long =
        (settings.get(SettingsRepository.KEY_TRIP_BUFFER_MIN)?.toLongOrNull()?.times(60_000L))
            ?: TripDefaults.BUFFER_MS

    private suspend fun travelMode(): String =
        settings.get(SettingsRepository.KEY_TRIP_TRAVEL_MODE)?.takeIf { it.isNotBlank() }
            ?: TripDefaults.TRAVEL_MODE

    private suspend fun watchedCalendarIds(): Set<Long> =
        settings.get(SettingsRepository.KEY_TRIP_CALENDAR_IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

    /** Full sync: import upcoming located events, prune stale trips, re-arm the daily sync. */
    suspend fun syncNow() = mutex.withLock {
        val now = time.nowMs()
        if (!enabled() || !calendar.hasPermission()) {
            tearDownAll()
            scheduler.cancelDailySync()
            return
        }
        val buffer = bufferMs()
        val mode = travelMode()
        val tripsGroupId = ensureTripsGroup(buffer)

        val events = calendar.upcomingLocatedEvents(now, now + TripDefaults.LOOKAHEAD_MS, watchedCalendarIds())
        val seen = HashSet<Long>()
        for (e in events) {
            seen += instanceKey(e.eventId, e.beginMs)
            upsertTrip(e, tripsGroupId, buffer, mode, now)
        }
        pruneUnseen(seen)
        scheduler.scheduleDailySync(now + 24 * 60 * 60_000L)
    }

    /** Recompute a single trip's travel time and re-arm it (fired by an alarm). */
    suspend fun recompute(reminderId: Long) = mutex.withLock {
        val now = time.nowMs()
        val trip = tripDao.getByReminderId(reminderId) ?: return
        if (!enabled() || !calendar.hasPermission()) return
        // If the user dismissed the reminder, the row is a tombstone — leave it.
        repo.getReminder(reminderId) ?: return
        val buffer = bufferMs()
        val mode = travelMode()
        val origin = homeCoords(trip)
        val dest = destCoords(trip)
        val travel = resolveTravelMs(origin, dest, trip.eventStartMs, mode, trip.lastTravelMs)
        applyTrip(trip, trip.reminderId, origin, dest, travel, buffer, now)
    }

    // --- internals ---

    private fun instanceKey(eventId: Long, beginMs: Long): Long =
        // Stable per (event, instance) identity for dedup; event ids and begins
        // are both well within the 32-bit space we mix here.
        eventId * 1_000_003L + (beginMs / 60_000L)

    private suspend fun upsertTrip(
        e: CalendarEvent,
        tripsGroupId: Long,
        buffer: Long,
        mode: String,
        now: Long,
    ) {
        val existing = tripDao.getByEvent(e.eventId, e.beginMs)

        // Tombstone: the reminder was dismissed (Done) but the event is still
        // upcoming. Don't resurrect it.
        if (existing != null && repo.getReminder(existing.reminderId) == null) return

        val origin = homeCoords(existing)
        val dest = destCoords(existing, e.location)
        val travel = resolveTravelMs(origin, dest, e.beginMs, mode, existing?.lastTravelMs)
        val leaveBy = TripMath.leaveBy(e.beginMs, travel)

        val name = reminderName(e.title)
        val existingReminder = existing?.let { repo.getReminder(it.reminderId) }
        val reminderId = repo.saveReminder(
            id = existing?.reminderId ?: 0L,
            groupId = tripsGroupId,
            name = name,
            schedule = Schedule.OneShot(leaveBy),
            nextFireAt = leaveBy,
            lastCompletedAt = existingReminder?.lastCompletedAt,
            createdAt = existingReminder?.createdAt ?: now,
        )

        val row = (existing ?: CalendarTripEntity(
            reminderId = reminderId,
            calendarId = e.calendarId,
            eventId = e.eventId,
            eventStartMs = e.beginMs,
            title = e.title,
            location = e.location,
            originAddress = null,
            travelMode = mode,
            bufferMs = buffer,
            originLat = null, originLon = null,
            destLat = null, destLon = null,
            lastTravelMs = null, lastComputedAtMs = null,
        )).copy(
            reminderId = reminderId,
            calendarId = e.calendarId,
            eventId = e.eventId,
            eventStartMs = e.beginMs,
            title = e.title,
            location = e.location,
            travelMode = mode,
            bufferMs = buffer,
            originLat = origin?.lat, originLon = origin?.lon,
            destLat = dest?.lat, destLon = dest?.lon,
            lastTravelMs = travel,
            lastComputedAtMs = now,
        )
        tripDao.upsert(row)

        engine.startEscalationAt(repo.getReminder(reminderId)!!, leaveBy)
        armNextRecompute(reminderId, leaveBy, now)
    }

    /** Re-save reminder + row + escalation for a recomputed travel estimate. */
    private suspend fun applyTrip(
        trip: CalendarTripEntity,
        reminderId: Long,
        origin: LatLng?,
        dest: LatLng?,
        travelMs: Long,
        buffer: Long,
        now: Long,
    ) {
        val leaveBy = TripMath.leaveBy(trip.eventStartMs, travelMs)
        val reminder = repo.getReminder(reminderId) ?: return
        repo.saveReminder(
            id = reminderId,
            groupId = reminder.groupId,
            name = reminder.name,
            schedule = Schedule.OneShot(leaveBy),
            nextFireAt = leaveBy,
            lastCompletedAt = reminder.lastCompletedAt,
            createdAt = reminder.createdAt,
        )
        tripDao.upsert(
            trip.copy(
                bufferMs = buffer,
                originLat = origin?.lat, originLon = origin?.lon,
                destLat = dest?.lat, destLon = dest?.lon,
                lastTravelMs = travelMs,
                lastComputedAtMs = now,
            )
        )
        engine.startEscalationAt(repo.getReminder(reminderId)!!, leaveBy)
        armNextRecompute(reminderId, leaveBy, now)
    }

    private fun armNextRecompute(reminderId: Long, leaveByMs: Long, now: Long) {
        val at = TripMath.nextRecomputeAt(leaveByMs, now)
        if (at != null) scheduler.scheduleRecompute(reminderId, at)
        else scheduler.cancelRecompute(reminderId)
    }

    /** Resolve traffic-aware travel time, degrading gracefully so a warning always fires. */
    private suspend fun resolveTravelMs(
        origin: LatLng?,
        dest: LatLng?,
        arriveByMs: Long,
        mode: String,
        fallbackMs: Long?,
    ): Long {
        if (origin != null && dest != null) {
            when (val r = tomtom.travelTime(origin, dest, arriveByMs, mode)) {
                is RoutingResult.Ok -> if (r.travelMs > 0) return r.travelMs
                is RoutingResult.Error -> Unit // fall through to fallback
            }
        }
        return fallbackMs ?: defaultFallbackTravelMs
    }

    /**
     * Origin coordinates for a trip. A per-trip [CalendarTripEntity.originAddress]
     * override (not surfaced in v1's UI) is geocoded and cached on the row; the
     * default is the Settings home address, whose coordinates are cached in
     * Settings and invalidated when the home address changes — so editing home
     * can't be defeated by a stale per-row cache.
     */
    private suspend fun homeCoords(existing: CalendarTripEntity?): LatLng? {
        val override = existing?.originAddress?.takeIf { it.isNotBlank() }
        if (override != null) {
            if (existing.originLat != null && existing.originLon != null) {
                return LatLng(existing.originLat, existing.originLon)
            }
            return tomtom.geocode(override)
        }
        val lat = settings.get(SettingsRepository.KEY_TRIP_HOME_LAT)?.toDoubleOrNull()
        val lon = settings.get(SettingsRepository.KEY_TRIP_HOME_LON)?.toDoubleOrNull()
        if (lat != null && lon != null) return LatLng(lat, lon)
        val address = settings.get(SettingsRepository.KEY_TRIP_HOME_ADDRESS)?.takeIf { it.isNotBlank() }
            ?: return null
        val geo = tomtom.geocode(address) ?: return null
        settings.set(SettingsRepository.KEY_TRIP_HOME_LAT, geo.lat.toString())
        settings.set(SettingsRepository.KEY_TRIP_HOME_LON, geo.lon.toString())
        return geo
    }

    /** Destination coordinates, reusing the cached geocode when the location is unchanged. */
    private suspend fun destCoords(existing: CalendarTripEntity?, location: String = existing?.location.orEmpty()): LatLng? {
        if (existing != null && existing.location == location &&
            existing.destLat != null && existing.destLon != null
        ) {
            return LatLng(existing.destLat, existing.destLon)
        }
        if (location.isBlank()) return null
        return tomtom.geocode(location)
    }

    /** Remove trips whose event instance is no longer upcoming/located. */
    private suspend fun pruneUnseen(seen: Set<Long>) {
        tripDao.getAll().forEach { trip ->
            if (instanceKey(trip.eventId, trip.eventStartMs) !in seen) {
                tearDown(trip)
            }
        }
    }

    private suspend fun tearDownAll() {
        tripDao.getAll().forEach { tearDown(it) }
    }

    private suspend fun tearDown(trip: CalendarTripEntity) {
        scheduler.cancelRecompute(trip.reminderId)
        engine.cancelActive(trip.reminderId)
        repo.getReminder(trip.reminderId)?.let { repo.deleteReminder(it) }
        tripDao.delete(trip)
    }

    /** Find the Trips group (creating/repairing it), keeping its chain matched to the buffer. */
    private suspend fun ensureTripsGroup(buffer: Long): Long {
        val desiredChain = TripChain.build(buffer, TripDefaults.REPEAT_INTERVAL_MS)
        val existing = repo.getGroups().firstOrNull { it.seedKey == tripsSeedKey }
        if (existing != null) {
            if (existing.overrideChain != desiredChain) {
                repo.upsertGroup(existing.copy(overrideChain = desiredChain))
                engine.rearmGroup(existing.id)
            }
            return existing.id
        }
        return repo.upsertGroup(
            Group(
                id = 0,
                name = "Trips",
                color = Color(0xFF7FB069).toArgb(),
                icon = "DirectionsCar",
                overrideChain = desiredChain,
                pausedUntilMs = null,
                telegramSilentMirror = false,
                seedKey = tripsSeedKey,
            ),
            sortIndex = 100,
        )
    }

    private fun reminderName(title: String): String = "Leave for $title"
}
