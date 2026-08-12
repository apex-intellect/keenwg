package ru.anisimov.keenwg.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.components.KeenGlassSurface
import ru.anisimov.keenwg.ui.overview.OverviewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    state: OverviewState,
    onSetup: () -> Unit,
    onTrustedDevices: () -> Unit,
    onDiagnostics: () -> Unit,
    onBackup: () -> Unit,
    onAbout: () -> Unit,
) {
    val presentation = systemPresentation(state)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.system_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RouterStatusCard(presentation)
            Text(
                text = stringResource(R.string.system_section_management),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
            KeenGlassSurface(Modifier.fillMaxWidth()) {
                presentation.rows.forEachIndexed { index, row ->
                    SystemMenuRow(
                        row = row,
                        onClick = {
                            when (row.action) {
                                SystemAction.CONNECTION -> onSetup()
                                SystemAction.DEVICES -> onTrustedDevices()
                                SystemAction.DIAGNOSTICS -> onDiagnostics()
                                SystemAction.BACKUP -> onBackup()
                                SystemAction.ABOUT -> onAbout()
                            }
                        },
                    )
                    if (index < presentation.rows.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RouterStatusCard(presentation: SystemPresentation) {
    val statusColor = when (presentation.connectionStatus) {
        SystemConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
        SystemConnectionStatus.CHECKING -> MaterialTheme.colorScheme.primary
        SystemConnectionStatus.DEGRADED -> MaterialTheme.colorScheme.secondary
        SystemConnectionStatus.LOCKED,
        SystemConnectionStatus.SETUP_REQUIRED,
        -> MaterialTheme.colorScheme.error
    }
    val statusIcon = when (presentation.connectionStatus) {
        SystemConnectionStatus.CONNECTED -> Icons.Default.CheckCircle
        SystemConnectionStatus.CHECKING -> Icons.Default.Refresh
        SystemConnectionStatus.DEGRADED,
        SystemConnectionStatus.SETUP_REQUIRED,
        -> Icons.Default.WarningAmber
        SystemConnectionStatus.LOCKED -> Icons.Default.Lock
    }

    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = statusColor.copy(alpha = 0.14f),
                contentColor = statusColor,
            ) {
                Icon(statusIcon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.profileName ?: stringResource(R.string.system_router_default),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    statusText(presentation.connectionStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (presentation.availableModuleCount > 0) {
                    Text(
                        stringResource(R.string.system_modules_available, presentation.availableModuleCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemMenuRow(row: SystemRow, onClick: () -> Unit) {
    val (icon, title, body) = rowContent(row.action)
    Surface(
        onClick = onClick,
        enabled = row.enabled,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().alpha(if (row.enabled) 1f else 0.46f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun statusText(status: SystemConnectionStatus): String = stringResource(
    when (status) {
        SystemConnectionStatus.CHECKING -> R.string.system_status_checking
        SystemConnectionStatus.CONNECTED -> R.string.system_status_connected
        SystemConnectionStatus.DEGRADED -> R.string.system_status_degraded
        SystemConnectionStatus.LOCKED -> R.string.system_status_locked
        SystemConnectionStatus.SETUP_REQUIRED -> R.string.system_status_setup_required
    },
)

@Composable
private fun rowContent(action: SystemAction): Triple<ImageVector, String, String> = when (action) {
    SystemAction.CONNECTION -> Triple(
        Icons.Default.Router,
        stringResource(R.string.system_connection_title),
        stringResource(R.string.system_connection_body),
    )
    SystemAction.DEVICES -> Triple(
        Icons.Default.Devices,
        stringResource(R.string.system_devices_title),
        stringResource(R.string.system_devices_body),
    )
    SystemAction.DIAGNOSTICS -> Triple(
        Icons.Default.BugReport,
        stringResource(R.string.system_diagnostics_title),
        stringResource(R.string.system_diagnostics_body),
    )
    SystemAction.BACKUP -> Triple(
        Icons.Default.Backup,
        stringResource(R.string.system_backup_title),
        stringResource(R.string.system_backup_body),
    )
    SystemAction.ABOUT -> Triple(
        Icons.Default.Info,
        stringResource(R.string.system_about_title),
        stringResource(R.string.system_about_body),
    )
}
