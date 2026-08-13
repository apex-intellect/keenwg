package ru.anisimov.keenwg.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import ru.anisimov.keenwg.ui.theme.KeenBackground

@Composable
fun KeenAppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(KeenBackground)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x2950D5FF), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.08f),
                    radius = size.minDimension * 0.68f,
                ),
                radius = size.minDimension * 0.68f,
                center = Offset(size.width * 0.9f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x16090B0E), Color.Transparent),
                    center = Offset(size.width * 0.02f, size.height * 0.84f),
                    radius = size.minDimension * 0.52f,
                ),
                radius = size.minDimension * 0.52f,
                center = Offset(size.width * 0.02f, size.height * 0.84f),
            )
        }
        content()
    }
}
