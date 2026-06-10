package app.nock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nock.android.domain.model.EscalationChain

/**
 * Horizontal rail of an escalation chain's stages: done stages get a check,
 * the live stage is a filled accent circle with its name, upcoming stages are
 * outlined. Shared by the Today "active now" card and the alarm takeover.
 */
@Composable
fun StageProgress(
    chain: EscalationChain,
    currentIndex: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val outline = MaterialTheme.colorScheme.outlineVariant
            val onSurfVar = MaterialTheme.colorScheme.onSurfaceVariant
            chain.stages.forEachIndexed { i, stage ->
                val done = i < currentIndex
                val live = i == currentIndex
                val tint = when {
                    done -> onSurfVar
                    live -> accent
                    else -> outline
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (live) Modifier else Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (live) 28.dp else 22.dp)
                            .clip(CircleShape)
                            .then(
                                if (live) Modifier.background(accent)
                                else Modifier.border(1.5.dp, tint, CircleShape)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (done) Icons.Filled.Check else stageIcon(stage.type),
                            contentDescription = null,
                            tint = if (live) Color.Black else tint,
                            modifier = Modifier.size(if (live) 16.dp else 12.dp)
                        )
                    }
                    if (live) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(stageTypeLabel(stage.type)),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (i < chain.stages.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 6.dp)
                            .background(
                                if (done) onSurfVar else outline,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}
