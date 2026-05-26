package app.nock.android.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.ui.components.GroupAvatar
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
        topBar = {
            // The design uses a large M3 app bar for Reminders. Keep the title aligned to start
            // with a heavier weight so the grouped sections below sit under a clear visual anchor.
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.reminders),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Normal
                    )
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VoiceAlarmFab()
                ExtendedFloatingActionButton(
                    onClick = onAddReminder,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 144.dp)
        ) {
            items(pending, key = { "pending-${it.id}" }) { entry ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    PendingVoiceCard(
                        entry = entry,
                        onRetry = { vm.retryPending(entry) },
                        onDelete = { vm.deletePending(entry) }
                    )
                }
            }
            sections.forEach { section ->
                item(key = "group-${section.group.id}") {
                    GroupSection(
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
}

@Composable
private fun GroupSection(
    section: GroupSection,
    onPauseToggle: () -> Unit,
    onClickReminder: (Long) -> Unit,
    onDelete: (Reminder) -> Unit,
) {
    val now = System.currentTimeMillis()
    val paused = section.group.isPaused(now)
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupAvatar(section.group, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        section.group.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (paused) {
                        Spacer(Modifier.width(8.dp))
                        PausedChip()
                    }
                }
                Text(
                    text = section.reminders.size.let { c ->
                        if (c == 1) "1 reminder" else "$c reminders"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onPauseToggle) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(if (paused) R.string.resume else R.string.pause),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (section.reminders.isEmpty()) {
            Text(
                stringResource(R.string.reminders_empty_group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = 68.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
        } else {
            val ctx = LocalContext.current
            section.reminders.forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClickReminder(r.id) }
                        .padding(start = 68.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            r.name,
                            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            describe(ctx, r),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { onDelete(r) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PausedChip() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(
                stringResource(R.string.reminders_paused_chip),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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
                else MaterialTheme.colorScheme.onSurfaceVariant
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

