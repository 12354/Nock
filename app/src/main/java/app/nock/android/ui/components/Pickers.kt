@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nock.android.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Material 3 date picker dialog. Replaces the legacy View-based
 * android.app.DatePickerDialog so date entry matches the app's Compose theme.
 * Confirms with the picked [LocalDate] (interpreted in the user's local zone).
 */
@Composable
fun NockDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    // DatePickerState speaks UTC-midnight millis for the selected *calendar day*.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton
                    onConfirm(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    onDismiss()
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DatePicker(state = state)
    }
}

/**
 * Material 3 time picker dialog. Replaces the legacy View-based
 * android.app.TimePickerDialog; honors the system 12/24-hour preference.
 * Confirms with the picked time as minutes-of-day.
 */
@Composable
fun NockTimePickerDialog(
    initialMinutesOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (minutesOfDay: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = (initialMinutesOfDay / 60).coerceIn(0, 23),
        initialMinute = (initialMinutesOfDay % 60).coerceIn(0, 59),
        is24Hour = DateFormat.is24HourFormat(ctx),
    )
    // material3 has no TimePickerDialog container yet — this is the canonical
    // dialog shell from the M3 samples.
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.pick_time_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        onConfirm(state.hour * 60 + state.minute)
                        onDismiss()
                    }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
