package app.nock.android.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.ui.voice.VoiceAlarmFab
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    vm: RemindersViewModel = hiltViewModel()
) {
    val sections by vm.sections.collectAsState()
    val pending by vm.pending.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reminders)) }) },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VoiceAlarmFab()
                ExtendedFloatingActionButton(
                    onClick = onAddReminder,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 144.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pending, key = { "pending-${it.id}" }) { entry ->
                PendingVoiceCard(
                    entry = entry,
                    onRetry = { vm.retryPending(entry) },
                    onDelete = { vm.deletePending(entry) }
                )
            }
            items(sections, key = { it.group.id }) { section ->
                GroupCard(
                    section = section,
                    onPauseToggle = {
                        if (section.group.isPaused(System.currentTimeMillis())) vm.unpauseGroup(section.group)
                        else vm.pauseGroup(section.group, null)
                    },
                    onClickReminder = onEditReminder,
                    onDelete = { vm.deleteReminder(it) }
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    section: GroupSection,
    onPauseToggle: () -> Unit,
    onClickReminder: (Long) -> Unit,
    onDelete: (Reminder) -> Unit,
) {
    val now = System.currentTimeMillis()
    val paused = section.group.pausedUntilMs?.let { it > now } == true
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(section.group.color))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    section.group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = onPauseToggle,
                    label = { Text(stringResource(if (paused) R.string.resume else R.string.pause)) },
                    leadingIcon = {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null
                        )
                    }
                )
            }
            val ctx = LocalContext.current
            if (section.reminders.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reminders_empty_group),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                section.reminders.forEach { r ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onClickReminder(r.id) }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                describe(ctx, r),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { onDelete(r) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingVoiceCard(
    entry: PendingVoiceReminderEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val hasError = entry.lastError != null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hasError) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(R.string.pending_voice_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "“${entry.transcript}”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(6.dp))
            val statusText = entry.lastError?.let {
                stringResource(R.string.pending_voice_failed_prefix, it)
            } ?: stringResource(R.string.pending_voice_processing)
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (hasError) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pending_voice_retry))
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

private fun describe(ctx: android.content.Context, r: Reminder): String = when (val s = r.schedule) {
    is app.nock.android.domain.model.Schedule.OneShot -> ctx.getString(R.string.schedule_once)
    is app.nock.android.domain.model.Schedule.Daily -> {
        val times = s.timesOfDayMinutes.sorted().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
        ctx.getString(R.string.schedule_daily_at, times)
    }
    is app.nock.android.domain.model.Schedule.Weekly -> {
        val locale = Locale.getDefault()
        val days = s.daysOfWeek.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        val times = s.timesOfDayMinutes.sorted().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
        ctx.getString(R.string.schedule_days_at, days, times)
    }
    is app.nock.android.domain.model.Schedule.Monthly ->
        ctx.getString(
            R.string.schedule_monthly_at,
            s.dayOfMonth,
            "%02d:%02d".format(s.timeOfDayMinutes / 60, s.timeOfDayMinutes % 60)
        )
    is app.nock.android.domain.model.Schedule.IntervalFromLast -> {
        val hrs = s.intervalMs / 3_600_000.0
        if (hrs >= 1.0) ctx.getString(R.string.schedule_every_h_after_done, "%.1f".format(hrs))
        else ctx.getString(R.string.schedule_every_min_after_done, (s.intervalMs / 60_000).toInt())
    }
    is app.nock.android.domain.model.Schedule.OnUnlock ->
        if (r.lastCompletedAt != null && r.lastCompletedAt >= s.armedAtMs)
            ctx.getString(R.string.schedule_on_unlock_fired)
        else
            ctx.getString(R.string.schedule_on_unlock_armed)
}
