package ru.anisimov.keenwg.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.theme.*

@Composable
fun NetworkSegmentControl(state: NetworkUiState, onSelect: (NetworkSegment) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousShape(KeenRadius.control),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.fillMaxWidth().padding(KeenScale.dp5), horizontalArrangement = Arrangement.spacedBy(KeenSpacing.tight)) {
            NetworkSegment.entries.forEach { segment ->
                val selected = state.selectedSegment == segment
                Surface(
                    onClick = { onSelect(segment) },
                    modifier = Modifier.weight(1f).heightIn(min = KeenSize.touch).semantics {
                        this.selected = selected
                        role = Role.Tab
                    },
                    shape = ContinuousShape(KeenRadius.control),
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Column(
                        Modifier.padding(horizontal = KeenScale.dp3, vertical = KeenScale.dp7),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(networkSegmentLabelResource(segment)), style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
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
