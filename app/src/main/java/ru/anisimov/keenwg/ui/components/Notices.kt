package ru.anisimov.keenwg.ui.components

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun StatusNotice(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    isError: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = KeenScale.dp14, vertical = KeenSpacing.group)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = KeenScale.dp2)) }
        }
    }
}
