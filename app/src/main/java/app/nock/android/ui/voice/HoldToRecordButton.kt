package app.nock.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// Hold-to-record pointer handling.
//
// detectTapGestures cancels the gesture as soon as the finger drifts past the
// view-config touch-slop. Speakers naturally shift their finger during a 2s
// pause — and that cancellation propagated as a session-stop, which ended the
// recognizer, ran DeepSeek on the partial transcript, briefly disabled the
// button (state → Thinking), and then re-armed the gesture for a fresh press.
// The next spoken word started a brand-new session with an empty buffer, so
// it "replaced" the previous text instead of extending it.
//
// We replace detectTapGestures with a raw pointer-input that:
//   1. Uses a stable Unit key so state-driven recompositions can never tear
//      down an in-flight hold.
//   2. Tracks the specific pointer that started the gesture by id and only
//      releases when THAT pointer reports !pressed. Drift, drag, and even
//      finger leaving the bounds are all ignored — only a true UP ends the
//      session.
//   3. Consumes the pointer change so parent gesture containers (Scaffold,
//      LazyColumn) can never steal the pointer mid-hold.
//   4. Reads enabled / permission / callback state via rememberUpdatedState
//      so the stable pointerInput can still see the latest values.
@Composable
fun HoldToRecordButton(
    isRecording: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val ctx = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStartAfterGrant by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted && pendingStartAfterGrant) onPressStart()
        pendingStartAfterGrant = false
    }

    val enabledLatest = rememberUpdatedState(enabled)
    val permissionGrantedLatest = rememberUpdatedState(permissionGranted)
    val onPressStartLatest = rememberUpdatedState(onPressStart)
    val onPressEndLatest = rememberUpdatedState(onPressEnd)

    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic-pulse-scale"
    )
    val idleScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(120),
        label = "mic-idle-scale"
    )

    val bg = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val fg = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .size(size)
            .scale(if (isRecording) pulseScale else idleScale)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledLatest.value) return@awaitEachGesture
                    if (!permissionGrantedLatest.value) {
                        pendingStartAfterGrant = true
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }

                    onPressStartLatest.value()
                    val pointerId = down.id
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: continue
                            if (!change.pressed) {
                                // True UP for our pointer — end of hold.
                                change.consume()
                                break
                            }
                            // Drift / drag is intentionally ignored. Consume so
                            // parents can't steal the pointer mid-hold.
                            change.consume()
                        }
                    } finally {
                        // Always signal end so the session resolves even if the
                        // composable is disposed mid-hold (CancellationException).
                        onPressEndLatest.value()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(size * 0.45f)
        )
    }
}
