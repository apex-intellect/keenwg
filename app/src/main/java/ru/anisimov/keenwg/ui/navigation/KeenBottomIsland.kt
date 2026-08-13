package ru.anisimov.keenwg.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.theme.KeenNavigationBlack

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
            shape = RoundedCornerShape(28.dp),
            color = KeenNavigationBlack,
            contentColor = Color.White,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                destinations.forEach { destination ->
                    val isSelected = destination == selected
                    val itemShape = RoundedCornerShape(22.dp)
                    val label = stringResource(destination.labelResource())
                    val description = stringResource(destination.descriptionResource())
                    Surface(
                        onClick = { onSelect(destination) },
                        modifier = Modifier
                            .weight(1f)
                            .height(62.dp)
                            .semantics { contentDescription = description },
                        shape = itemShape,
                        color = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                        contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.58f),
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Icon(destination.icon(), contentDescription = null)
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                            if (isSelected) {
                                Box(
                                    Modifier
                                        .padding(top = 3.dp)
                                        .height(2.dp)
                                        .fillMaxWidth(0.26f)
                                        .then(Modifier),
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {}
                                }
                            }
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
