@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package app.nock.android.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.update.UpdateManager
import app.nock.android.update.UpdateResult
import app.nock.android.update.UpdateState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            state.chain?.let {
                StageChainSection(chain = it, onChange = vm::setChain)
            }
            GroupsSection(state.groups, vm)
            TelegramSection(state.telegramToken, state.telegramChat, state.telegramStatus, vm)
            DriveSection(state.driveEmail, state.driveLastSyncMs, state.driveStatus, vm)
            UpdateSection()
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val updateManager = remember { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    suspend fun doCheck() {
        updateState = UpdateState.Checking
        updateState = when (val result = updateManager.checkForUpdate()) {
            is UpdateResult.Success ->
                if (result.info.hasUpdate) UpdateState.Available(result.info)
                else UpdateState.UpToDate
            is UpdateResult.Failure -> UpdateState.Error(result.error)
        }
    }

    LaunchedEffect(Unit) { doCheck() }

    SectionCard("App update") {
        when (val s = updateState) {
            UpdateState.Idle, UpdateState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UpdateState.Available -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Update available", fontWeight = FontWeight.SemiBold)
                        Text(
                            "build ${s.info.currentVersionCode} → ${s.info.remoteVersionCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Button(onClick = {
                        scope.launch {
                            updateState = UpdateState.Downloading(0f)
                            val apk = updateManager.downloadUpdate { progress ->
                                updateState = UpdateState.Downloading(progress)
                            }
                            if (apk != null) {
                                updateState = UpdateState.Installing
                                updateManager.installApk(apk)
                            } else {
                                updateState = UpdateState.Error("Download failed")
                            }
                        }
                    }) { Text("Update") }
                }
            }
            is UpdateState.Downloading -> {
                Text(
                    "Downloading… ${(s.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = s.progress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            UpdateState.Installing -> {
                Text("Opening installer…", style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "You're up to date",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { scope.launch { doCheck() } }) { Text("Re-check") }
                }
            }
            is UpdateState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { doCheck() } }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun StageChainSection(chain: EscalationChain, onChange: (EscalationChain) -> Unit) {
    var local by remember(chain) { mutableStateOf(chain) }
    SectionCard("Escalation chain (global default)") {
        local.stages.forEachIndexed { idx, stage ->
            StageRow(
                stage = stage,
                canMoveUp = idx > 0,
                canMoveDown = idx < local.stages.lastIndex,
                onMoveUp = {
                    val list = local.stages.toMutableList()
                    val tmp = list[idx]; list[idx] = list[idx - 1]; list[idx - 1] = tmp
                    local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                },
                onMoveDown = {
                    val list = local.stages.toMutableList()
                    val tmp = list[idx]; list[idx] = list[idx + 1]; list[idx + 1] = tmp
                    local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                },
                onOffsetChange = { newOff ->
                    val list = local.stages.toMutableList()
                    list[idx] = list[idx].copy(offsetMs = newOff)
                    local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                },
                onRemove = {
                    if (local.stages.size > 1) {
                        val list = local.stages.toMutableList().also { it.removeAt(idx) }
                        local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                    }
                }
            )
        }
        val missing = StageType.values().toSet() - local.stages.map { it.type }.toSet()
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Add stage:", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow {
                missing.forEach { t ->
                    AssistChip(
                        onClick = {
                            val nextOff = (local.stages.lastOrNull()?.offsetMs ?: 0L) + 5 * 60_000L
                            val list = local.stages + StageConfig(t, nextOff)
                            local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                        },
                        label = { Text(t.name) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        IntervalField(
            label = "Last-stage repeat interval (minutes)",
            valueMin = (local.repeatIntervalMs / 60_000L).toInt()
        ) { newMin ->
            local = local.copy(repeatIntervalMs = newMin * 60_000L); onChange(local)
        }
    }
}

@Composable
private fun StageRow(
    stage: StageConfig,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOffsetChange: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stage.type.name, fontWeight = FontWeight.SemiBold)
            val mins = (stage.offsetMs / 60_000L).toInt()
            OutlinedTextField(
                value = mins.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> onOffsetChange(v * 60_000L) } },
                label = { Text("Offset (min)") },
                modifier = Modifier.width(160.dp).padding(top = 4.dp)
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Up") }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Down") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
    }
}

