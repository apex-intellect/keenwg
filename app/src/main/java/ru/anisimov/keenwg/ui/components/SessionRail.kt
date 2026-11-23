package ru.anisimov.keenwg.ui.components

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
fun SessionRail(online: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outline
        online -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .width(KeenSpacing.tight)
            .height(KeenScale.dp52)
            .background(color, RoundedCornerShape(KeenSpacing.tight))
            .clearAndSetSemantics { },
    )
}
