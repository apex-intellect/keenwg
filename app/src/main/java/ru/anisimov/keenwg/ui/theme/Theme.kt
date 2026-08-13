package ru.anisimov.keenwg.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val KeenLightColors = lightColorScheme(
    primary = KeenPrimary,
    onPrimary = Color.White,
    primaryContainer = KeenPrimaryContainer,
    onPrimaryContainer = Color(0xFF0A316E),
    secondary = KeenCyan,
    onSecondary = KeenText,
    secondaryContainer = KeenWarningContainer,
    onSecondaryContainer = Color(0xFF4C3400),
    tertiary = KeenSuccess,
    onTertiary = Color.White,
    tertiaryContainer = KeenSuccessContainer,
    onTertiaryContainer = Color(0xFF07523D),
    background = Color.Transparent,
    onBackground = KeenText,
    surface = KeenSurface,
    onSurface = KeenText,
    surfaceVariant = KeenSurfaceElevated,
    onSurfaceVariant = KeenTextSecondary,
    surfaceContainer = KeenSurface,
    surfaceContainerHigh = KeenSurfaceElevated,
    surfaceContainerHighest = KeenSurfaceElevated,
    outline = KeenOutline,
    outlineVariant = Color(0xFFE4E6E9),
    error = KeenError,
    onError = Color.White,
    errorContainer = KeenErrorContainer,
    onErrorContainer = Color(0xFF781D25),
    scrim = Color(0x99000000),
)

private val KeenWgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KeenWgTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = KeenLightColors,
        typography = KeenWgTypography,
        shapes = KeenWgShapes,
        content = content,
    )
}
