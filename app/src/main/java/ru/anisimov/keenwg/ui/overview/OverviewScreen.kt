package ru.anisimov.keenwg.ui.overview

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Warning
import ru.anisimov.keenwg.ui.components.KeenButton as Button
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.anisimov.keenwg.ui.components.KeenOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination
import ru.anisimov.keenwg.ui.components.KeenGlassSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    state: OverviewState,
    onRefresh: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onOpenSystem: () -> Unit,
    onSetup: () -> Unit,
    onOpenDestination: (TopLevelDestination) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.nav_overview))
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.ui_overviewscreen_ed96001620))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KeenSpacing.screen, vertical = KeenSpacing.group),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.item),
        ) {
            if (state.showProfileSelector) {
                ProfileSelector(state, onSelectProfile)
            } else {
                Text(
                    state.selectedProfileName ?: stringResource(R.string.home_router_not_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RouterHealthCard(state)

            state.activeXkeenNode?.let { node ->
                InfoCard(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.home_active_vpn_server),
                    body = node,
                )
            }

            Text(stringResource(R.string.home_sections), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            KeenGlassSurface(Modifier.fillMaxWidth()) {
                ModuleCard("connections.", Icons.Outlined.Language, state) { onOpenDestination(TopLevelDestination.CONNECTIONS) }
                HorizontalDivider(Modifier.padding(horizontal = KeenSpacing.card))
                ModuleCard("routes.", Icons.Outlined.Route, state) { onOpenDestination(TopLevelDestination.ROUTES) }
                HorizontalDivider(Modifier.padding(horizontal = KeenSpacing.card))
                ModuleCard("access.", Icons.Outlined.Devices, state) { onOpenDestination(TopLevelDestination.ACCESS) }
            }

            if (state.health == OverviewHealth.SETUP_REQUIRED || state.health == OverviewHealth.DEGRADED || state.health == OverviewHealth.LOCKED) {
                OutlinedButton(
                    onClick = if (state.health == OverviewHealth.LOCKED) onOpenSystem else onSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52),
                ) {
                    Text(if (state.health == OverviewHealth.LOCKED) stringResource(R.string.ui_overviewscreen_e1ea72a240) else stringResource(R.string.ui_overviewscreen_50a765b16f))
                }
            }
            Spacer(Modifier.height(KeenSpacing.inline))
        }
    }
}

@Composable
private fun ProfileSelector(state: OverviewState, onSelectProfile: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp64),
            shape = ContinuousShape(KeenRadius.card),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = KeenSpacing.card, vertical = KeenSpacing.group),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            ) {
                Icon(Icons.Outlined.Router, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_overviewscreen_0925f2d263), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.selectedProfileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.ui_overviewscreen_090e918376), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.profiles.forEach { profile: RouterProfile ->
                DropdownMenuItem(
                    text = { Text(profile.displayName) },
                    leadingIcon = { if (profile.id == state.selectedProfileId) Icon(Icons.Outlined.CheckCircle, null) },
                    onClick = {
                        expanded = false
                        onSelectProfile(profile.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun RouterHealthCard(state: OverviewState) {
    val copy = overviewHealthCopy(state.health)
    val presentation = when (state.health) {
        OverviewHealth.LOADING -> HealthPresentation(stringResource(copy.titleResource), stringResource(copy.bodyResource), Icons.Outlined.Router)
        OverviewHealth.HEALTHY -> HealthPresentation(stringResource(copy.titleResource), stringResource(copy.bodyResource), Icons.Outlined.CheckCircle)
        OverviewHealth.DEGRADED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Outlined.Warning)
        OverviewHealth.SETUP_REQUIRED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Outlined.Warning)
        OverviewHealth.LOCKED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Outlined.Lock)
    }
    Card(
        shape = ContinuousShape(KeenRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(KeenSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.item),
        ) {
            if (state.loading) CircularProgressIndicator(modifier = Modifier.size(KeenScale.dp32), strokeWidth = KeenScale.dp3)
            else Icon(presentation.icon, contentDescription = null, tint = healthColor(state.health), modifier = Modifier.size(KeenScale.dp32))
            Column(verticalArrangement = Arrangement.spacedBy(KeenSpacing.inline)) {
                Text(presentation.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(presentation.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModuleCard(prefix: String, icon: ImageVector, state: OverviewState, onClick: () -> Unit) {
    val capabilities = state.capabilities?.capabilities.orEmpty().filter { it.id.startsWith(prefix) }
    val available = capabilities.filter { it.available }
    val writable = available.any { it.access == CapabilityAccess.WRITE }
    val detail = when {
        available.isEmpty() -> stringResource(R.string.home_not_configured)
        writable -> stringResource(R.string.home_management_available)
        else -> stringResource(R.string.home_view_available)
    }
    Surface(onClick = onClick, enabled = available.isNotEmpty(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = KeenScale.dp80).padding(KeenSpacing.card),
            horizontalArrangement = Arrangement.spacedBy(KeenSpacing.item),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KeenSpacing.tight)) {
                Text(stringResource(overviewModuleTitle(prefix)), style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousShape(KeenRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = KeenSpacing.card, vertical = KeenSpacing.item),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KeenScale.dp14),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (enabled) stringResource(R.string.home_available) else "—", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun healthColor(health: OverviewHealth) = when (health) {
    OverviewHealth.HEALTHY -> MaterialTheme.colorScheme.tertiary
    OverviewHealth.DEGRADED, OverviewHealth.SETUP_REQUIRED -> MaterialTheme.colorScheme.secondary
    OverviewHealth.LOCKED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

private data class HealthPresentation(val title: String, val body: String, val icon: ImageVector)
