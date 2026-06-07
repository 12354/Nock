package app.nock.android.domain.trip

/**
 * Pure timing math for calendar trip alarms. No Android dependencies so it is
 * fully unit-testable.
 *
 * A trip reminder warns the user in time to travel to a located calendar event:
 *
 *   leaveBy = eventStart − travelTime(traffic)
 *
 * The escalation chain (see [TripChain]) is anchored at `leaveBy`: a silent
 * heads-up fires `buffer` before it and the chain escalates to a loud alarm
 * exactly at `leaveBy` — the moment the user must physically depart.
 */
object TripMath {

    /** The travel-time estimate is refreshed this far before `leaveBy`. */
    val RECOMPUTE_LEADS_MS: List<Long> = listOf(
        3 * 60 * 60_000L, // 3 hours before leave-by
        1 * 60 * 60_000L, // 1 hour before leave-by
        15 * 60_000L,     // 15 minutes before leave-by
    )

    /** The moment the user must leave to arrive on time, given the travel estimate. */
    fun leaveBy(eventStartMs: Long, travelMs: Long): Long = eventStartMs - travelMs

    /**
     * The next future instant at which the traffic-aware travel time should be
     * recomputed, or null when every recompute point is already in the past
     * (we are within the final lead window — the armed escalation owns it now,
     * no further routing call is worthwhile).
     *
     * Recompute points are absolute: `leaveBy − lead` for each configured lead.
     * Picking the earliest still-future one means each fired recompute can chain
     * to the next by calling this again with the freshly computed `leaveBy`.
     */
    fun nextRecomputeAt(leaveByMs: Long, nowMs: Long): Long? =
        RECOMPUTE_LEADS_MS
            .map { leaveByMs - it }
            .filter { it > nowMs }
            .minOrNull()
}