@Composable
private fun IntervalField(label: String, valueMin: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = valueMin.toString(),
        onValueChange = { it.toIntOrNull()?.let { v -> if (v >= 1) onChange(v) } },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun GroupsSection(groups: List<Group>, vm: SettingsViewModel) {
    SectionCard("Groups") {
        groups.forEach { g ->
            GroupRow(g, vm)
            HorizontalDivider()
        }
    }
}

@Composable
private fun GroupRow(g: Group, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            modifier = Modifier.size(20.dp).clip(CircleShape).background(Color(g.color))
        )
        Spacer(Modifier.width(8.dp))
        Text(g.name, modifier = Modifier.weight(1f))
        Text(
            if (g.overrideChain != null) "custom chain" else "default chain",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        IconButton(onClick = { editing = !editing }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
    }
    if (editing) {
        GroupOverrideEditor(g, vm) { editing = false }
    }
}

@Composable
private fun GroupOverrideEditor(g: Group, vm: SettingsViewModel, onClose: () -> Unit) {
    var mirror by remember(g.id) { mutableStateOf(g.telegramSilentMirror) }
    var override by remember(g.id) { mutableStateOf(g.overrideChain) }

    Column(modifier = Modifier.padding(start = 28.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = mirror, onCheckedChange = {
                mirror = it
                vm.updateGroup(g.copy(telegramSilentMirror = it))
            })
            Spacer(Modifier.width(8.dp))
            Text("Mirror silent stage to Telegram")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = override != null, onCheckedChange = { on ->
                override = if (on) app.nock.android.domain.model.DefaultChain.CHAIN else null
                vm.updateGroup(g.copy(overrideChain = override))
            })
            Spacer(Modifier.width(8.dp))
            Text("Custom escalation chain")
        }
        override?.let { chain ->
            Text("Override chain", style = MaterialTheme.typography.labelMedium)
            StageChainSection(chain = chain, onChange = {
                override = it
                vm.updateGroup(g.copy(overrideChain = it))
            })
        }
        TextButton(onClick = onClose) { Text("Done editing group") }
    }
}

@Composable
private fun TelegramSection(token: String, chat: String, status: String?, vm: SettingsViewModel) {
    var t by remember(token) { mutableStateOf(token) }
    var c by remember(chat) { mutableStateOf(chat) }
    SectionCard("Telegram bot") {
        OutlinedTextField(
            value = t, onValueChange = { t = it },
            label = { Text("Bot token") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = c, onValueChange = { c = it },
            label = { Text("Chat ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setTelegram(t, c) }) { Text("Save") }
            OutlinedButton(onClick = { vm.setTelegram(t, c); vm.testTelegram() }) { Text("Test send") }
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DriveSection(email: String?, lastSyncMs: Long?, status: String?, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }
            .onSuccess { acc -> vm.setDriveEmail(acc.email) }
    }
    SectionCard("Google Drive (App Folder)") {
        if (email == null) {
            Text("Not signed in.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
                    .build()
                val client = GoogleSignIn.getClient(ctx, gso)
                launcher.launch(client.signInIntent)
            }) { Text("Sign in") }
        } else {
            Text("Signed in as $email")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::syncPush) { Text("Push now") }
                OutlinedButton(onClick = vm::syncPull) { Text("Pull now") }
                TextButton(onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                    GoogleSignIn.getClient(ctx, gso).signOut()
                    vm.setDriveEmail(null)
                }) { Text("Sign out") }
            }
            if (lastSyncMs != null) {
                Spacer(Modifier.height(8.dp))
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                Text(
                    "Last sync: " + LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMs), ZoneId.systemDefault()).format(fmt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
