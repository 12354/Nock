@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package app.nock.android.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.ui.LocaleHelper
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
import java.util.Locale

/** Settings category routes, used both for navigation and for the landing list. */
object SettingsCategory {
    const val GENERAL = "general"
    const val NOTIFICATIONS = "notifications"
    const val INTEGRATIONS = "integrations"
    const val DIAGNOSTICS = "diagnostics"
    const val DEBUG = "debug"
}

/** Per-integration routes, used by the Integrations landing list and navigation. */
object IntegrationCategory {
    const val TELEGRAM = "telegram"
    const val CALENDAR = "calendar"
    const val DEEPSEEK = "deepseek"
    const val DRIVE = "drive"
}

/**
 * Settings landing screen: a short list of categories that each drill into their
 * own screen — the standard Android settings pattern, rather than one long scroll.
 */
@Composable
fun SettingsScreen(
    onOpenCategory: (String) -> Unit = {},
    onEditGroup: (Long) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                CategoryRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_cat_general),
                    subtitle = stringResource(R.string.settings_cat_general_sub),
                    onClick = { onOpenCategory(SettingsCategory.GENERAL) }
                )
                HorizontalDivider()
                CategoryRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.settings_cat_notifications),
                    subtitle = stringResource(R.string.settings_cat_notifications_sub),
                    onClick = { onOpenCategory(SettingsCategory.NOTIFICATIONS) }
                )
                HorizontalDivider()
                CategoryRow(
                    icon = Icons.Outlined.Hub,
                    title = stringResource(R.string.settings_cat_integrations),
                    subtitle = stringResource(R.string.settings_cat_integrations_sub),
                    onClick = { onOpenCategory(SettingsCategory.INTEGRATIONS) }
                )
                HorizontalDivider()
                CategoryRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_cat_diagnostics),
                    subtitle = stringResource(R.string.settings_cat_diagnostics_sub),
                    onClick = { onOpenCategory(SettingsCategory.DIAGNOSTICS) }
                )
                HorizontalDivider()
                CategoryRow(
                    icon = Icons.Filled.Build,
                    title = stringResource(R.string.settings_cat_debug),
                    subtitle = stringResource(R.string.settings_cat_debug_sub),
                    onClick = { onOpenCategory(SettingsCategory.DEBUG) }
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Shared scaffold for a settings category sub-screen: title + back arrow + scrolling content. */
@Composable
private fun CategoryScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content
        )
    }
}

@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    CategoryScaffold(stringResource(R.string.settings_cat_general), onBack) {
        LanguageSection(vm)
        UpdateSection()
    }
}

@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    onEditGroup: (Long) -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    // Editing the global chain saves per-keystroke; apply it to already-armed
    // reminders once, when this screen leaves composition (back, system back, or
    // navigating into a group editor — the re-arm is idempotent either way).
    DisposableEffect(Unit) {
        onDispose { vm.applyChainEditsIfDirty() }
    }
    CategoryScaffold(stringResource(R.string.settings_cat_notifications), onBack) {
        state.chain?.let {
            StageChainSection(chain = it, onChange = vm::setChain)
        }
        PreAlarmSoundSection(state.preAlarmSoundUri, vm)
        GroupsSection(state.groups, onEditGroup, onAddGroup = { onEditGroup(0L) })
    }
}

@Composable
private fun PreAlarmSoundSection(soundUri: String?, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: android.net.Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        // A null picked URI means the user chose "Silent".
        vm.setPreAlarmSound(picked?.toString())
    }

    val currentLabel = when {
        soundUri == null -> stringResource(R.string.settings_prealarm_sound_default)
        soundUri.isBlank() -> stringResource(R.string.settings_prealarm_sound_silent)
        else -> remember(soundUri) {
            runCatching { RingtoneManager.getRingtone(ctx, android.net.Uri.parse(soundUri))?.getTitle(ctx) }
                .getOrNull()
        } ?: stringResource(R.string.settings_prealarm_sound_custom)
    }

    SectionCard(stringResource(R.string.settings_prealarm_sound_title)) {
        Text(
            stringResource(R.string.settings_prealarm_sound_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_prealarm_sound_current, currentLabel),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val existing = soundUri?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, ctx.getString(R.string.settings_prealarm_sound_title))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            }
            launcher.launch(intent)
        }) { Text(stringResource(R.string.settings_prealarm_sound_choose)) }
    }
}

