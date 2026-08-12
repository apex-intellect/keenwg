package ru.anisimov.keenwg.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val KeenDarkColors = darkColorScheme(
    primary = KeenPrimary,
    onPrimary = Color(0xFF002D3A),
    primaryContainer = KeenPrimaryContainer,
    onPrimaryContainer = Color(0xFFD9F4FC),
    secondary = KeenWarning,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = KeenWarningContainer,
    onSecondaryContainer = Color(0xFFFFE7AD),
    tertiary = KeenSuccess,
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = KeenSuccessContainer,
    onTertiaryContainer = Color(0xFFB8F4E7),
    background = KeenBackground,
    onBackground = KeenText,
    surface = KeenSurface,
    onSurface = KeenText,
    surfaceVariant = KeenSurfaceElevated,
    onSurfaceVariant = KeenTextSecondary,
    surfaceContainer = KeenSurface,
    surfaceContainerHigh = KeenNavigation,
    surfaceContainerHighest = KeenSurfaceElevated,
    outline = KeenOutline,
    outlineVariant = Color(0xFF203040),
    error = KeenError,
    onError = Color(0xFF4A0005),
    errorContainer = KeenErrorContainer,
    onErrorContainer = Color(0xFFFFDAD8),
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
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = KeenDarkColors,
        typography = KeenWgTypography,
        shapes = KeenWgShapes,
        content = content,
    )
}
