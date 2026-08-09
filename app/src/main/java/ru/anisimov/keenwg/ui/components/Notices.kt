package ru.anisimov.keenwg.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}
