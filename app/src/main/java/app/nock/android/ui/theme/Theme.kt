package app.nock.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The Nock brand accent — the apricot of the logo dots. Exposed for the few
 * places that need it outside a composable (everything else should read
 * MaterialTheme.colorScheme.primary, which is seeded from this).
 */
val NockApricot = Color(0xFFFFB070)

// M3 dark scheme seeded from the apricot brand color instead of the stock
// baseline purple, so the whole app carries the logo's warmth. Tones follow the
// standard M3 dark mapping (primary ≈ tone 80, containers ≈ tone 30, warm
// neutrals for the surface stack). Dark mode only, per the design brief.
private val NockDark = darkColorScheme(
    primary = NockApricot,
    onPrimary = Color(0xFF4C2700),
    primaryContainer = Color(0xFF6C3A06),
    onPrimaryContainer = Color(0xFFFFDCC2),
    inversePrimary = Color(0xFF8A5120),
    secondary = Color(0xFFE3C0A5),
    onSecondary = Color(0xFF422C18),
    secondaryContainer = Color(0xFF5B422C),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFC3CC9A),
    onTertiary = Color(0xFF2C3410),
    tertiaryContainer = Color(0xFF424B24),
    onTertiaryContainer = Color(0xFFDFE8B4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF17120D),
    onBackground = Color(0xFFECE0D6),
    surface = Color(0xFF17120D),
    onSurface = Color(0xFFECE0D6),
    inverseSurface = Color(0xFFECE0D6),
    inverseOnSurface = Color(0xFF362F29),
    surfaceVariant = Color(0xFF50443A),
    onSurfaceVariant = Color(0xFFD5C3B5),
    outline = Color(0xFF9D8E81),
    outlineVariant = Color(0xFF50443A),
    surfaceContainerLowest = Color(0xFF110C08),
    surfaceContainerLow = Color(0xFF1F1A14),
    surfaceContainer = Color(0xFF231E18),
    surfaceContainerHigh = Color(0xFF2E2821),
    surfaceContainerHighest = Color(0xFF39322B),
)

// Serif display/headline styles echo the serif wordmark; body and label text
// stays on the default sans for legibility at small sizes.
private val NockTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif),
        displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif),
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun NockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NockDark,
        typography = NockTypography,
        content = content,
    )
}
