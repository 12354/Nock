@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.nock.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nock.android.R
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Timeline-style escalation-chain editor. One implementation shared by the
 * global default (Notifications settings) and per-group override (group
 * editor) so the two can't drift apart.
 *
 * Stage order is derived from the offsets — the escalation engine assumes
 * ascending offsets (see EscalationChain.firstPendingStage), so the editor
 * keeps the list sorted instead of offering manual reordering that could
 * contradict the times. Offsets are nudged with -/+ steppers (hold to repeat)
 * or typed exactly by tapping the "N min before" label.
 */
@Composable
fun ChainEditor(
    chain: EscalationChain,
    onChange: (EscalationChain) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    var local by remember(chain) { mutableStateOf(chain) }
    fun update(stages: List<StageConfig> = local.stages, repeatMs: Long = local.repeatIntervalMs) {
        local = EscalationChain(stages.sortedBy { it.offsetMs }, repeatMs)
        onChange(local)
    }

    var offsetDialogFor by remember { mutableStateOf<StageType?>(null) }
    var repeatDialogOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.animateContentSize()) {
        local.stages.forEachIndexed { idx, stage ->
            key(stage.type) {
                StageRow(
                    stage = stage,
                    isFirst = idx == 0,
                    isLast = idx == local.stages.lastIndex,
                    accent = accent,
                    removable = local.stages.size > 1,
                    onOffsetChange = { newOffsetMs ->
                        update(local.stages.map {
                            if (it.type == stage.type) it.copy(offsetMs = newOffsetMs) else it
                        })
                    },
                    onTapOffset = { offsetDialogFor = stage.type },
                    onRemove = { update(local.stages.filterNot { it.type == stage.type }) },
                )
            }
        }

        val missing = StageType.values().toList() - local.stages.map { it.type }.toSet()
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_add_stage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                missing.forEach { t ->
                    AssistChip(
                        onClick = {
                            update(local.stages + StageConfig(t, defaultOffsetFor(t, local.stages)))
                        },
                        leadingIcon = {
                            Icon(stageIcon(t), contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(stringResource(stageTypeLabel(t))) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            val repeatMin = (local.repeatIntervalMs / 60_000L).toInt()
            StepButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.chain_step_earlier),
                enabled = repeatMin > 1,
            ) { if ((local.repeatIntervalMs / 60_000L) > 1) update(repeatMs = local.repeatIntervalMs - 60_000L) }
            Text(
                stringResource(R.string.chain_repeat_every, repeatMin),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { repeatDialogOpen = true }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            StepButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chain_step_later),
            ) { update(repeatMs = local.repeatIntervalMs + 60_000L) }
        }
    }

    offsetDialogFor?.let { type ->
        val stage = local.stages.firstOrNull { it.type == type }
        if (stage == null) {
            offsetDialogFor = null
        } else {
            MinutesDialog(
                title = stringResource(stageTypeLabel(type)),
                initialMinutes = abs(stage.offsetMs / 60_000L).toInt(),
                initialBefore = stage.offsetMs <= 0,
                showDirection = true,
                minMinutes = 0,
                onDismiss = { offsetDialogFor = null },
                onConfirm = { minutes, before ->
                    val sign = if (before) -1L else 1L
                    update(local.stages.map {
                        if (it.type == type) it.copy(offsetMs = sign * minutes * 60_000L) else it
                    })
                    offsetDialogFor = null
                }
            )
        }
    }
    if (repeatDialogOpen) {
        MinutesDialog(
            title = stringResource(R.string.chain_repeat_dialog_title),
            initialMinutes = (local.repeatIntervalMs / 60_000L).toInt(),
            initialBefore = false,
            showDirection = false,
            minMinutes = 1,
            onDismiss = { repeatDialogOpen = false },
            onConfirm = { minutes, _ ->
                update(repeatMs = minutes * 60_000L)
                repeatDialogOpen = false
            }
        )
    }
}

@Composable
private fun StageRow(
    stage: StageConfig,
    isFirst: Boolean,
    isLast: Boolean,
    accent: Color,
    removable: Boolean,
    onOffsetChange: (Long) -> Unit,
    onTapOffset: () -> Unit,
    onRemove: () -> Unit,
) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier.width(36.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isFirst) Color.Transparent else rail)
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isLast) accent else accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = stageIcon(stage.type),
                    contentDescription = null,
                    tint = if (isLast) Color.Black else accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Box(
                Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else rail)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(stageTypeLabel(stage.type)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRemove,
                    enabled = removable,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.chain_step_earlier),
                ) { onOffsetChange(stage.offsetMs - 60_000L) }
                Text(
                    offsetLabel(stage.offsetMs),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTapOffset)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
                StepButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chain_step_later),
                ) { onOffsetChange(stage.offsetMs + 60_000L) }
            }
        }
    }
}

@Composable
private fun offsetLabel(offsetMs: Long): String {
    val minutes = (offsetMs / 60_000L).toInt()
    return when {
        minutes < 0 -> stringResource(R.string.chain_min_before, -minutes)
        minutes == 0 -> stringResource(R.string.chain_at_time)
        else -> stringResource(R.string.chain_min_after, minutes)
    }
}

/**
 * -/+ button that steps once on tap and auto-repeats while held. A hold sets
 * [repeating] so the release's regular onClick doesn't add one extra step.
 */
@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onStep: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentOnStep by rememberUpdatedState(onStep)
    var repeating by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(450)
            repeating = true
            while (true) {
                currentOnStep()
                delay(70)
            }
        }
    }
    IconButton(
        onClick = { if (repeating) repeating = false else currentOnStep() },
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/**
 * New stages slot into the timeline by loudness: between the quieter and
 * louder neighbours already present (mid-gap), or 5 min beyond the edge when
 * there is no neighbour on that side.
 */
private fun defaultOffsetFor(type: StageType, stages: List<StageConfig>): Long {
    val sorted = stages.sortedBy { it.offsetMs }
    val quieter = sorted.lastOrNull { it.type < type }
    val louder = sorted.firstOrNull { it.type > type }
    val step = 5 * 60_000L
    return when {
        quieter == null && louder == null -> 0L
        quieter == null -> louder!!.offsetMs - step
        louder == null -> quieter.offsetMs + step
        else -> ((quieter.offsetMs + louder.offsetMs) / 2 / 60_000L) * 60_000L
    }
}

@Composable
private fun MinutesDialog(
    title: String,
    initialMinutes: Int,
    initialBefore: Boolean,
    showDirection: Boolean,
    minMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, before: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    var before by remember { mutableStateOf(initialBefore) }
    val parsed = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) text = v },
                    label = { Text(stringResource(R.string.chain_minutes_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (showDirection) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = before,
                            onClick = { before = true },
                            label = { Text(stringResource(R.string.chain_before)) }
                        )
                        FilterChip(
                            selected = !before,
                            onClick = { before = false },
                            label = { Text(stringResource(R.string.chain_after)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed >= minMinutes,
                onClick = { onConfirm(parsed!!, before) }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
