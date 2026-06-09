package app.nock.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Undo snackbar with a thin countdown bar that drains over [durationMs], so the
 * user can see how long the undo window stays open. Shared by the Today "marked
 * done" undo and the reminder-detail "deleted" undo.
 */
@Composable
fun UndoSnackbar(data: SnackbarData, durationMs: Long) {
    val progress = remember(data) { Animatable(1f) }
    LaunchedEffect(data) {
        progress.animateTo(0f, tween(durationMs.toInt(), easing = LinearEasing))
    }
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = data.visuals.actionLabel?.let { label ->
            {
                // Snackbar renders on the inverted (light) surface, so the default
                // TextButton color (primary = light purple) is barely legible here.
                // inversePrimary is the M3 token for snackbar actions and reads
                // clearly on the light surface.
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary
                    )
                ) {
                    Text(label)
                }
            }
        }
    ) {
        Column {
            Text(data.visuals.message)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        }
    }
}
