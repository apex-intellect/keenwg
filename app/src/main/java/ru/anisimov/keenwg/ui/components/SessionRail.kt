package ru.anisimov.keenwg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun SessionRail(online: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outline
        online -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .width(4.dp)
            .height(52.dp)
            .background(color, RoundedCornerShape(4.dp))
            .clearAndSetSemantics { },
    )
}
