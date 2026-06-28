package app.nock.android.trip

import app.nock.android.data.SettingsRepository
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ---- Response shapes (only the fields we read) ----

internal data class TomTomGeocodeResponse(val results: List<TomTomGeocodeResult>?)
internal data class TomTomGeocodeResult(val position: TomTomPosition?)
internal data class TomTomPosition(val lat: Double?, val lon: Double?)

internal data class TomTomRouteResponse(val routes: List<TomTomRoute>?)
internal data class TomTomRoute(val summary: TomTomRouteSummary?)
internal data class TomTomRouteSummary(
    val travelTimeInSeconds: Long?,
    val trafficDelayInSeconds: Long?,
)

/**
 * Pure JSON parsing for TomTom responses, split out so it can be unit-tested
 * without any network.
 */
object TomTomParsing {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val geocodeAdapter: JsonAdapter<TomTomGeocodeResponse> =
        moshi.adapter(TomTomGeocodeResponse::class.java)
    private val routeAdapter: JsonAdapter<TomTomRouteResponse> =
        moshi.adapter(TomTomRouteResponse::class.java)

    fun parseGeocode(json: String): LatLng? {
        val pos = runCatching { geocodeAdapter.fromJson(json) }.getOrNull()
            ?.results?.firstOrNull()?.position ?: return null
        val lat = pos.lat ?: return null
        val lon = pos.lon ?: return null
        return LatLng(lat, lon)
    }

    /** Traffic-aware travel time in milliseconds, or null when absent. */
    fun parseTravelMs(json: String): Long? {
        val seconds = runCatching { routeAdapter.fromJson(json) }.getOrNull()
            ?.routes?.firstOrNull()?.summary?.travelTimeInSeconds ?: return null
        return seconds * 1000L
    }
}

/**
 * TomTom-backed geocoding and traffic-aware routing. The user supplies their own
 * free API key (Settings), mirroring the Telegram bot-token / DeepSeek-key
 * pattern — no shared backend, and TomTom's free tier has no overage billing.
 */
@Singleton
class TomTomClient @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) : Geocoder, RoutingClient {

    private suspend fun key(): String? =
        settings.get(SettingsRepository.KEY_TOMTOM_KEY)?.takeIf { it.isNotBlank() }

    override suspend fun geocode(query: String): LatLng? = withContext(Dispatchers.IO) {
        val apiKey = key() ?: return@withContext null
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext null
        val url = "https://api.tomtom.com/search/2/geocode/".toHttpUrl().newBuilder()
            .addPathSegment("$trimmed.json")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("limit", "1")
            .build()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val raw = resp.body?.string()
                if (resp.isSuccessful && raw != null) TomTomParsing.parseGeocode(raw) else null
            }
        }.getOrNull()
    }

    override suspend fun travelTime(
        origin: LatLng,
        destination: LatLng,
        arriveByMs: Long,
        travelMode: String,
    ): RoutingResult = withContext(Dispatchers.IO) {
        val apiKey = key() ?: return@withContext RoutingResult.Error("no TomTom key")
        val locations = "${origin.lat},${origin.lon}:${destination.lat},${destination.lon}"
        // TomTom rejects an arriveAt in the past with a 4xx. A recompute can be
        // delivered late (Doze) after the event has already started, so floor the
        // target a minute into the future — a still-useful depart-soon estimate —
        // rather than issuing a doomed request that silently falls back to a stale time.
        val safeArriveBy = maxOf(arriveByMs, System.currentTimeMillis() + 60_000L)
        val url = ("https://api.tomtom.com/routing/1/calculateRoute/" + locations + "/json")
            .toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .addQueryParameter("travelMode", travelMode.ifBlank { "car" })
            .addQueryParameter("traffic", "true")
            .addQueryParameter("computeTravelTimeFor", "all")
            .addQueryParameter("arriveAt", isoOffset(safeArriveBy))
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    RoutingResult.Error("${resp.code}: ${raw?.take(200)}")
                } else {
                    val travelMs = raw?.let { TomTomParsing.parseTravelMs(it) }
                    if (travelMs != null) RoutingResult.Ok(travelMs)
                    else RoutingResult.Error("no route in response")
                }
            }
        } catch (t: Throwable) {
            RoutingResult.Error(t.message ?: t::class.simpleName ?: "error")
        }
    }

    companion object {
        // TomTom accepts ISO-8601 with a zone offset for arriveAt; emit the
        // device-zone offset form (e.g. 2026-06-07T18:00:00+02:00).
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun isoOffset(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(epochMs).atZone(zone).format(ISO)
    }
}
