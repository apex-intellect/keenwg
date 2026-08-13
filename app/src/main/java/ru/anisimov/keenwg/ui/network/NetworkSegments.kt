package ru.anisimov.keenwg.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R

@Composable
fun NetworkSegmentControl(state: NetworkUiState, onSelect: (NetworkSegment) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NetworkSegment.entries.forEach { segment ->
                val selected = state.selectedSegment == segment
                Surface(
                    onClick = { onSelect(segment) },
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp).semantics {
                        this.selected = selected
                        role = Role.Tab
                    },
                    shape = RoundedCornerShape(21.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Column(
                        Modifier.padding(horizontal = 3.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            when (segment) {
                                NetworkSegment.DEVICES -> Icons.Default.Devices
                                NetworkSegment.IP_ADDRESSES -> Icons.Default.Router
                                NetworkSegment.DOMAINS -> Icons.Default.Language
                                NetworkSegment.EXPLAIN -> Icons.Default.Search
                            },
                            contentDescription = null,
                        )
                        Text(stringResource(networkSegmentLabelResource(segment)), style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                        Text("${segmentCount(segment, state)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

internal fun networkSegmentLabelResource(segment: NetworkSegment): Int = when (segment) {
    NetworkSegment.DEVICES -> R.string.rules_devices
    NetworkSegment.IP_ADDRESSES -> R.string.rules_addresses
    NetworkSegment.DOMAINS -> R.string.rules_sites
    NetworkSegment.EXPLAIN -> R.string.rules_check
}
