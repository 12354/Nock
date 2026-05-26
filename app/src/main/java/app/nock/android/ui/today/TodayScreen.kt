package app.nock.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.ui.components.GroupAvatar
import app.nock.android.ui.components.stageIcon
import app.nock.android.ui.voice.VoiceAlarmFab
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class TodaySection(val titleRes: Int, val items: List<TodayItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    vm: TodayViewModel = hiltViewModel()
) {
    val items by vm.items.collectAsState()
    val ctx = LocalContext.current
    val sections = remember(items) { groupByBucket(items) }
    val activeItem = remember(items) { items.firstOrNull { it.isActive } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.today),
                        style = MaterialTheme.typography.titleLarge
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
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.today_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.today_empty_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
        ) {
            if (activeItem != null) {
                item("active-header") {
                    SectionHeader(stringResource(R.string.today_section_active_now))
                }
                item("active-card-${activeItem.reminder.id}") {
                    ActiveEscalationCard(
                        item = activeItem,
                        onDone = { vm.markDone(activeItem.reminder.id) },
                        onSnooze = { vm.snooze(activeItem.reminder.id) },
                        onClick = { onEditReminder(activeItem.reminder.id) }
                    )
                }
            }
            sections.forEach { section ->
                item("hdr-${section.titleRes}") {
                    SectionHeader(stringResource(section.titleRes))
                }
                section.items.forEach { item ->
                    item("row-${item.reminder.id}") {
                        ReminderRow(
                            item = item,
                            timeLabel = formatRowTime(ctx, item.reminder.nextFireAt),
                            relativeLabel = relativeLabel(ctx, item.reminder.nextFireAt),
                            onClick = { onEditReminder(item.reminder.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun ReminderRow(
    item: TodayItem,
    timeLabel: String,
    relativeLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupAvatar(item.group, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.reminder.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(item.group.name)
                    if (relativeLabel != null) {
                        append(" · ")
                        append(relativeLabel)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = timeLabel,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActiveEscalationCard(
    item: TodayItem,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onClick: () -> Unit,
) {
    val active = item.active ?: return
    val color = Color(item.group.color)
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(item.group, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${item.group.name.uppercase()} · ${stringResource(R.string.today_firing_now).uppercase()}",
                        color = color,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.reminder.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            StageProgress(chain = active.chain, currentIndex = active.currentStageIndex, accent = color)
            Spacer(Modifier.height(14.dp))
            val ctx = LocalContext.current
            val nextStage = if (active.currentStageIndex < active.chain.lastIndex)
                active.chain.stage(active.currentStageIndex + 1)
            else null
            val timeStr = formatClock(active.nextFireAtMs)
            val subtitle = if (nextStage != null) {
                val name = stageDisplayName(ctx, nextStage.type)
                stringResource(R.string.today_active_next_stage, name, timeStr)
            } else {
                stringResource(R.string.today_active_repeats_at, timeStr)
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.done))
                }
                FilledTonalButton(onClick = onSnooze) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.snooze))
                }
            }
        }
    }
}

@Composable
private fun StageProgress(
    chain: EscalationChain,
    currentIndex: Int,
    accent: Color,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val outline = MaterialTheme.colorScheme.outlineVariant
            val onSurfVar = MaterialTheme.colorScheme.onSurfaceVariant
            val ctx = LocalContext.current
            chain.stages.forEachIndexed { i, stage ->
                val done = i < currentIndex
                val live = i == currentIndex
                val tint = when {
                    done -> onSurfVar
                    live -> accent
                    else -> outline
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (live) Modifier else Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (live) 28.dp else 22.dp)
                            .clip(CircleShape)
                            .then(
                                if (live) Modifier.background(accent)
                                else Modifier.border(1.5.dp, tint, CircleShape)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (done) Icons.Filled.Check else stageIcon(stage.type),
                            contentDescription = null,
                            tint = if (live) Color.Black else tint,
                            modifier = Modifier.size(if (live) 16.dp else 12.dp)
                        )
                    }
                    if (live) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stageDisplayName(ctx, stage.type),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (i < chain.stages.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 6.dp)
                            .background(
                                if (done) onSurfVar else outline,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}

private fun stageDisplayName(ctx: android.content.Context, type: app.nock.android.domain.model.StageType): String {
    val res = when (type) {
        app.nock.android.domain.model.StageType.SILENT -> R.string.stage_type_silent
        app.nock.android.domain.model.StageType.TELEGRAM -> R.string.stage_type_telegram
        app.nock.android.domain.model.StageType.ALARM_VIBRATE -> R.string.stage_type_alarm_vibrate
        app.nock.android.domain.model.StageType.ALARM -> R.string.stage_type_alarm
    }
    return ctx.getString(res)
}

private fun groupByBucket(items: List<TodayItem>): List<TodaySection> {
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val nextHourCutoff = now + 60 * 60_000L

    val nextHour = mutableListOf<TodayItem>()
    val later = mutableListOf<TodayItem>()
    val tomorrow = mutableListOf<TodayItem>()
    val laterDays = mutableListOf<TodayItem>()

    items
        .filter { !it.isActive && it.reminder.nextFireAt != null }
        .sortedBy { it.reminder.nextFireAt }
        .forEach { item ->
            val ms = item.reminder.nextFireAt ?: return@forEach
            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toLocalDate()
            when {
                date == today && ms <= nextHourCutoff -> nextHour += item
                date == today -> later += item
                date == today.plusDays(1) -> tomorrow += item
                else -> laterDays += item
            }
        }

    return buildList {
        if (nextHour.isNotEmpty()) add(TodaySection(R.string.today_section_next_hour, nextHour))
        if (later.isNotEmpty()) add(TodaySection(R.string.today_section_later_today, later))
        if (tomorrow.isNotEmpty()) add(TodaySection(R.string.today_section_tomorrow, tomorrow))
        if (laterDays.isNotEmpty()) add(TodaySection(R.string.today_section_later, laterDays))
    }
}

private fun formatRowTime(ctx: android.content.Context, ms: Long?): String {
    if (ms == null) return ctx.getString(R.string.today_no_next_fire)
    val locale = Locale.getDefault()
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    return when (dt.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "$time · ${dt.format(DateTimeFormatter.ofPattern("EEE", locale))}"
        else -> "$time · ${dt.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))}"
    }
}

private fun formatClock(ms: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
}

private fun relativeLabel(ctx: android.content.Context, ms: Long?): String? {
    if (ms == null) return null
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return null
    val minutes = (diff / 60_000L).toInt()
    return when {
        minutes < 60 -> ctx.getString(R.string.time_in_minutes, minutes)
        minutes < 24 * 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) ctx.getString(R.string.time_in_hours, h)
            else ctx.getString(R.string.time_in_hours_minutes, h, m)
        }
        else -> null
    }
}
