package ru.anisimov.keenwg.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.theme.*

@Composable
fun KeenBottomIsland(
    selected: TopLevelDestination,
    destinations: List<TopLevelDestination>,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(
        horizontal = KeenSpacing.navigationInset, vertical = KeenSpacing.navigationBottom,
    )) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousShape(KeenRadius.navigation),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KeenOpacity.glass),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(KeenSize.hairline, MaterialTheme.colorScheme.surface.copy(alpha = KeenOpacity.glassRim)),
            shadowElevation = KeenSize.navigationShadow,
        ) {
            Row(Modifier.fillMaxWidth().padding(KeenSpacing.navigationContent)) {
                destinations.forEach { destination ->
                    val isSelected = destination == selected
                    val description = stringResource(destination.descriptionResource())
                    Surface(
                        onClick = { onSelect(destination) },
                        modifier = Modifier.weight(1f).heightIn(min = KeenSize.navigationItem)
                            .semantics {
                                contentDescription = description
                                this.selected = isSelected
                                role = Role.Tab
                            },
                        shape = ContinuousShape(KeenRadius.navigationItem),
                        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = KeenSpacing.tight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(KeenSpacing.tight, Alignment.CenterVertically),
                        ) {
                            Icon(destination.icon(), contentDescription = null, modifier = Modifier.size(KeenSize.icon))
                            Text(
                                stringResource(destination.labelResource()),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.OVERVIEW -> Icons.Outlined.Home
    TopLevelDestination.CONNECTIONS -> Icons.Outlined.Language
    TopLevelDestination.ROUTES -> Icons.Outlined.Route
    TopLevelDestination.ACCESS -> Icons.Outlined.Key
    TopLevelDestination.SYSTEM -> Icons.Outlined.Settings
}

private fun TopLevelDestination.labelResource(): Int = when (this) {
    TopLevelDestination.OVERVIEW -> R.string.nav_overview
    TopLevelDestination.CONNECTIONS -> R.string.nav_connections
    TopLevelDestination.ROUTES -> R.string.nav_routes
    TopLevelDestination.ACCESS -> R.string.nav_access
    TopLevelDestination.SYSTEM -> R.string.nav_system
}

private fun TopLevelDestination.descriptionResource(): Int = when (this) {
    TopLevelDestination.OVERVIEW -> R.string.nav_overview_description
    TopLevelDestination.CONNECTIONS -> R.string.nav_connections_description
    TopLevelDestination.ROUTES -> R.string.nav_routes_description
    TopLevelDestination.ACCESS -> R.string.nav_access_description
    TopLevelDestination.SYSTEM -> R.string.nav_system_description
}