/**
 * Integrations landing screen: each integration drills into its own sub-screen,
 * mirroring the top-level settings landing pattern.
 */
@Composable
fun IntegrationsSettingsScreen(
    onBack: () -> Unit,
    onOpenIntegration: (String) -> Unit = {},
) {
    CategoryScaffold(stringResource(R.string.settings_cat_integrations), onBack) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            CategoryRow(
                icon = Icons.AutoMirrored.Filled.Send,
                title = stringResource(R.string.settings_telegram_title),
                subtitle = stringResource(R.string.settings_int_telegram_sub),
                onClick = { onOpenIntegration(IntegrationCategory.TELEGRAM) }
            )
            HorizontalDivider()
            CategoryRow(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.trips_title),
                subtitle = stringResource(R.string.settings_int_calendar_sub),
                onClick = { onOpenIntegration(IntegrationCategory.CALENDAR) }
            )
            HorizontalDivider()
            CategoryRow(
                icon = Icons.Outlined.Psychology,
                title = stringResource(R.string.settings_deepseek_title),
                subtitle = stringResource(R.string.settings_int_deepseek_sub),
                onClick = { onOpenIntegration(IntegrationCategory.DEEPSEEK) }
            )
            HorizontalDivider()
            CategoryRow(
                icon = Icons.Outlined.CloudSync,
                title = stringResource(R.string.settings_drive_title),
                subtitle = stringResource(R.string.settings_int_drive_sub),
                onClick = { onOpenIntegration(IntegrationCategory.DRIVE) }
            )
        }
    }
}

@Composable
fun TelegramSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    CategoryScaffold(stringResource(R.string.settings_telegram_title), onBack) {
        TelegramSection(state.telegramToken, state.telegramChat, state.telegramStatus, vm)
    }
}

@Composable
fun CalendarSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    CategoryScaffold(stringResource(R.string.trips_title), onBack) {
        TripsSection(state, vm)
    }
}

@Composable
fun DeepSeekSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    CategoryScaffold(stringResource(R.string.settings_deepseek_title), onBack) {
        DeepSeekSection(state.deepSeekApiKey, state.deepSeekModel, state.deepSeekBaseUrl, state.deepSeekContext, vm)
    }
}

@Composable
fun DriveSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    CategoryScaffold(stringResource(R.string.settings_drive_title), onBack) {
        DriveSection(state.driveEmail, state.driveLastSyncMs, state.driveStatus, vm)
    }
}

@Composable
fun DiagnosticsSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    CategoryScaffold(stringResource(R.string.settings_cat_diagnostics), onBack) {
        AlarmHistorySection(vm)
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

    // The system installer is a separate activity: when the user cancels (or
    // even completes) it, our activity resumes. If we left the UI parked on
    // "Opening installer…" the user could never retry. Re-check on resume so
    // the section returns to either Available (cancelled) or UpToDate (installed).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && updateState is UpdateState.Installing) {
                scope.launch { doCheck() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val downloadFailedMsg = stringResource(R.string.settings_update_download_failed)
    SectionCard(stringResource(R.string.settings_update_title)) {
        when (val s = updateState) {
            UpdateState.Idle, UpdateState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_update_checking), style = MaterialTheme.typography.bodyMedium)
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
                        Text(stringResource(R.string.settings_update_available), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(
                                R.string.settings_update_build_change,
                                s.info.currentVersionCode,
                                s.info.remoteVersionCode
                            ),
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
                                updateState = UpdateState.Error(downloadFailedMsg)
                            }
                        }
                    }) { Text(stringResource(R.string.settings_update_action_update)) }
                }
            }
            is UpdateState.Downloading -> {
                Text(
                    stringResource(R.string.settings_update_downloading, (s.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = s.progress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            UpdateState.Installing -> {
                Text(stringResource(R.string.settings_update_opening_installer), style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_update_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { scope.launch { doCheck() } }) { Text(stringResource(R.string.settings_update_recheck)) }
                }
            }
            is UpdateState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { doCheck() } }) { Text(stringResource(R.string.settings_update_retry)) }
            }
        }
    }
}

