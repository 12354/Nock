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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun HoldToRecordButton(
    isRecording: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onCancel: () -> Unit,
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
            .pointerInput(enabled, permissionGranted) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        if (!permissionGranted) {
                            pendingStartAfterGrant = true
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        onPressStart()
                        val released = tryAwaitRelease()
                        if (released) onPressEnd() else onCancel()
                    }
                )
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
