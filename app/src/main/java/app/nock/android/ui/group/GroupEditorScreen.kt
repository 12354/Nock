@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.ui.components.GroupColorChoices
import app.nock.android.ui.components.GroupIconChoices
import app.nock.android.ui.components.PauseUntilDialog
import app.nock.android.ui.components.formatPauseUntil
import app.nock.android.ui.components.groupIconFor
import app.nock.android.ui.components.stageIcon
import app.nock.android.ui.components.stageTypeLabel
import kotlinx.coroutines.launch

@Composable
fun GroupEditorScreen(
    groupId: Long,
    onDone: () -> Unit,
    vm: GroupEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(groupId) { vm.load(groupId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.id == 0L) R.string.group_editor_title_new
                            else R.string.group_editor_title_edit
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { vm.save(); onDone() }
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.id != 0L) {
                        IconButton(onClick = {
                            scope.launch { vm.delete(); onDone() }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        TextButton(onClick = {
                            scope.launch { vm.save(); onDone() }
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            AvatarHeader(state)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::updateName,
                label = { Text(stringResource(R.string.edit_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            SectionHeader(stringResource(R.string.group_editor_section_color))
            ColorPicker(state.color, vm::updateColor)

            SectionHeader(stringResource(R.string.group_editor_section_icon))
            IconPicker(state.icon, Color(state.color), vm::updateIcon)

            SectionHeader(stringResource(R.string.group_editor_section_pause))
            val ctx = LocalContext.current
            var showPauseDialog by remember { mutableStateOf(false) }
            val pauseStateText = if (state.isPaused && state.pausedUntilMs != null) {
                stringResource(
                    R.string.group_editor_pause_state_until,
                    formatPauseUntil(ctx, state.pausedUntilMs!!)
                )
            } else {
                stringResource(R.string.group_editor_pause_state_not_paused)
            }
            SettingRow(
                leadingIcon = {
                    Icon(
                        Icons.Filled.PauseCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                title = stringResource(R.string.group_editor_pause_title),
                sub = pauseStateText,
                onClick = { showPauseDialog = true },
                trailing = {
                    TextButton(onClick = { showPauseDialog = true }) {
                        Text(stringResource(if (state.isPaused) R.string.edit else R.string.pause))
                    }
                }
            )
            if (showPauseDialog) {
                PauseUntilDialog(
                    currentPauseUntilMs = state.pausedUntilMs,
                    onDismiss = { showPauseDialog = false },
                    onPick = { vm.setPauseUntil(it) },
                    onResume = { vm.setPauseUntil(null) },
                )
            }

            SectionHeader(stringResource(R.string.group_editor_section_chain))
            SettingRow(
                leadingIcon = {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = Color(state.color)
                    )
                },
                title = stringResource(R.string.group_editor_custom_chain_title),
                sub = stringResource(R.string.group_editor_custom_chain_sub),
                trailing = {
                    Switch(
                        checked = state.overrideChain != null,
                        onCheckedChange = vm::toggleCustomChain
                    )
                }
            )

            val activeChain = state.overrideChain ?: state.defaultChain
            ChainEditorCard(
                chain = activeChain,
                accent = Color(state.color),
                editable = state.overrideChain != null,
                onChange = { vm.updateChain(it) }
            )

            SettingRow(
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = null,
                        tint = Color(0xFF8AB4F8)
                    )
                },
                title = stringResource(R.string.settings_mirror_silent_telegram),
                sub = stringResource(R.string.group_editor_mirror_silent_telegram_sub),
                trailing = {
                    Switch(
                        checked = state.telegramSilentMirror,
                        onCheckedChange = vm::updateMirror
                    )
                }
            )
        }
    }
}

@Composable
private fun AvatarHeader(state: GroupEditorState) {
    val color = Color(state.color)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f))
                .border(2.dp, color.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = groupIconFor(state.icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state.name.ifBlank { stringResource(R.string.group_editor_title_new) },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = if (state.reminderCount == 1)
                stringResource(R.string.group_editor_reminders_count, state.reminderCount)
            else
                stringResource(R.string.group_editor_reminders_count_plural, state.reminderCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val selectedColor = Color(selected)
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
    ) {
        // first row: 8 colors. We use FlowRow-like layout but with fixed weighted distribution.
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupColorChoices.forEach { c ->
                val color = Color(c)
                val isSelected = c.toInt() == selected
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onSelect(c.toInt()) }
                        .then(
                            if (isSelected) Modifier.border(3.dp, onSurface, CircleShape)
                            else Modifier.border(2.dp, color.copy(alpha = 0.4f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = surface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPicker(selected: String, accent: Color, onSelect: (String) -> Unit) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val columns = 5
    val spacing = 8.dp
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        val cellSize = (maxWidth - spacing * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            GroupIconChoices.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowItems.forEach { (name, vector) ->
                        val isSelected = name == selected
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) accent.copy(alpha = 0.22f) else surface)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(16.dp))
                                    else Modifier
                                )
                                .clickable { onSelect(name) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = vector,
                                contentDescription = name,
                                tint = if (isSelected) accent else onSurfaceVar,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    leadingIcon: @Composable () -> Unit,
    title: String,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { leadingIcon() }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ChainEditorCard(
    chain: EscalationChain,
    accent: Color,
    editable: Boolean,
    onChange: (EscalationChain) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            MiniChain(chain = chain, accent = accent)
            Spacer(Modifier.height(8.dp))
            Text(
                chainOffsetSummary(chain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (editable) {
                Spacer(Modifier.height(8.dp))
                StageEditor(chain, onChange)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.group_editor_chain_summary_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniChain(chain: EscalationChain, accent: Color) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chain.stages.forEachIndexed { idx, stage ->
            Surface(
                color = accent.copy(alpha = 0.18f),
                shape = CircleShape,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = stageIcon(stage.type),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        stringResource(stageTypeLabel(stage.type)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (idx < chain.stages.lastIndex) {
                Text(
                    "→",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

private fun chainOffsetSummary(chain: EscalationChain): String {
    fun fmt(ms: Long): String {
        val sign = if (ms >= 0) "+" else "−"
        val abs = kotlin.math.abs(ms)
        val mins = (abs / 60_000L).toInt()
        return if (mins == 0) "0" else "${sign}${mins}m"
    }
    val parts = chain.stages.map { fmt(it.offsetMs) }
    val repeat = (chain.repeatIntervalMs / 60_000L).toInt()
    return parts.joinToString(" · ") + " (repeat ${repeat}m)"
}

@Composable
private fun StageEditor(chain: EscalationChain, onChange: (EscalationChain) -> Unit) {
    var local by remember(chain) { mutableStateOf(chain) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        local.stages.forEachIndexed { idx, stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = stageIcon(stage.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(stageTypeLabel(stage.type)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                val mins = (stage.offsetMs / 60_000L).toInt()
                OutlinedTextField(
                    value = mins.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { m ->
                            val list = local.stages.toMutableList()
                            list[idx] = list[idx].copy(offsetMs = m * 60_000L)
                            local = EscalationChain(list, local.repeatIntervalMs)
                            onChange(local)
                        }
                    },
                    label = { Text(stringResource(R.string.settings_offset_label)) },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
                IconButton(
                    enabled = idx > 0,
                    onClick = {
                        val list = local.stages.toMutableList()
                        val tmp = list[idx]; list[idx] = list[idx - 1]; list[idx - 1] = tmp
                        local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                    }
                ) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.move_up)) }
                IconButton(
                    enabled = idx < local.stages.lastIndex,
                    onClick = {
                        val list = local.stages.toMutableList()
                        val tmp = list[idx]; list[idx] = list[idx + 1]; list[idx + 1] = tmp
                        local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                    }
                ) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.move_down)) }
                IconButton(
                    enabled = local.stages.size > 1,
                    onClick = {
                        val list = local.stages.toMutableList().also { it.removeAt(idx) }
                        local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                    }
                ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove)) }
            }
        }
        val missing = StageType.values().toSet() - local.stages.map { it.type }.toSet()
        if (missing.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                missing.forEach { t ->
                    AssistChip(
                        onClick = {
                            val nextOff = (local.stages.lastOrNull()?.offsetMs ?: 0L) + 5 * 60_000L
                            val list = local.stages + StageConfig(t, nextOff)
                            local = EscalationChain(list, local.repeatIntervalMs); onChange(local)
                        },
                        label = { Text(stringResource(stageTypeLabel(t))) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = (local.repeatIntervalMs / 60_000L).toInt().toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { m -> if (m >= 1) {
                    local = local.copy(repeatIntervalMs = m * 60_000L); onChange(local)
                } }
            },
            label = { Text(stringResource(R.string.settings_repeat_interval_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
