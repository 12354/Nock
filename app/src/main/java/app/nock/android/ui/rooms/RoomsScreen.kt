@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.rooms

import android.Manifest
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nock.android.R
import app.nock.android.wifi.SpotQuality

@Composable
fun RoomsScreen(
    onBack: () -> Unit,
    vm: RoomsViewModel = hiltViewModel()
) {
    val rooms by vm.rooms.collectAsState()
    val capture by vm.capture.collectAsState()
    val permissions by vm.permissions.collectAsState()

    var addDialog by remember { mutableStateOf(false) }
    var renameRoom by remember { mutableStateOf<RoomUi?>(null) }
    var deleteRoom by remember { mutableStateOf<RoomUi?>(null) }

    // Permission state can change in the system settings while we're paused.
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
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshPermissions() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rooms_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.rooms_add)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard(stringResource(R.string.rooms_info))
            }
            if (!permissions.fineLocation) {
                item {
                    PermissionBanner(
                        text = stringResource(R.string.rooms_perm_fine),
                        action = stringResource(R.string.rooms_perm_grant),
                        onClick = {
                            finePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    )
                }
            } else if (!permissions.backgroundLocation) {
                item {
                    PermissionBanner(
                        text = stringResource(R.string.rooms_perm_background),
                        action = stringResource(R.string.rooms_perm_grant),
                        onClick = {
                            backgroundPermissionLauncher.launch(
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        }
                    )
                }
            }
            if (!permissions.wifiAvailable) {
                item {
                    PermissionBanner(
                        text = stringResource(R.string.rooms_wifi_off),
                        action = null,
                        onClick = {}
                    )
                }
            }
            items(rooms, key = { it.id }) { room ->
                RoomCard(
                    room = room,
                    capture = capture?.takeIf { it.roomId == room.id },
                    captureBusy = capture?.finished == false,
                    canCapture = permissions.fineLocation && permissions.wifiAvailable,
                    onCapture = { replace -> vm.startCapture(room.id, replace) },
                    onDismissCapture = vm::dismissCaptureResult,
                    onRename = { renameRoom = room },
                    onDelete = { deleteRoom = room },
                )
            }
        }
    }

    if (addDialog) {
        NameDialog(
            title = stringResource(R.string.rooms_add),
            initial = "",
            onConfirm = { vm.addRoom(it); addDialog = false },
            onDismiss = { addDialog = false }
        )
    }
    renameRoom?.let { room ->
        NameDialog(
            title = stringResource(R.string.rooms_rename),
            initial = room.name,
            onConfirm = { vm.renameRoom(room.id, it); renameRoom = null },
            onDismiss = { renameRoom = null }
        )
    }
    deleteRoom?.let { room ->
        AlertDialog(
            onDismissRequest = { deleteRoom = null },
            title = { Text(stringResource(R.string.rooms_delete_title, room.name)) },
            text = { Text(stringResource(R.string.rooms_delete_message)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteRoom(room.id); deleteRoom = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRoom = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RoomCard(
    room: RoomUi,
    capture: CaptureUi?,
    captureBusy: Boolean,
    canCapture: Boolean,
    onCapture: (replace: Boolean) -> Unit,
    onDismissCapture: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MeetingRoom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (room.sampleCount == 0) {
                            stringResource(R.string.rooms_not_captured)
                        } else {
                            stringResource(R.string.rooms_samples_count, room.sampleCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rooms_rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }

            room.quality?.let { q ->
                Spacer(Modifier.height(4.dp))
                QualityLine(q, room.strongAps ?: 0)
            }

            Spacer(Modifier.height(8.dp))
            when {
                capture != null && !capture.finished -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                R.string.rooms_capturing,
                                capture.scansDone,
                                RoomsViewModel.CAPTURE_SCANS
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                capture != null -> {
                    if (capture.failed) {
                        Text(
                            stringResource(R.string.rooms_capture_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        QualityLine(capture.quality ?: SpotQuality.POOR, capture.strongAps ?: 0)
                    }
                    TextButton(onClick = onDismissCapture) {
                        Text(stringResource(R.string.rooms_capture_ok))
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (room.sampleCount == 0) {
                            AssistChip(
                                enabled = canCapture && !captureBusy,
                                onClick = { onCapture(false) },
                                label = { Text(stringResource(R.string.rooms_capture)) },
                                leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) }
                            )
                        } else {
                            AssistChip(
                                enabled = canCapture && !captureBusy,
                                onClick = { onCapture(true) },
                                label = { Text(stringResource(R.string.rooms_recapture)) },
                                leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) }
                            )
                            AssistChip(
                                enabled = canCapture && !captureBusy,
                                onClick = { onCapture(false) },
                                label = { Text(stringResource(R.string.rooms_add_samples)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityLine(quality: SpotQuality, strongAps: Int) {
    val (res, color) = when (quality) {
        SpotQuality.GOOD -> R.string.rooms_quality_good to MaterialTheme.colorScheme.primary
        SpotQuality.FAIR -> R.string.rooms_quality_fair to MaterialTheme.colorScheme.onSurfaceVariant
        SpotQuality.POOR -> R.string.rooms_quality_poor to MaterialTheme.colorScheme.error
    }
    Text(
        stringResource(res, strongAps),
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun PermissionBanner(text: String, action: String?, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            )
            if (action != null) {
                TextButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rooms_name_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
