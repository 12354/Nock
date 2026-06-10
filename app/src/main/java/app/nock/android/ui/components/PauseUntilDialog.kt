@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.nock.android.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nock.android.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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

    // Custom "until" runs date → time through the M3 Compose pickers (the old
    // android.app View dialogs didn't match the app theme).
    var showCustomDate by remember { mutableStateOf(false) }
    var customDate by remember { mutableStateOf<LocalDate?>(null) }
    val seed = remember(now) {
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + 60L * 60_000L), ZoneId.systemDefault())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_dialog_title)) },
        text = {
            Column {
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
                    onClick = { showCustomDate = true },
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

    if (showCustomDate) {
        NockDatePickerDialog(
            initialDate = seed.toLocalDate(),
            onDismiss = { showCustomDate = false },
            onConfirm = { date -> customDate = date }
        )
    }
    customDate?.let { date ->
        NockTimePickerDialog(
            initialMinutesOfDay = seed.hour * 60 + seed.minute,
            onDismiss = { customDate = null },
            onConfirm = { minutes ->
                val until = LocalDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60))
                onPick(until.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                onDismiss()
            }
        )
    }
}

@Composable
private fun PauseChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

private fun tomorrowMorningMs(): Long {
    val tomorrowAt7 = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(7, 0))
    return tomorrowAt7.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
