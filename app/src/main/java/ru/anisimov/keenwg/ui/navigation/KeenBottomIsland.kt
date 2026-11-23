package ru.anisimov.keenwg.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.theme.*

@Composable
internal fun KeenBottomIsland(
    selected: TopLevelDestination,
    destinations: List<TopLevelDestination>,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: KeenBackdrop? = null,
) {
    val glassLayer = rememberGraphicsLayer()
    val shape = ContinuousShape(KeenRadius.navigation)
    val tint = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(
        horizontal = KeenSpacing.navigationInset, vertical = KeenSpacing.navigationBottom,
    )) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("floating-navigation")
                .shadow(KeenGlass.shadow, shape, clip = false).clip(shape)
                .then(if (backdrop != null) Modifier.keenBackdropEffect(backdrop, glassLayer, tint) else Modifier)
                .border(KeenSize.hairline, tint.copy(alpha = KeenOpacity.glassRim), shape),
            shape = shape,
            color = if (backdrop != null) Color.Transparent else tint.copy(alpha = KeenGlass.fallbackTint),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            val largeText = LocalDensity.current.fontScale > KeenLayout.largeTextThreshold &&
                LocalConfiguration.current.screenWidthDp < KeenLayout.compactWidth
            Column(
                Modifier.fillMaxWidth().padding(KeenSpacing.navigationContent),
                verticalArrangement = Arrangement.spacedBy(KeenSpacing.tight),
            ) {
                destinations.chunked(if (largeText) 3 else 5).forEach { rowDestinations ->
                    KeenNavigationRow(selected, rowDestinations, onSelect)
                }
            }
        }
    }
}

private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.OVERVIEW -> KeenNavigationIcons.home
    TopLevelDestination.CONNECTIONS -> KeenNavigationIcons.globe
    TopLevelDestination.ROUTES -> KeenNavigationIcons.route
    TopLevelDestination.ACCESS -> KeenNavigationIcons.key
    TopLevelDestination.SYSTEM -> KeenNavigationIcons.settings
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

@Composable
private fun KeenNavigationRow(
    selected: TopLevelDestination,
    destinations: List<TopLevelDestination>,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
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
                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = KeenGlass.selectedTint) else Color.Transparent,
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
                        modifier = Modifier.padding(horizontal = KeenScale.dp2),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
