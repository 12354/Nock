package app.nock.android.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TomTomParsingTest {

    @Test fun parseGeocode_readsFirstPosition() {
        val json = """
            {"results":[
              {"position":{"lat":52.3702,"lon":4.8952}},
              {"position":{"lat":1.0,"lon":2.0}}
            ]}
        """.trimIndent()
        assertEquals(LatLng(52.3702, 4.8952), TomTomParsing.parseGeocode(json))
    }

    @Test fun parseGeocode_emptyResults_null() {
        assertNull(TomTomParsing.parseGeocode("""{"results":[]}"""))
        assertNull(TomTomParsing.parseGeocode("""{}"""))
        assertNull(TomTomParsing.parseGeocode("""not json"""))
    }

    @Test fun parseTravel_readsTravelSeconds() {
        val json = """
            {"routes":[
              {"summary":{"lengthInMeters":12000,"travelTimeInSeconds":1800,"trafficDelayInSeconds":300}}
            ]}
        """.trimIndent()
        assertEquals(1_800_000L, TomTomParsing.parseTravelMs(json)) // 30 min in ms
    }

    @Test fun parseTravel_zeroSeconds_isZeroNotNull() {
        // Same-location trips (event address == home, e.g. taking out the trash)
        // come back as a real 200 route with travelTimeInSeconds = 0. That must
        // parse to 0L — distinct from the null "no route" case — so the caller
        // treats it as a live 0-minute trip instead of a fallback estimate.
        val json = """
            {"routes":[
              {"summary":{"lengthInMeters":0,"travelTimeInSeconds":0,"trafficDelayInSeconds":0}}
            ]}
        """.trimIndent()
        assertEquals(0L, TomTomParsing.parseTravelMs(json))
    }

    @Test fun parseTravel_noRoutes_null() {
        assertNull(TomTomParsing.parseTravelMs("""{"routes":[]}"""))
        assertNull(TomTomParsing.parseTravelMs("""{"formatVersion":"0.0.12"}"""))
        assertNull(TomTomParsing.parseTravelMs("""garbage"""))
    }

    @Test fun isoOffset_roundTripsToSameInstant() {
        // The formatted arriveAt must parse back to the exact instant we passed,
        // so TomTom receives the arrival time we intend.
        val epoch = 1_717_776_000_000L
        val s = TomTomClient.isoOffset(epoch, java.time.ZoneOffset.UTC)
        val parsed = java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        assertEquals(epoch, parsed)
    }
}
