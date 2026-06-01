package app.nock.android.ui.voice

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.ui.LocaleHelper
import app.nock.android.ui.theme.NockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class VoiceAlarmActivity : ComponentActivity() {
    // Apply the user-selected app language. ComponentActivity (unlike
    // AppCompatActivity) doesn't pick up AppCompatDelegate's per-app locale, so
    // wrap the base context the same way MainActivity/AlarmActivity do.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NockTheme {
                VoiceAlarmScreen(onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun VoiceAlarmScreen(
    onFinish: () -> Unit,
    vm: VoiceAlarmViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        when (state) {
            is VoiceAlarmUiState.Success -> {
                delay(1200)
                onFinish()
            }
            is VoiceAlarmUiState.Error -> {
                delay(2200)
                onFinish()
            }
            else -> {}
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusText(state)
                Spacer(Modifier.height(8.dp))
                HoldToRecordButton(
                    isRecording = state is VoiceAlarmUiState.Listening,
                    enabled = state !is VoiceAlarmUiState.Thinking && state !is VoiceAlarmUiState.Success,
                    onPressStart = { vm.startListening() },
                    onPressEnd = { vm.stopListening() },
                    size = 120.dp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.voice_hold_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatusText(state: VoiceAlarmUiState) {
    when (state) {
        is VoiceAlarmUiState.Idle ->
            Text(
                stringResource(R.string.voice_idle_prompt),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        is VoiceAlarmUiState.Listening -> {
            val text = state.partial.ifBlank { stringResource(R.string.voice_listening) }
            Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        }
        is VoiceAlarmUiState.Thinking -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                Text("“${state.transcript}”", style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center)
                Text(stringResource(R.string.voice_thinking), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        is VoiceAlarmUiState.Success ->
            Text(
                state.message,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        is VoiceAlarmUiState.Error ->
            Text(
                stringResource(R.string.voice_error_prefix, state.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
    }
}
