package ru.anisimov.keenwg.ui.overview

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.domain.model.RouterProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    state: OverviewState,
    onRefresh: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onOpenSystem: () -> Unit,
    onSetup: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KeenWG")
                        Text(
                            stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ui_overviewscreen_ed96001620))
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.showProfileSelector) {
                ProfileSelector(state, onSelectProfile)
            } else {
                Text(
                    state.selectedProfileName ?: stringResource(R.string.home_router_not_selected),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            RouterHealthCard(state)

            state.activeXkeenNode?.let { node ->
                InfoCard(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.home_active_vpn_server),
                    body = node,
                )
            }

            Text(stringResource(R.string.home_sections), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ModuleCard("connections.", Icons.Default.Language, state)
            ModuleCard("routes.", Icons.Default.Route, state)
            ModuleCard("access.", Icons.Default.Devices, state)

            if (state.health == OverviewHealth.SETUP_REQUIRED || state.health == OverviewHealth.DEGRADED || state.health == OverviewHealth.LOCKED) {
                OutlinedButton(
                    onClick = if (state.health == OverviewHealth.LOCKED) onOpenSystem else onSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (state.health == OverviewHealth.LOCKED) stringResource(R.string.ui_overviewscreen_e1ea72a240) else stringResource(R.string.ui_overviewscreen_50a765b16f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileSelector(state: OverviewState, onSelectProfile: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Router, contentDescription = null)
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
                    leadingIcon = { if (profile.id == state.selectedProfileId) Icon(Icons.Default.CheckCircle, null) },
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
        OverviewHealth.LOADING -> HealthPresentation(stringResource(copy.titleResource), stringResource(copy.bodyResource), Icons.Default.Router)
        OverviewHealth.HEALTHY -> HealthPresentation(stringResource(copy.titleResource), stringResource(copy.bodyResource), Icons.Default.CheckCircle)
        OverviewHealth.DEGRADED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Default.Warning)
        OverviewHealth.SETUP_REQUIRED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Default.Warning)
        OverviewHealth.LOCKED -> HealthPresentation(stringResource(copy.titleResource), state.messageResource?.let { stringResource(it) } ?: stringResource(copy.bodyResource), Icons.Default.Lock)
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.health == OverviewHealth.HEALTHY) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.loading) CircularProgressIndicator(modifier = Modifier.height(28.dp), strokeWidth = 3.dp)
            else Icon(presentation.icon, contentDescription = null, tint = healthColor(state.health))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(presentation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(presentation.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModuleCard(prefix: String, icon: ImageVector, state: OverviewState) {
    val capabilities = state.capabilities?.capabilities.orEmpty().filter { it.id.startsWith(prefix) }
    val available = capabilities.filter { it.available }
    val writable = available.any { it.access == CapabilityAccess.WRITE }
    val detail = when {
        available.isEmpty() -> stringResource(R.string.home_not_configured)
        writable -> stringResource(R.string.home_management_available)
        else -> stringResource(R.string.home_view_available)
    }
    InfoCard(icon, stringResource(overviewModuleTitle(prefix)), detail, enabled = available.isNotEmpty())
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
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
