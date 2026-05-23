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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    vm: RemindersViewModel = hiltViewModel()
) {
    val sections by vm.sections.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Reminders") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddReminder,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sections, key = { it.group.id }) { section ->
                GroupCard(
                    section = section,
                    onPauseToggle = {
                        if (section.group.pausedUntilMs != null) vm.unpauseGroup(section.group)
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
                    label = { Text(if (paused) "Resume" else "Pause") },
                    leadingIcon = {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null
                        )
                    }
                )
            }
            if (section.reminders.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No reminders in this group.",
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
                                describe(r),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { onDelete(r) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}

private fun describe(r: Reminder): String = when (val s = r.schedule) {
    is app.nock.android.domain.model.Schedule.OneShot -> "Once"
    is app.nock.android.domain.model.Schedule.Daily -> {
        val times = s.timesOfDayMinutes.sorted().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
        "Daily at $times"
    }
    is app.nock.android.domain.model.Schedule.Weekly -> {
        val days = s.daysOfWeek.sorted().joinToString(", ") { it.name.take(3) }
        val times = s.timesOfDayMinutes.sorted().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }
        "$days at $times"
    }
    is app.nock.android.domain.model.Schedule.Monthly ->
        "Monthly on day ${s.dayOfMonth} at %02d:%02d".format(s.timeOfDayMinutes / 60, s.timeOfDayMinutes % 60)
    is app.nock.android.domain.model.Schedule.IntervalFromLast -> {
        val hrs = s.intervalMs / 3_600_000.0
        if (hrs >= 1.0) "Every ${"%.1f".format(hrs)} h after last done"
        else "Every ${s.intervalMs / 60_000} min after last done"
    }
}