@Composable
private fun LanguageSection(vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val currentTag = LocaleHelper.getLanguageTag(ctx)
    val options = listOf(
        "" to R.string.language_system_default,
        "en" to R.string.language_english,
        "de" to R.string.language_german,
    )
    SectionCard(stringResource(R.string.settings_language_title)) {
        androidx.compose.foundation.layout.FlowRow {
            options.forEach { (tag, labelRes) ->
                FilterChip(
                    selected = tag == currentTag,
                    onClick = {
                        if (tag == currentTag) return@FilterChip
                        LocaleHelper.setLanguageTag(ctx, tag)
                        vm.syncSeedGroupNames()
                        (ctx as? Activity)?.recreate()
                    },
                    label = { Text(stringResource(labelRes)) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
            }
        }
    }
}

private fun stageTypeLabel(type: StageType): Int = app.nock.android.ui.components.stageTypeLabel(type)

@Composable
private fun StageChainSection(chain: EscalationChain, onChange: (EscalationChain) -> Unit) {
    var local by remember(chain) { mutableStateOf(chain) }
    SectionCard(stringResource(R.string.settings_escalation_chain_title)) {
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
            Text(stringResource(R.string.settings_add_stage), style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow {
                missing.forEach { t ->
                    AssistChip(
                        onClick = {
                            val nextOff = (local.stages.lastOrNull()?.offsetMs ?: 0L) + 5 * 60_000L
                            val list = local.stages + StageConfig(t, nextOff)
                            local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                        },
                        label = { Text(stringResource(stageTypeLabel(t))) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        IntervalField(
            label = stringResource(R.string.settings_repeat_interval_label),
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
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = app.nock.android.ui.components.stageIcon(stage.type),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(stageTypeLabel(stage.type)), fontWeight = FontWeight.SemiBold)
            val mins = (stage.offsetMs / 60_000L).toInt()
            OutlinedTextField(
                value = mins.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> onOffsetChange(v * 60_000L) } },
                label = { Text(stringResource(R.string.settings_offset_label)) },
                modifier = Modifier.width(160.dp).padding(top = 4.dp)
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.move_up)) }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.move_down)) }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove)) }
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
private fun GroupsSection(groups: List<Group>, onEditGroup: (Long) -> Unit, onAddGroup: () -> Unit) {
    SectionCard(stringResource(R.string.settings_groups_title)) {
        groups.forEach { g ->
            GroupRow(g, onClick = { onEditGroup(g.id) })
            HorizontalDivider()
        }
        TextButton(onClick = onAddGroup) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add))
        }
    }
}

@Composable
private fun GroupRow(g: Group, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        app.nock.android.ui.components.GroupAvatar(g, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(g.name, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (g.overrideChain != null) R.string.settings_group_custom_chain else R.string.settings_group_default_chain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
        }
    }
}

