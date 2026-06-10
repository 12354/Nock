@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.debug

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nock.android.R
import app.nock.android.wifi.RoomFingerprints
import app.nock.android.wifi.SpotQuality
import androidx.compose.ui.res.stringResource

/**
 * Live WiFi indoor-positioning inspector. Tap Scan and it does exactly what a
 * background room check does — read a scan, score every captured room against it —
 * then shows the full ranking, the winner (if any clears the threshold), and the
 * raw AP list. Hardcoded English strings, matching the sibling [DebugScreen].
 */
@Composable
fun WifiDebugScreen(
    onBack: () -> Unit,
    vm: WifiDebugViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Permissions can be toggled in system settings while we're paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val finePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshPermissions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi positioning") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = state.probe != null,
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.buildDump(state)))
                            Toast.makeText(context, "Copied probe", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy probe")
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
            item { PermissionsCard(state, onGrantFine = { finePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) }

            item {
                Button(
                    onClick = { vm.scan() },
                    enabled = !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.scanning) "Scanning…" else "Scan now")
                }
            }

            if (state.scanning) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            state.error?.let { err ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            val probe = state.probe
            if (probe != null) {
                item { ScanSummaryCard(probe) }

                if (state.noSamples) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Text(
                                "No rooms have captured fingerprints yet — capture some on the Rooms screen, then scan here.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                sectionHeader("Room scores", probe.rooms.size)
                items(probe.rooms, key = { "room-${it.roomId}" }) { room ->
                    RoomScoreRow(room, isWinner = room.roomId == probe.winner?.roomId)
                }

                sectionHeader("Access points", probe.aps.size)
                items(probe.aps, key = { "ap-${it.bssid}" }) { ap ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            ap.bssid,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${ap.level} dBm",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = if (ap.strong) FontWeight.Bold else FontWeight.Normal,
                            color = if (ap.strong) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun PermissionsCard(state: WifiDebugState, onGrantFine: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Status", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            StatusLine("Location (fine)", state.hasFine)
            StatusLine("Location (background)", state.hasBackground)
            StatusLine("WiFi scanning", state.wifiAvailable)
            if (!state.hasFine) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGrantFine) { Text("Grant location") }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScanSummaryCard(probe: WifiProbe) {
    val winner = probe.winner
    val color = if (winner != null) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onColor = if (winner != null) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                winner?.let { "Detected: ${it.name} (${WifiDebugViewModel.pct(it.score)})" }
                    ?: "No room detected — nothing clears ${(RoomFingerprints.MIN_MATCH_SCORE * 100).toInt()}% " +
                        "with a ${(RoomFingerprints.MIN_MATCH_MARGIN * 100).toInt()}% lead",
                fontWeight = FontWeight.SemiBold,
                color = onColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${probe.apCount} APs · ${probe.strongAps} strong · ${qualityLabel(probe.quality)} · scan age ${probe.ageMs / 1000}s",
                color = onColor,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!probe.enoughAps) {
                Text(
                    "Too few APs (need ${RoomFingerprints.MIN_SCAN_APS}) — matching is skipped.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RoomScoreRow(room: RoomMatchRow, isWinner: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                room.name,
                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                WifiDebugViewModel.pct(room.score),
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val frac = (room.score ?: 0.0).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = frac,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Text(
            if (room.sampleCount == 0) "no samples captured" else "${room.sampleCount} sample(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun qualityLabel(q: SpotQuality): String = when (q) {
    SpotQuality.GOOD -> "good spot"
    SpotQuality.FAIR -> "fair spot"
    SpotQuality.POOR -> "poor spot"
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(title: String, count: Int) {
    item(key = "header-$title") {
        Column {
            Spacer(Modifier.height(8.dp))
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
