package ru.anisimov.keenwg.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun KeenBottomIsland(
    selected: TopLevelDestination,
    destinations: List<TopLevelDestination>,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                destinations.forEach { destination ->
                    val isSelected = destination == selected
                    val itemShape = RoundedCornerShape(24.dp)
                    Surface(
                        onClick = { onSelect(destination) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(itemShape)
                            .semantics {
                                this.selected = isSelected
                                role = Role.Tab
                                contentDescription = destination.contentDescription
                            },
                        shape = itemShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(destination.icon(), contentDescription = null, modifier = Modifier.size(21.dp))
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.OVERVIEW -> Icons.Default.Home
    TopLevelDestination.CONNECTIONS -> Icons.Default.Language
    TopLevelDestination.ROUTES -> Icons.Default.Route
    TopLevelDestination.ACCESS -> Icons.Default.Devices
    TopLevelDestination.SYSTEM -> Icons.Default.Settings
}
