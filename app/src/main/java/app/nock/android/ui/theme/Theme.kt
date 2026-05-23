package app.nock.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFB59EFF),
    secondary = Color(0xFF7C5CFF),
    tertiary = Color(0xFFEFB8C8)
)
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)

@Composable
fun NockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        canDynamic && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        canDynamic && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
