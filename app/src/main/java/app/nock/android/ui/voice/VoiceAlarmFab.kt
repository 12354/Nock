package app.nock.android.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R

@Composable
fun VoiceAlarmFab(
    modifier: Modifier = Modifier,
    vm: VoiceAlarmViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        when (val s = state) {
            is VoiceAlarmUiState.Success -> {
                snackbar.showSnackbar(message = s.reminderName)
                vm.reset()
            }
            is VoiceAlarmUiState.Error -> {
                snackbar.showSnackbar(message = s.message)
                vm.reset()
            }
            else -> {}
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.fillMaxWidth(),
            snackbar = { data -> Snackbar(snackbarData = data) }
        )
        StatusChip(state)
        HoldToRecordButton(
            isRecording = state is VoiceAlarmUiState.Listening,
            enabled = state !is VoiceAlarmUiState.Thinking,
            onPressStart = { vm.startListening() },
            onPressEnd = { vm.stopListening() },
            onCancel = { vm.cancel() }
        )
    }
}

@Composable
private fun StatusChip(state: VoiceAlarmUiState) {
    when (state) {
        is VoiceAlarmUiState.Listening -> {
            val text = state.partial.ifBlank { stringResource(R.string.voice_listening) }
            ChipSurface { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
        is VoiceAlarmUiState.Thinking -> {
            ChipSurface {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.voice_thinking), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        else -> { Spacer(Modifier.height(0.dp)) }
    }
}

@Composable
private fun ChipSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp))
        ) { content() }
    }
}
