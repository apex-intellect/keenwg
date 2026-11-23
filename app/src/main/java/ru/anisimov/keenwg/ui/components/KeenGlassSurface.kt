package ru.anisimov.keenwg.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius
import ru.anisimov.keenwg.ui.theme.KeenSize

@Composable
fun KeenGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ContinuousShape(KeenRadius.card),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(KeenSize.hairline, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = KeenSize.zero,
        shadowElevation = KeenSize.zero,
    ) {
        Column(content = content)
    }
}
