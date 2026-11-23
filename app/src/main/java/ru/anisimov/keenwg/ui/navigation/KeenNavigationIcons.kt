package ru.anisimov.keenwg.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import ru.anisimov.keenwg.ui.theme.KeenSpacing

/** Original outline paths shared with the approved HTML prototype. */
internal object KeenNavigationIcons {
    val home = icon("Overview", "M3 10 L12 3 L21 10 L21 20 L3 20 Z M9 20 L9 13 L15 13 L15 20")
    val globe = icon("Servers", "M21 12 A9 9 0 1 1 3 12 A9 9 0 0 1 21 12 M3 12 L21 12 M12 3 C17 8 17 16 12 21 C7 16 7 8 12 3")
    val route = icon("Rules", "M5 3 L5 9 M5 6 L15 6 A4 4 0 0 1 15 14 L9 14 A4 4 0 0 0 9 22 L18 22")
    val key = icon("Access", "M14 8 A5 5 0 1 1 10 3 A5 5 0 0 1 14 8 M13 11 L21 19 L21 22 L17 22 L17 19 L14 19 L11 16")
    val settings = icon("System", "M12 8 A4 4 0 1 0 12 16 A4 4 0 0 0 12 8 M12 2 L12 5 M12 19 L12 22 M2 12 L5 12 M19 12 L22 12 M5 5 L7 7 M17 17 L19 19 M5 19 L7 17 M17 7 L19 5")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = KeenSpacing.section, defaultHeight = KeenSpacing.section,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}
