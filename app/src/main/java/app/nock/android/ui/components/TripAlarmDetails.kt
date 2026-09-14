package app.nock.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nock.android.R
import app.nock.android.domain.trip.TripAlarmInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared trip context for the ringing alarm, its active card and its editor. */
@Composable
fun TripAlarmDetails(trip: TripAlarmInfo, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.trip_alarm_time, formatTripTime(trip.alarmAtMs)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.trip_driving_duration,
                    trip.travelMinutes?.let { stringResource(R.string.trip_duration_minutes, it) }
                        ?: stringResource(R.string.trip_duration_unknown),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.trip_suggested_departure, formatTripTime(trip.leaveByMs)),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Include the date when the warning and departure straddle midnight. */
fun formatTripTime(ms: Long): String {
    val dateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val pattern = if (dateTime.toLocalDate() == LocalDate.now()) "HH:mm" else "EEE d MMM · HH:mm"
    return dateTime.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
