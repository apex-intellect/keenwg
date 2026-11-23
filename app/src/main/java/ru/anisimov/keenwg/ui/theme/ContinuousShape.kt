package ru.anisimov.keenwg.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Continuous corners: two tangent cubic segments per corner, avoiding the
 * abrupt straight-to-circular transition of a regular rounded rectangle.
 */
data class ContinuousShape(val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }.coerceIn(0f, minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            cubicTo(w - r * .6f, 0f, w - r * .4f, r * .06f, w - r * .23f, r * .23f)
            cubicTo(w - r * .06f, r * .4f, w, r * .6f, w, r)
            lineTo(w, h - r)
            cubicTo(w, h - r * .6f, w - r * .06f, h - r * .4f, w - r * .23f, h - r * .23f)
            cubicTo(w - r * .4f, h - r * .06f, w - r * .6f, h, w - r, h)
            lineTo(r, h)
            cubicTo(r * .6f, h, r * .4f, h - r * .06f, r * .23f, h - r * .23f)
            cubicTo(r * .06f, h - r * .4f, 0f, h - r * .6f, 0f, h - r)
            lineTo(0f, r)
            cubicTo(0f, r * .6f, r * .06f, r * .4f, r * .23f, r * .23f)
            cubicTo(r * .4f, r * .06f, r * .6f, 0f, r, 0f)
            close()
        })
    }
}
