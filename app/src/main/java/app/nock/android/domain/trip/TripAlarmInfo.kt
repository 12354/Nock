package app.nock.android.domain.trip

/** Display times for a trip. The stored reminder time remains the departure. */
data class TripAlarmInfo(
    val leaveByMs: Long,
    val travelMs: Long?,
    val bufferMs: Long,
) {
    val alarmAtMs: Long
        get() = leaveByMs + TripChain.build(bufferMs, TripDefaults.REPEAT_INTERVAL_MS)
            .stages.last().offsetMs

    /** Round up so the UI never understates the estimated driving duration. */
    val travelMinutes: Long?
        get() = travelMs?.coerceAtLeast(0L)?.let { it / 60_000L + if (it % 60_000L > 0) 1 else 0 }
}
