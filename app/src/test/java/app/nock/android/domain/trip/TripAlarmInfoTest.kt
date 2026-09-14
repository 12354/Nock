package app.nock.android.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class TripAlarmInfoTest {
    private val minute = 60_000L

    @Test fun displayUsesWarningTimeWhileRetainingDepartureAndTravel() {
        val trip = TripAlarmInfo((10 * 60 + 7) * minute, 22 * minute + 30_000L, 30 * minute)
        assertEquals((9 * 60 + 37) * minute, trip.alarmAtMs)
        assertEquals((10 * 60 + 7) * minute, trip.leaveByMs)
        assertEquals(23L, trip.travelMinutes)
    }

    @Test fun changedBufferMovesDisplayTimeButNotDeparture() {
        val original = TripAlarmInfo(10 * 60 * minute, 20 * minute, 30 * minute)
        val edited = original.copy(bufferMs = 45 * minute)
        assertEquals(original.alarmAtMs - 15 * minute, edited.alarmAtMs)
        assertEquals(original.leaveByMs, edited.leaveByMs)
    }

    @Test fun warningCanBelongToPreviousDay() {
        val departure = LocalDateTime.of(2026, 9, 16, 0, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val expected = LocalDateTime.of(2026, 9, 15, 23, 45).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, TripAlarmInfo(departure, 20 * minute, 30 * minute).alarmAtMs)
    }

    @Test fun missingTravelEstimateIsNotDisplayedAsZero() {
        assertNull(TripAlarmInfo(10 * minute, null, minute).travelMinutes)
        assertEquals(0L, TripAlarmInfo(10 * minute, 0L, minute).travelMinutes)
        assertEquals(22L, TripAlarmInfo(10 * minute, 22 * minute, minute).travelMinutes)
    }
}
