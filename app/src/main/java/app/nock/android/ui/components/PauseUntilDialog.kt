@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.nock.android.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nock.android.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

@Composable
fun PauseUntilDialog(
    currentPauseUntilMs: Long?,
    onDismiss: () -> Unit,
    onPick: (untilMs: Long) -> Unit,
    onResume: () -> Unit,
) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val isPaused = currentPauseUntilMs != null && currentPauseUntilMs > now

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_dialog_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                if (isPaused) {
                    Text(
                        stringResource(
                            R.string.pause_dialog_currently_paused,
                            formatPauseUntil(ctx, currentPauseUntilMs!!)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PauseChip(stringResource(R.string.pause_preset_15_min)) {
                        onPick(now + 15L * 60_000L); onDismiss()
                    }
                    PauseChip(stringResource(R.string.pause_preset_1_hour)) {
                        onPick(now + 60L * 60_000L); onDismiss()
                    }
                    PauseChip(stringResource(R.string.pause_preset_4_hours)) {
                        onPick(now + 4L * 60L * 60_000L); onDismiss()
                    }
                    PauseChip(stringResource(R.string.pause_preset_tomorrow_morning)) {
                        onPick(tomorrowMorningMs()); onDismiss()
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        pickCustomUntil(ctx) { untilMs ->
                            onPick(untilMs); onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pause_pick_custom)) }
            }
        },
        confirmButton = {
            if (isPaused) {
                TextButton(onClick = { onResume(); onDismiss() }) {
                    Text(stringResource(R.string.pause_resume_now))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        dismissButton = {
            if (isPaused) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun PauseChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

private fun tomorrowMorningMs(): Long {
    val tomorrowAt7 = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(7, 0))
    return tomorrowAt7.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun pickCustomUntil(ctx: Context, onPicked: (Long) -> Unit) {
    val seed = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    val datePicker = DatePickerDialog(
        ctx,
        { _, year, month, day ->
            val is24h = DateFormat.is24HourFormat(ctx)
            TimePickerDialog(
                ctx,
                { _, hour, minute ->
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(cal.timeInMillis)
                },
                seed.get(Calendar.HOUR_OF_DAY),
                seed.get(Calendar.MINUTE),
                is24h
            ).show()
        },
        seed.get(Calendar.YEAR),
        seed.get(Calendar.MONTH),
        seed.get(Calendar.DAY_OF_MONTH)
    )
    datePicker.datePicker.minDate = System.currentTimeMillis()
    datePicker.show()
}

fun formatPauseUntil(ctx: Context, untilMs: Long): String {
    val zone = ZoneId.systemDefault()
    val until = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(untilMs), zone)
    val today = LocalDate.now(zone)
    val timeFmt = if (DateFormat.is24HourFormat(ctx)) "%02d:%02d".format(until.hour, until.minute)
    else {
        val h12 = ((until.hour + 11) % 12) + 1
        val ampm = if (until.hour < 12) "AM" else "PM"
        "%d:%02d %s".format(h12, until.minute, ampm)
    }
    return when (until.toLocalDate()) {
        today -> timeFmt
        today.plusDays(1) -> ctx.getString(R.string.pause_until_tomorrow_at, timeFmt)
        else -> "%04d-%02d-%02d %s".format(until.year, until.monthValue, until.dayOfMonth, timeFmt)
    }
}
