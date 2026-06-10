@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.nock.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nock.android.R
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType

/**
 * The escalation-chain editor: per-stage offset, reorder, remove, add-missing
 * stages, and the last-stage repeat interval. One implementation shared by the
 * global default (Notifications settings) and per-group override (group editor)
 * so the two can't drift apart.
 *
 * Offset fields hold raw text locally (keyed on the stage type, which is unique
 * per chain and follows reorders) so the user can clear the field and type a
 * leading "-" for pre-trigger offsets. Deriving the displayed value from
 * offsetMs every keystroke snapped those away and made the field impossible to
 * clear or make negative.
 */
@Composable
fun ChainEditor(
    chain: EscalationChain,
    onChange: (EscalationChain) -> Unit,
    modifier: Modifier = Modifier,
) {
    var local by remember(chain) { mutableStateOf(chain) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                var offsetText by remember(stage.type) {
                    mutableStateOf((stage.offsetMs / 60_000L).toInt().toString())
                }
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { v ->
                        if (v.isEmpty() || v == "-" || v.toIntOrNull() != null) {
                            offsetText = v
                            v.toIntOrNull()?.let { m ->
                                val list = local.stages.toMutableList()
                                list[idx] = list[idx].copy(offsetMs = m * 60_000L)
                                local = EscalationChain(list, local.repeatIntervalMs)
                                onChange(local)
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.settings_offset_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            Text(stringResource(R.string.settings_add_stage), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        // Same raw-text treatment so the repeat field can be cleared while
        // typing; keyed on the value so an external chain swap re-initialises it.
        var repeatText by remember(local.repeatIntervalMs) {
            mutableStateOf((local.repeatIntervalMs / 60_000L).toInt().toString())
        }
        OutlinedTextField(
            value = repeatText,
            onValueChange = { v ->
                if (v.isEmpty() || v.toIntOrNull() != null) {
                    repeatText = v
                    v.toIntOrNull()?.let { m -> if (m >= 1) {
                        local = local.copy(repeatIntervalMs = m * 60_000L); onChange(local)
                    } }
                }
            },
            label = { Text(stringResource(R.string.settings_repeat_interval_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
