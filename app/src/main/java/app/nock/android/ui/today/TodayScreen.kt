package app.nock.android.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    vm: TodayViewModel = hiltViewModel()
) {
    val items by vm.items.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Today") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddReminder,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No reminders yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap Add to create your first one.", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.reminder.id }) { item ->
                    ReminderRow(
                        item = item,
                        onDone = { vm.markDone(item.reminder.id) },
                        onClick = { onEditReminder(item.reminder.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    item: TodayItem,
    onDone: () -> Unit,
    onClick: () -> Unit,
) {
    val color = Color(item.group.color)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (item.isActive) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.group.name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.reminder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

private fun subtitle(item: TodayItem): String {
    val r = item.reminder
    val prefix = "${item.group.name} • "
    val time = r.nextFireAt?.let { formatFireTime(it) } ?: "no next fire"
    val active = if (item.isActive) " • firing now" else ""
    return prefix + time + active
}

private fun formatFireTime(ms: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    val today = LocalDateTime.now()
    val sameDay = dt.toLocalDate() == today.toLocalDate()
    val tomorrow = dt.toLocalDate() == today.toLocalDate().plusDays(1)
    val timeStr = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        sameDay -> "Today $timeStr"
        tomorrow -> "Tomorrow $timeStr"
        else -> dt.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
    }
}
