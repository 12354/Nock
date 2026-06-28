package app.nock.android.trip

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.entity.CalendarTripEntity
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageType
import app.nock.android.domain.time.TimeSource
import app.nock.android.domain.trip.TripChain
import app.nock.android.domain.trip.TripDefaults
import app.nock.android.domain.trip.TripMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One escalation step in a manual-import preview: a stage type and its absolute fire time. */
data class TripPreviewStep(
    val type: StageType,
    val atMs: Long,
)

/**
 * A dry-run preview of what importing a single calendar event would create —
 * surfaced by the manual single-appointment importer before the user commits.
 */
data class TripPreview(
    val event: CalendarEvent,
    val hasLocation: Boolean,
    val homeAddress: String?,
    val originResolved: Boolean,
    val destResolved: Boolean,
    val travelMs: Long,
    /** False when TomTom couldn't be reached and the time is a fallback estimate. */
    val travelLive: Boolean,
    val leaveByMs: Long,
    val reminderName: String,
    val showLeaveFor: Boolean,
    val bufferMs: Long,
    val steps: List<TripPreviewStep>,
    /** A live reminder for this exact event instance already exists. */
    val alreadyImported: Boolean,
    /** This event was imported before and the user dismissed it (a tombstone exists). */
    val previouslyDismissed: Boolean,
)

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
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val calendar: CalendarRepository,
    private val tomtom: TomTomClient,
    private val tripDao: CalendarTripDao,
    private val activeDao: ActiveEscalationDao,
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val scheduler: TripScheduler,
    private val time: TimeSource,
    private val syncLog: TripSyncLog,
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

    /** The configured trip buffer in whole minutes — seeds the manual-import slider. */
    suspend fun configuredBufferMin(): Int = (bufferMs() / 60_000L).toInt()

    private suspend fun travelMode(): String =
        settings.get(SettingsRepository.KEY_TRIP_TRAVEL_MODE)?.takeIf { it.isNotBlank() }
            ?: TripDefaults.TRAVEL_MODE

    private suspend fun watchedCalendarIds(): Set<Long> =
        settings.get(SettingsRepository.KEY_TRIP_CALENDAR_IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

    /** Full sync: import upcoming located events, prune stale trips, re-arm the daily sync. */
    suspend fun syncNow() = mutex.withLock {
        val now = time.nowMs()
        syncLog.start(now)
        val enabled = enabled()
        val hasPermission = calendar.hasPermission()
        if (!enabled || !hasPermission) {
            if (!enabled) syncLog.line("Calendar alarms are turned off — nothing synced.")
            if (!hasPermission) syncLog.line("No calendar permission granted — nothing synced.")
            syncLog.publish()
            tearDownAll()
            scheduler.cancelDailySync()
            return
        }
        val buffer = bufferMs()
        val mode = travelMode()
        val watched = watchedCalendarIds()
        val tripsGroupId = ensureTripsGroup(buffer)
        val calNames = calendar.listCalendars().associate { it.id to it.displayName }

        val scan = calendar.scanWindow(now, now + TripDefaults.LOOKAHEAD_MS)
        syncLog.line(
            "window: ${syncLog.clock(now)} → ${syncLog.clock(now + TripDefaults.LOOKAHEAD_MS)}" +
                " (next ${TripDefaults.LOOKAHEAD_MS / (24 * 60 * 60_000L)} days)"
        )
        syncLog.line("home: ${homeDescription()}")
        syncLog.line("buffer: ${syncLog.dur(buffer)} · mode: $mode")
        syncLog.line(
            if (watched.isEmpty()) "watched calendars: NONE SELECTED — no event can sync until you check a calendar below"
            else "watched calendars: " + watched.joinToString(", ") { "${calNames[it] ?: "?"} (#$it)" }
        )
        syncLog.line("events found in window: ${scan.size}")
        syncLog.line("")

        val seen = HashSet<Long>()
        for (s in scan) {
            val calName = calNames[s.calendarId] ?: "calendar #${s.calendarId}"
            val head = "\"${s.title}\" [$calName] ${syncLog.clock(s.beginMs)}"
            when {
                s.allDay -> {
                    syncLog.line(head)
                    syncLog.line("    skipped — all-day event (no arrival time to plan a trip for)")
                }
                s.calendarId !in watched -> {
                    syncLog.line(head)
                    syncLog.line("    skipped — calendar not in your watched selection")
                }
                else -> {
                    val e = CalendarEvent(s.calendarId, s.eventId, s.beginMs, s.title, s.location)
                    seen += instanceKey(e.eventId, e.beginMs)
                    syncLog.line(head)
                    upsertTrip(e, tripsGroupId, buffer, mode, now)
                }
            }
        }
        val pruned = pruneUnseen(seen, watched, now)
        syncLog.line("")
        syncLog.line("pruned $pruned trip(s) no longer in the window.")
        syncLog.publish()
        scheduler.scheduleDailySync(now + 24 * 60 * 60_000L)
    }

    /** One-line description of where trips start from, for the sync log. */
    private suspend fun homeDescription(): String {
        val override = settings.get(SettingsRepository.KEY_TRIP_HOME_ADDRESS)?.takeIf { it.isNotBlank() }
            ?: return "not set (located events fall back to a fixed travel time)"
        val lat = settings.get(SettingsRepository.KEY_TRIP_HOME_LAT)?.toDoubleOrNull()
        val lon = settings.get(SettingsRepository.KEY_TRIP_HOME_LON)?.toDoubleOrNull()
        return if (lat != null && lon != null) "\"$override\" (geocoded)" else "\"$override\" (not yet geocoded)"
    }

    /** Recompute a single trip's travel time and re-arm it (fired by an alarm). */
    suspend fun recompute(reminderId: Long) = mutex.withLock {
        val now = time.nowMs()
        val trip = tripDao.getByReminderId(reminderId) ?: return
        if (!enabled() || !calendar.hasPermission()) return
        // If the user dismissed the reminder, the row is a tombstone — leave it.
        repo.getReminder(reminderId) ?: return
        // Preserve this trip's own (per-reminder) buffer — a recompute only refreshes
        // the travel estimate; it must not reset a buffer the user edited back to the
        // global default.
        val buffer = trip.bufferMs
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
        // upcoming. Don't resurrect it automatically — the manual single-appointment
        // importer ([importEvent]) is the deliberate way to bring it back.
        if (existing != null && repo.getReminder(existing.reminderId) == null) {
            syncLog.line("    skipped — you dismissed this reminder; it won't be recreated")
            return
        }

        val comp = computeTrip(e, existing, mode)
        logSyncedEvent(e, comp.hasLocation, comp.origin, comp.dest, comp.travelMs, comp.leaveByMs)
        // Keep an existing trip's per-reminder buffer across re-syncs; only a brand-new
        // trip takes the global default.
        writeTrip(e, existing, comp, tripsGroupId, existing?.bufferMs ?: buffer, mode, now)
    }

    /**
     * Resolve travel time, leave-by and trip framing for [e] without persisting
     * anything. The network work (geocode + routing) happens here, off the engine
     * lock, shared by the bulk sync and the manual-import preview.
     */
    private suspend fun computeTrip(
        e: CalendarEvent,
        existing: CalendarTripEntity?,
        mode: String,
    ): TripComputation {
        // Located events get a traffic-aware leave-by; location-less events simply
        // fire at their start time (travel = 0, no routing or recompute).
        val hasLocation = e.location.isNotBlank()
        val origin = if (hasLocation) homeCoords(existing) else null
        val dest = if (hasLocation) destCoords(existing, e.location) else null
        val travel = if (hasLocation) resolveTravel(origin, dest, e.beginMs, mode, existing?.lastTravelMs)
            else TravelEstimate(0L, live = false)
        val leaveBy = TripMath.leaveBy(e.beginMs, travel.travelMs)
        val show = showLeaveFor(hasLocation, travel.travelMs)
        return TripComputation(
            hasLocation = hasLocation,
            origin = origin,
            dest = dest,
            travelMs = travel.travelMs,
            travelLive = travel.live,
            leaveByMs = leaveBy,
            showLeaveFor = show,
            reminderName = reminderName(e.title, show),
        )
    }

    /**
     * Persist (or refresh) the reminder + trip row + escalation for [e] from an
     * already-resolved [comp], as ONE engine-locked step. Doing these as bare
     * repo/tripDao calls let a Drive snapshot import (which wipes reminders +
     * calendar_trips) interleave and either NPE or strand a half-written trip;
     * runExclusive serializes it against the engine and import. Returns the id.
     */
    private suspend fun writeTrip(
        e: CalendarEvent,
        existing: CalendarTripEntity?,
        comp: TripComputation,
        tripsGroupId: Long,
        buffer: Long,
        mode: String,
        now: Long,
    ): Long {
        val reminderId = engine.runExclusive {
            val existingReminder = existing?.let { repo.getReminder(it.reminderId) }
            // Whether departure actually moved. A plain travel-estimate refresh
            // that lands on the same leave-by must NOT tear down a live escalation
            // (which would wipe a snooze the user set on the "leave now" alarm and
            // re-ring it) — and a full sync runs on every app open / boot / daily.
            val leaveByUnchanged = existingReminder?.nextFireAt == comp.leaveByMs
            val rid = repo.saveReminder(
                id = existing?.reminderId ?: 0L,
                groupId = tripsGroupId,
                name = comp.reminderName,
                schedule = Schedule.OneShot(comp.leaveByMs),
                nextFireAt = comp.leaveByMs,
                lastCompletedAt = existingReminder?.lastCompletedAt,
                createdAt = existingReminder?.createdAt ?: now,
            )
            val row = (existing ?: CalendarTripEntity(
                reminderId = rid,
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
                reminderId = rid,
                calendarId = e.calendarId,
                eventId = e.eventId,
                eventStartMs = e.beginMs,
                title = e.title,
                location = e.location,
                travelMode = mode,
                bufferMs = buffer,
                originLat = comp.origin?.lat, originLon = comp.origin?.lon,
                destLat = comp.dest?.lat, destLon = comp.dest?.lon,
                lastTravelMs = comp.travelMs,
                lastComputedAtMs = now,
            )
            tripDao.upsert(row)
            // If the row was wiped out from under us mid-step, skip arming rather
            // than crash; the next sync pass rebuilds it. Re-arm only when the
            // departure time moved or no escalation is currently armed (self-heal);
            // an unchanged leave-by leaves a live/snoozed escalation untouched.
            repo.getReminder(rid)?.let { r ->
                if (!leaveByUnchanged || activeDao.getByReminderId(rid) == null) {
                    arm(r, comp.leaveByMs)
                }
            }
            rid
        }
        // Only located events have a travel time worth refreshing as traffic firms up.
        if (comp.hasLocation) armNextRecompute(reminderId, comp.leaveByMs, now)
        else scheduler.cancelRecompute(reminderId)
        return reminderId
    }

    // --- Editing an imported trip's location ---

    /**
     * The location stored for the trip behind [reminderId], or null when the
     * reminder isn't a calendar-imported trip. A blank string is a real value
     * (an imported event with no location), distinct from null (not a trip), so
     * the edit screen can decide whether to surface the location field at all.
     */
    suspend fun tripLocation(reminderId: Long): String? =
        tripDao.getByReminderId(reminderId)?.location

    /**
     * Persist a user-edited [location] for the trip behind [reminderId],
     * invalidating the cached destination geocode so the next [recompute]
     * re-routes from the new address. Returns true when the value actually
     * changed (so the caller can skip a needless recompute). No-op for a
     * reminder that has no trip row.
     */
    suspend fun setTripLocation(reminderId: Long, location: String): Boolean = mutex.withLock {
        val trip = tripDao.getByReminderId(reminderId) ?: return@withLock false
        if (trip.location == location) return@withLock false
        tripDao.upsert(trip.copy(location = location, destLat = null, destLon = null))
        true
    }

    // --- Manual single-appointment import ---

    /**
     * Upcoming, non-all-day events from a single calendar for the manual importer,
     * over a wider window than the bulk sync so the user can scroll/search further
     * out. Ignores the watched-calendar filter on purpose: the user is explicitly
     * choosing this calendar.
     */
    suspend fun eventsForCalendar(calendarId: Long): List<CalendarEvent> {
        val now = time.nowMs()
        return calendar.scanWindow(now, now + TripDefaults.MANUAL_LOOKAHEAD_MS)
            .filter { !it.allDay && it.calendarId == calendarId }
            .map { CalendarEvent(it.calendarId, it.eventId, it.beginMs, it.title, it.location) }
    }

    /**
     * Dry-run the trip computation for a chosen event: travel time, leave-by, the
     * escalation steps and a reminder-name preview, plus whether it's already
     * imported or was previously dismissed. Writes nothing.
     */
    suspend fun previewEvent(event: CalendarEvent, bufferMsOverride: Long? = null): TripPreview {
        val buffer = bufferMsOverride ?: bufferMs()
        val mode = travelMode()
        val existing = tripDao.getByEvent(event.eventId, event.beginMs)
        val comp = computeTrip(event, existing, mode)
        val chain = TripChain.build(buffer, TripDefaults.REPEAT_INTERVAL_MS)
        val steps = chain.stages.map { TripPreviewStep(it.type, comp.leaveByMs + it.offsetMs) }
        val liveReminder = existing?.let { repo.getReminder(it.reminderId) }
        return TripPreview(
            event = event,
            hasLocation = comp.hasLocation,
            homeAddress = settings.get(SettingsRepository.KEY_TRIP_HOME_ADDRESS)?.takeIf { it.isNotBlank() },
            originResolved = comp.origin != null,
            destResolved = comp.dest != null,
            travelMs = comp.travelMs,
            travelLive = comp.travelLive,
            leaveByMs = comp.leaveByMs,
            reminderName = comp.reminderName,
            showLeaveFor = comp.showLeaveFor,
            bufferMs = buffer,
            steps = steps,
            alreadyImported = liveReminder != null,
            previouslyDismissed = existing != null && liveReminder == null,
        )
    }

    /**
     * Re-frame a preview for a user-chosen buffer without re-routing. Only the
     * escalation step offsets (and the stored buffer) depend on it — the
     * traffic-aware travel time and leave-by are independent — so the slider can
     * update the preview live without another geocode/routing round-trip.
     */
    fun reframeWithBuffer(preview: TripPreview, bufferMs: Long): TripPreview {
        val chain = TripChain.build(bufferMs, TripDefaults.REPEAT_INTERVAL_MS)
        val steps = chain.stages.map { TripPreviewStep(it.type, preview.leaveByMs + it.offsetMs) }
        return preview.copy(bufferMs = bufferMs, steps = steps)
    }

    /**
     * Import a single chosen event as a trip reminder, on demand. Unlike [syncNow]
     * this bypasses the dismissed-tombstone guard — re-importing is exactly how the
     * user deliberately brings back a reminder they dismissed — and it prunes
     * nothing. Recomputes the estimate fresh so the import reflects current traffic.
     *
     * A [bufferMsOverride] (from the importer's buffer slider) is stored as this
     * reminder's own per-reminder buffer; the engine builds its escalation chain
     * from it, so it never affects other trips. Absent an override, an existing
     * trip keeps its buffer and a fresh one takes the global default.
     * Returns the new/updated reminder id.
     */
    suspend fun importEvent(event: CalendarEvent, bufferMsOverride: Long? = null): Long = mutex.withLock {
        val now = time.nowMs()
        val mode = travelMode()
        // The group chain is only the default/fallback now that each trip carries its
        // own buffer, so seed the group from the global default, not this import.
        val tripsGroupId = ensureTripsGroup(bufferMs())
        val existing = tripDao.getByEvent(event.eventId, event.beginMs)
        val buffer = bufferMsOverride ?: existing?.bufferMs ?: bufferMs()
        val comp = computeTrip(event, existing, mode)
        writeTrip(event, existing, comp, tripsGroupId, buffer, mode, now)
    }

    // --- Editing an imported trip's buffer ---

    /**
     * This trip's heads-up buffer in whole minutes, or null when [reminderId] isn't
     * a calendar-imported trip — lets the editor decide whether to show the field.
     */
    suspend fun tripBufferMin(reminderId: Long): Int? =
        tripDao.getByReminderId(reminderId)?.let { (it.bufferMs / 60_000L).toInt() }

    /**
     * Persist a user-edited per-reminder [bufferMin] for the trip behind
     * [reminderId] and re-arm it so the new heads-up lead takes effect immediately.
     * No re-routing: only the escalation offsets depend on the buffer, so the cached
     * travel estimate and leave-by are reused. Returns true when the value changed
     * (so the caller can skip redundant work); no-op for a non-trip reminder.
     */
    suspend fun setTripBufferMin(reminderId: Long, bufferMin: Int): Boolean = mutex.withLock {
        val newBufferMs = bufferMin.toLong() * 60_000L
        engine.runExclusive {
            val trip = tripDao.getByReminderId(reminderId) ?: return@runExclusive false
            if (trip.bufferMs == newBufferMs) return@runExclusive false
            tripDao.upsert(trip.copy(bufferMs = newBufferMs))
            // Re-arm from the reminder's current trigger; the engine reads the row's
            // (now updated) buffer when rebuilding the trip's chain.
            repo.getReminder(reminderId)?.let { r -> r.nextFireAt?.let { arm(r, it) } }
            true
        }
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
        // A recompute can push travel above/below the trip threshold, so refresh
        // the name to keep the "Leave for …" framing in sync with the estimate.
        val name = reminderName(trip.title, showLeaveFor(trip.location.isNotBlank(), travelMs))
        // Whether departure actually moved this recompute. When traffic is steady
        // leave-by is unchanged, and re-arming would needlessly wipe a snooze the
        // user set on the live "leave now" alarm and re-ring it.
        val leaveByUnchanged = reminder.nextFireAt == leaveBy
        // Re-save + re-arm atomically (see upsertTrip) so a concurrent import can't
        // wipe the reminder between the save and the re-arm.
        engine.runExclusive {
            repo.saveReminder(
                id = reminderId,
                groupId = reminder.groupId,
                name = name,
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
            // Re-arm only when departure moved or no escalation is currently armed
            // (self-heal); an unchanged leave-by preserves a live/snoozed chain.
            repo.getReminder(reminderId)?.let { r ->
                if (!leaveByUnchanged || activeDao.getByReminderId(reminderId) == null) {
                    arm(r, leaveBy)
                }
            }
        }
        armNextRecompute(reminderId, leaveBy, now)
    }

    /** Record why/how an event became (or fell short of) a trip reminder in the sync log. */
    private fun logSyncedEvent(
        e: CalendarEvent,
        hasLocation: Boolean,
        origin: LatLng?,
        dest: LatLng?,
        travelMs: Long,
        leaveByMs: Long,
    ) {
        if (!hasLocation) {
            syncLog.line("    synced — no location → alerts at start ${syncLog.clock(leaveByMs)}")
            return
        }
        val notes = buildList {
            if (origin == null) add("home address not set/geocodable")
            if (dest == null) add("location \"${e.location}\" couldn't be geocoded")
            if (origin == null || dest == null) add("using fallback travel time")
        }
        val frame = if (showLeaveFor(true, travelMs)) "leave-for alarm" else "at-home (0 travel) → plain alert"
        syncLog.line(
            "    synced — \"${e.location}\" → leave ${syncLog.clock(leaveByMs)}" +
                " (travel ${syncLog.dur(travelMs)}, $frame)"
        )
        if (notes.isNotEmpty()) syncLog.line("      note: " + notes.joinToString("; "))
    }

    private fun armNextRecompute(reminderId: Long, leaveByMs: Long, now: Long) {
        val at = TripMath.nextRecomputeAt(leaveByMs, now)
        if (at != null) scheduler.scheduleRecompute(reminderId, at)
        else scheduler.cancelRecompute(reminderId)
    }

    /** A resolved travel time plus whether it came from a live TomTom call (vs. a fallback). */
    private data class TravelEstimate(val travelMs: Long, val live: Boolean)

    /**
     * Resolve traffic-aware travel time, degrading gracefully so a warning always
     * fires. [TravelEstimate.live] is false when TomTom couldn't be reached and the
     * value fell back to the last good estimate or the fixed default.
     */
    private suspend fun resolveTravel(
        origin: LatLng?,
        dest: LatLng?,
        arriveByMs: Long,
        mode: String,
        fallbackMs: Long?,
    ): TravelEstimate {
        if (origin != null && dest != null) {
            when (val r = tomtom.travelTime(origin, dest, arriveByMs, mode)) {
                // A travel time of exactly 0 is a real answer, not a failure: when an
                // event's location is the user's own home (e.g. taking out the trash),
                // origin == destination and TomTom returns a 200 route with
                // travelTimeInSeconds = 0. parseTravelMs returns null (not 0) when there
                // is genuinely no route, so any non-negative value here is live — accept
                // it instead of falling through to the fixed default travel time.
                is RoutingResult.Ok -> if (r.travelMs >= 0) return TravelEstimate(r.travelMs, live = true)
                is RoutingResult.Error -> Unit // fall through to fallback
            }
        }
        return TravelEstimate(fallbackMs ?: defaultFallbackTravelMs, live = false)
    }

    private suspend fun resolveTravelMs(
        origin: LatLng?,
        dest: LatLng?,
        arriveByMs: Long,
        mode: String,
        fallbackMs: Long?,
    ): Long = resolveTravel(origin, dest, arriveByMs, mode, fallbackMs).travelMs

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

    /**
     * Remove trips this scan can prove are gone: any whose event already started,
     * and any in a *watched* calendar within the lookahead window that the scan
     * didn't see (deleted or moved). Trips outside this scan's authority — a
     * manually imported appointment from an unwatched calendar, or one further out
     * than the lookahead — are left alone until their start passes, so the manual
     * single-appointment importer isn't undone by the next daily sync. Returns how
     * many were removed.
     */
    private suspend fun pruneUnseen(seen: Set<Long>, watched: Set<Long>, now: Long): Int {
        var pruned = 0
        val windowEnd = now + TripDefaults.LOOKAHEAD_MS
        tripDao.getAll().forEach { trip ->
            val inScanScope = trip.calendarId in watched && trip.eventStartMs <= windowEnd
            // A trip's loud "leave now" alarm fires at leaveBy (= eventStart −
            // travel), BEFORE the event starts, and keeps re-ringing/snoozing until
            // the user presses Done — so an active escalation can legitimately still
            // be live right at/after eventStart. Don't reclaim a started-event trip
            // while its escalation is in flight, or a sync landing in that window
            // silently kills the most important alarm. Once Done'd the row is gone
            // and the next sync prunes the now-orphan trip normally.
            val hasLiveEscalation = activeDao.getByReminderId(trip.reminderId) != null
            val gone = (trip.eventStartMs < now && !hasLiveEscalation) ||
                (inScanScope && instanceKey(trip.eventId, trip.eventStartMs) !in seen)
            if (gone) {
                tearDown(trip)
                pruned++
            }
        }
        return pruned
    }

    private suspend fun tearDownAll() {
        tripDao.getAll().forEach { tearDown(it) }
    }

    private suspend fun tearDown(trip: CalendarTripEntity) {
        scheduler.cancelRecompute(trip.reminderId)
        // Cancel the escalation, delete the reminder, and drop the trip row as one
        // engine-locked step so a concurrent fire/import can't see a torn state.
        engine.runExclusive {
            cancel(trip.reminderId)
            repo.getReminder(trip.reminderId)?.let { repo.deleteReminder(it) }
            tripDao.delete(trip)
        }
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
                name = ctx.getString(R.string.trips_group_name),
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

    /**
     * Whether to frame a reminder as a trip ("Leave for …") and give it the
     * pre-departure buffer. Only a travel time of exactly 0 — TomTom's real
     * answer when the event location is the user's own home (origin == dest,
     * e.g. taking out the trash) — is treated as no trip: there is nowhere to
     * travel, so a leave-by departure prompt and its buffer are just noise and
     * it shows the plain event title like a location-less event. Any positive
     * travel time, however small, still gets the full leave-for treatment.
     */
    private fun showLeaveFor(hasLocation: Boolean, travelMs: Long): Boolean =
        hasLocation && travelMs > 0

    private fun reminderName(title: String, showLeaveFor: Boolean): String =
        if (showLeaveFor) ctx.getString(R.string.trips_reminder_name, title) else title

    /** Resolved (but not yet persisted) trip details, shared by sync, preview and import. */
    private data class TripComputation(
        val hasLocation: Boolean,
        val origin: LatLng?,
        val dest: LatLng?,
        val travelMs: Long,
        val travelLive: Boolean,
        val leaveByMs: Long,
        val showLeaveFor: Boolean,
        val reminderName: String,
    )
}
