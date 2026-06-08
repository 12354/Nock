package app.nock.android.trip

/** A geographic point. */
data class LatLng(val lat: Double, val lon: Double)

/** Outcome of a traffic-aware travel-time lookup. */
sealed interface RoutingResult {
    /** Estimated traffic-aware travel duration, in milliseconds. */
    data class Ok(val travelMs: Long) : RoutingResult
    data class Error(val message: String) : RoutingResult
}

/** Resolves a free-text address / place name to coordinates. */
interface Geocoder {
    suspend fun geocode(query: String): LatLng?
}

/** Computes a traffic-aware travel time between two points for a target arrival. */
interface RoutingClient {
    /**
     * Traffic-aware driving time from [origin] to [destination] for an arrival
     * at [arriveByMs] (epoch millis). Implementations should use predictive
     * traffic for the future arrival time so the estimate reflects expected
     * conditions when the user actually travels.
     */
    suspend fun travelTime(
        origin: LatLng,
        destination: LatLng,
        arriveByMs: Long,
        travelMode: String,
    ): RoutingResult
}
