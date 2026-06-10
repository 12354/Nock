package app.nock.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nock.android.R
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The single floating action button: tap to open the reminder editor, press and
 * hold to record a voice reminder. Replaces the old stacked pair (voice mic FAB
 * above an Add FAB) on the Today and Reminders screens.
 *
 * Voice results (success/error/background "added" confirmations) are reported
 * through [snackbarHostState] so they appear in the screen's normal snackbar
 * slot rather than floating next to the button.
 *
 * Hold tracking follows HoldToRecordButton's rules: once recording has started,
 * only a true UP of the original pointer ends it — drift and drag are ignored
 * and consumed so list scrolling can't steal the gesture mid-sentence.
 */
@Composable
fun AddReminderFab(
    onAdd: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    vm: VoiceAlarmViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    // showSnackbar suspends until the snackbar leaves; reset only after, since
    // resetting flips the state key and would cancel this effect mid-show.
    LaunchedEffect(state) {
        when (val s = state) {
            is VoiceAlarmUiState.Success -> {
                snackbarHostState.showSnackbar(message = s.message)
                vm.reset()
            }
            is VoiceAlarmUiState.Error -> {
                snackbarHostState.showSnackbar(message = s.message)
                vm.reset()
            }
            else -> {}
        }
    }
    // Background processing finishes after the capture flow has gone idle, so
    // the "added X at <time> <date>" confirmation arrives on its own channel.
    LaunchedEffect(Unit) {
        vm.toasts.collect { message -> snackbarHostState.showSnackbar(message = message) }
    }

    val ctx = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    val recording = state is VoiceAlarmUiState.Listening
    val thinking = state is VoiceAlarmUiState.Thinking
    val enabled = !thinking

    val enabledLatest = rememberUpdatedState(enabled)
    val permissionGrantedLatest = rememberUpdatedState(permissionGranted)
    val onAddLatest = rememberUpdatedState(onAdd)
    val startLatest = rememberUpdatedState({ vm.startListening() })
    val stopLatest = rememberUpdatedState({ vm.stopListening() })
    val haptic = LocalHapticFeedback.current

    val pulse = rememberInfiniteTransition(label = "fab-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab-pulse-scale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (recording) {
            val partial = (state as VoiceAlarmUiState.Listening).partial
            ChipSurface {
                Text(
                    partial.ifBlank { stringResource(R.string.voice_listening) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
            }
        }
        val addHoldHint = stringResource(R.string.fab_add_hold_hint)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                recording -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = when {
                recording -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            },
            shadowElevation = 6.dp,
            modifier = Modifier
                .scale(if (recording) pulseScale else 1f)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = addHoldHint
                    onClick { onAddLatest.value(); true }
                }
                // Stable Unit key: state-driven recompositions must never tear
                // down an in-flight hold (see HoldToRecordButton).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabledLatest.value) return@awaitEachGesture
                        down.consume()
                        val pointerId = down.id
                        // Released before the long-press timeout → it's a tap.
                        val releasedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitRelease(pointerId)
                        }
                        if (releasedEarly != null) {
                            onAddLatest.value()
                            return@awaitEachGesture
                        }
                        // Held past the timeout → voice capture.
                        if (!permissionGrantedLatest.value) {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                            awaitRelease(pointerId)
                            return@awaitEachGesture
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        startLatest.value.invoke()
                        try {
                            awaitRelease(pointerId)
                        } finally {
                            // Always signal end so the session resolves even if
                            // the composable is disposed mid-hold.
                            stopLatest.value.invoke()
                        }
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    thinking -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    recording -> Icon(Icons.Filled.Mic, contentDescription = null)
                    else -> Icon(Icons.Filled.Add, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when {
                            thinking -> R.string.voice_thinking
                            recording -> R.string.voice_listening
                            else -> R.string.add
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Waits until the pointer that started the gesture reports !pressed, consuming
 * every change so parent gesture containers can't steal the pointer mid-hold.
 * Drift and drag are intentionally ignored.
 */
private suspend fun AwaitPointerEventScope.awaitRelease(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        change.consume()
        if (!change.pressed) return
    }
}

@Composable
private fun ChipSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp))
        ) { content() }
    }
}