@Composable
private fun TelegramSection(token: String, chat: String, status: String?, vm: SettingsViewModel) {
    var t by remember(token) { mutableStateOf(token) }
    var c by remember(chat) { mutableStateOf(chat) }
    SectionCard(stringResource(R.string.settings_telegram_title)) {
        OutlinedTextField(
            value = t, onValueChange = { t = it },
            label = { Text(stringResource(R.string.settings_telegram_token)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = c, onValueChange = { c = it },
            label = { Text(stringResource(R.string.settings_telegram_chat_id)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setTelegram(t, c) }) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { vm.setTelegram(t, c); vm.testTelegram() }) { Text(stringResource(R.string.settings_test_send)) }
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
        if (result.resultCode == Activity.RESULT_CANCELED && result.data == null) {
            vm.setDriveStatus(ctx.getString(R.string.status_drive_sign_in_cancelled))
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }
            .onSuccess { acc ->
                vm.setDriveEmail(acc.email)
                vm.setDriveStatus(null)
            }
            .onFailure { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                val detail = if (code != null) "code $code: ${e.message ?: ""}" else (e.message ?: e::class.simpleName ?: "unknown")
                Log.w("DriveSignIn", "Google sign-in failed: $detail", e)
                vm.setDriveStatus(ctx.getString(R.string.status_drive_sign_in_failed, detail))
            }
    }
    SectionCard(stringResource(R.string.settings_drive_title)) {
        if (email == null) {
            Text(stringResource(R.string.settings_not_signed_in))
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
                    .build()
                val client = GoogleSignIn.getClient(ctx, gso)
                // Sign out first so a cached account with a stale/invalid grant doesn't make the
                // picker close with no observable effect.
                client.signOut().addOnCompleteListener {
                    launcher.launch(client.signInIntent)
                }
            }) { Text(stringResource(R.string.sign_in)) }
        } else {
            Text(stringResource(R.string.settings_signed_in_as, email))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::syncPush) { Text(stringResource(R.string.settings_push_now)) }
                OutlinedButton(onClick = vm::syncPull) { Text(stringResource(R.string.settings_pull_now)) }
                TextButton(onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                    GoogleSignIn.getClient(ctx, gso).signOut()
                    vm.setDriveEmail(null)
                }) { Text(stringResource(R.string.sign_out)) }
            }
            if (lastSyncMs != null) {
                Spacer(Modifier.height(8.dp))
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
                Text(
                    stringResource(
                        R.string.settings_last_sync,
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMs), ZoneId.systemDefault()).format(fmt)
                    ),
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
private fun TripsSection(state: SettingsState, vm: SettingsViewModel) {
    var enabled by remember(state.tripsEnabled) { mutableStateOf(state.tripsEnabled) }
    var key by remember(state.tomtomKey) { mutableStateOf(state.tomtomKey) }
    var home by remember(state.tripHomeAddress) { mutableStateOf(state.tripHomeAddress) }
    var buffer by remember(state.tripBufferMin) { mutableStateOf(state.tripBufferMin.toString()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.onCalendarPermissionResult() }

    fun persist() = vm.setTrips(enabled, key, home, buffer.toIntOrNull() ?: state.tripBufferMin)

    SectionCard(stringResource(R.string.trips_title)) {
        Text(
            stringResource(R.string.trips_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trips_enable), modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    if (it && !state.tripHasCalendarPermission) {
                        permLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                    }
                    persist()
                }
            )
        }

        if (enabled) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.trips_tomtom_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = home,
                onValueChange = { home = it },
                label = { Text(stringResource(R.string.trips_home_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = buffer,
                onValueChange = { v -> buffer = v.filter { it.isDigit() }.take(3) },
                label = { Text(stringResource(R.string.trips_buffer_minutes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persist() }) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = { persist(); vm.testRoute() }) {
                    Text(stringResource(R.string.trips_test_route))
                }
                OutlinedButton(
                    onClick = { persist(); vm.syncTripsNow() },
                    enabled = state.tripHasCalendarPermission,
                ) {
                    Text(stringResource(R.string.trips_sync_now))
                }
            }
            state.tripStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            TripSyncLogSection(vm)

            Spacer(Modifier.height(16.dp))
            if (!state.tripHasCalendarPermission) {
                Text(
                    stringResource(R.string.trips_calendar_permission_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permLauncher.launch(android.Manifest.permission.READ_CALENDAR) }) {
                    Text(stringResource(R.string.trips_grant_calendar))
                }
            } else {
                Text(
                    stringResource(R.string.trips_calendars_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.trips_calendars_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                // Only the calendars the user explicitly checks are watched;
                // nothing is selected by default.
                val selected = state.tripSelectedCalendarIds
                state.tripCalendars.forEach { cal ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = cal.id in selected,
                            onCheckedChange = { checked ->
                                val next = if (checked) selected + cal.id else selected - cal.id
                                vm.setTripCalendars(next)
                            }
                        )
                        Column {
                            Text(cal.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (cal.accountName.isNotBlank()) {
                                Text(
                                    cal.accountName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A copyable diagnostic of the most recent calendar sync, shown right under the
 * Sync button. When an event that should have become an alarm doesn't, the user
 * taps Sync now, then copies this — it lists every event found and why each was
 * or wasn't turned into an alarm.
 */
@Composable
private fun TripSyncLogSection(vm: SettingsViewModel) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val logText by vm.tripSyncLogText.collectAsState()
    val copiedMsg = stringResource(R.string.trips_synclog_copied)

    Text(
        stringResource(R.string.trips_synclog_title),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        stringResource(R.string.trips_synclog_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(logText))
                scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
            },
            enabled = logText.isNotBlank()
        ) {
            Text(stringResource(R.string.trips_synclog_copy))
        }
        OutlinedButton(onClick = { vm.clearTripSyncLog() }, enabled = logText.isNotBlank()) {
            Text(stringResource(R.string.trips_synclog_clear))
        }
    }
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            Text(
                text = logText.ifBlank { stringResource(R.string.trips_synclog_empty) },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DeepSeekSection(apiKey: String, model: String, baseUrl: String, context: String, vm: SettingsViewModel) {
    var k by remember(apiKey) { mutableStateOf(apiKey) }
    var m by remember(model) { mutableStateOf(model) }
    var u by remember(baseUrl) { mutableStateOf(baseUrl) }
    var c by remember(context) { mutableStateOf(context) }
    SectionCard(stringResource(R.string.settings_deepseek_title)) {
        Text(
            stringResource(R.string.settings_deepseek_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = k,
            onValueChange = { k = it },
            label = { Text(stringResource(R.string.settings_deepseek_api_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = m,
            onValueChange = { m = it },
            label = { Text(stringResource(R.string.settings_deepseek_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = u,
            onValueChange = { u = it },
            label = { Text(stringResource(R.string.settings_deepseek_base_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.settings_deepseek_context_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = c,
            onValueChange = { c = it },
            label = { Text(stringResource(R.string.settings_deepseek_context)) },
            placeholder = { Text(stringResource(R.string.settings_deepseek_context_placeholder)) },
            singleLine = false,
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setDeepSeek(k, m, u, c) }) { Text(stringResource(R.string.save)) }
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

@Composable
private fun AlarmHistorySection(vm: SettingsViewModel) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var logText by remember { mutableStateOf(vm.alarmHistoryDump()) }
    val copiedMsg = stringResource(R.string.settings_alarm_history_copied)

    SectionCard(stringResource(R.string.settings_alarm_history_title)) {
        Text(
            stringResource(R.string.settings_alarm_history_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { logText = vm.alarmHistoryDump() }) {
                Text(stringResource(R.string.settings_alarm_history_refresh))
            }
            Button(onClick = {
                scope.launch {
                    val snapshot = vm.alarmHistoryWithCurrentState()
                    logText = snapshot
                    clipboard.setText(AnnotatedString(snapshot))
                    snackbarHostState.showSnackbar(copiedMsg)
                }
            }) {
                Text(stringResource(R.string.settings_alarm_history_copy))
            }
            OutlinedButton(onClick = {
                vm.clearAlarmHistory()
                logText = ""
            }) {
                Text(stringResource(R.string.settings_alarm_history_clear))
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text(
                    text = logText.ifBlank { stringResource(R.string.settings_alarm_history_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.fillMaxWidth())
    }
}
