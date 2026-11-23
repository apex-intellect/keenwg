package ru.anisimov.keenwg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = with(KeenPalette) { lightColorScheme(
    primary = graphite, onPrimary = ivory,
    primaryContainer = soft, onPrimaryContainer = graphite,
    secondary = warning, onSecondary = paper,
    secondaryContainer = warningSoft, onSecondaryContainer = warning,
    tertiary = success, onTertiary = paper,
    tertiaryContainer = successSoft, onTertiaryContainer = success,
    background = ivory, onBackground = graphite,
    surface = paper, onSurface = graphite,
    surfaceVariant = soft, onSurfaceVariant = muted,
    surfaceContainerLowest = paper, surfaceContainerLow = paper,
    surfaceContainer = paper, surfaceContainerHigh = paper,
    surfaceContainerHighest = soft, surfaceTint = Color.Transparent,
    outline = line, outlineVariant = line,
    error = danger, onError = paper,
    errorContainer = dangerSoft, onErrorContainer = danger,
) }

private val DarkColors = with(KeenPalette) { darkColorScheme(
    primary = nightInk, onPrimary = night,
    primaryContainer = nightSoft, onPrimaryContainer = nightInk,
    secondary = nightWarning, onSecondary = night,
    secondaryContainer = nightWarningSoft, onSecondaryContainer = nightWarning,
    tertiary = nightSuccess, onTertiary = night,
    tertiaryContainer = nightSuccessSoft, onTertiaryContainer = nightSuccess,
    background = night, onBackground = nightInk,
    surface = nightPaper, onSurface = nightInk,
    surfaceVariant = nightSoft, onSurfaceVariant = nightMuted,
    surfaceContainerLowest = nightPaper, surfaceContainerLow = nightPaper,
    surfaceContainer = nightPaper, surfaceContainerHigh = nightPaper,
    surfaceContainerHighest = nightSoft, surfaceTint = Color.Transparent,
    outline = nightLine, outlineVariant = nightLine,
    error = nightDanger, onError = night,
    errorContainer = nightDangerSoft, onErrorContainer = nightDanger,
) }

private val KeenWgShapes = Shapes(
    // Material text fields use extraSmall; keep their corners aligned with controls.
    extraSmall = RoundedCornerShape(KeenRadius.control),
    small = RoundedCornerShape(KeenRadius.control),
    medium = RoundedCornerShape(KeenRadius.control),
    large = RoundedCornerShape(KeenRadius.card),
    extraLarge = RoundedCornerShape(KeenRadius.sheet),
)

@Composable
fun KeenWgTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KeenWgTypography,
        shapes = KeenWgShapes,
        content = content,
    )
}
