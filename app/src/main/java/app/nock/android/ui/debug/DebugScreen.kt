@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import androidx.compose.ui.res.stringResource

/**
 * Raw database inspector. Lists every row of every table verbatim and flags the
 * rows the normal screens hide (orphaned reminders, corrupt schedules, orphan
 * escalations) so nothing can be invisible.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    onOpenWifiDebug: () -> Unit = {},
    vm: DebugViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val snapshot by vm.snapshot.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_cat_debug)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.buildDump(snapshot)))
                        Toast.makeText(context, "Copied raw dump", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(
                    onClick = onOpenWifiDebug,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "WiFi indoor positioning — live scan & room match",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            item {
                AnomalyBanner(snapshot)
            }

            sectionHeader("Groups", snapshot.groups.size)
            items(snapshot.groups, key = { "g-${it.id}" }) { RawRow(it.toString()) }

            sectionHeader("Reminders", snapshot.reminders.size)
            items(snapshot.reminders, key = { "r-${it.entity.id}" }) { r ->
                val flags = buildList {
                    if (!r.groupExists) add("ORPHAN — groupId=${r.entity.groupId} has no group (hidden from list, can still fire)")
                    if (!r.scheduleDecodes) add("CORRUPT schedule JSON (dropped from list)")
                }
                RawRow(r.entity.toString(), flags)
            }

            sectionHeader("Active escalations", snapshot.active.size)
            items(snapshot.active, key = { "a-${it.entity.id}" }) { a ->
                val flags = buildList {
                    if (!a.reminderExists) add("ORPHAN — reminderId=${a.entity.reminderId} has no reminder")
                    if (!a.chainDecodes) add("CORRUPT chain JSON")
                }
                RawRow(a.entity.toString(), flags)
            }

            sectionHeader("Settings", snapshot.settings.size)
            items(snapshot.settings, key = { "s-${it.key}" }) {
                RawRow("${it.key} = ${DebugViewModel.displaySetting(it.key, it.value)}")
            }

            sectionHeader("Pending voice", snapshot.pendingVoice.size)
            items(snapshot.pendingVoice, key = { "pv-${it.id}" }) { RawRow(it.toString()) }

            sectionHeader("Pending Telegram deletions", snapshot.pendingTelegram.size)
            items(snapshot.pendingTelegram, key = { "pt-${it.messageId}" }) { RawRow(it.toString()) }

            item { Spacer(Modifier.width(1.dp)) }
        }
    }
}

@Composable
private fun AnomalyBanner(s: DebugSnapshot) {
    val anomalies = s.anomalyCount
    val color = if (anomalies > 0) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onColor = if (anomalies > 0) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (anomalies > 0) "$anomalies anomaly row(s) — these are hidden from the normal UI"
                else "No anomalies — every row is reachable from the normal UI",
                color = onColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (anomalies > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "${s.hiddenReminders.size} hidden reminder(s), ${s.orphanEscalations.size} orphan escalation(s)",
                    color = onColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(title: String, count: Int) {
    item(key = "header-$title") {
        Column {
            Spacer(Modifier.width(8.dp))
            Text(
                "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RawRow(text: String, flags: List<String> = emptyList()) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        flags.forEach { f ->
            Row {
                Text(
                    "⚠ $f",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
