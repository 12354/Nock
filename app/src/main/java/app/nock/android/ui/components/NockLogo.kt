package app.nock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * "n · · k" serif wordmark with two apricot dots — the Nock app logo,
 * rendered inline (no tile) so the M3 dark surface acts as the ink background.
 */
@Composable
fun NockLogo(
    modifier: Modifier = Modifier,
    size: Int = 22,
    dotColor: Color = Color(0xFFFFB070),
    foreground: Color = Color(0xFFF4EFE6),
) {
    val sizeSp: TextUnit = size.sp
    val dotDiameter = (size * 0.26f).dp
    val dotGap = (size * 0.085f).dp
    val dotsSideMargin = (size * 0.14f).dp
    val letterSpacing = (-size * 0.01f).sp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "n",
            color = foreground,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = sizeSp,
            letterSpacing = letterSpacing,
        )
        Row(
            modifier = Modifier.padding(horizontal = dotsSideMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(dotDiameter)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Box(Modifier.size(dotGap))
            Box(
                Modifier
                    .size(dotDiameter)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = "k",
            color = foreground,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = sizeSp,
            letterSpacing = letterSpacing,
        )
    }
}
