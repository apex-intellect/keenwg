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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.overview.OverviewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterConnectionScreen(
    state: OverviewState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onRecover: () -> Unit,
    onChangeRouter: () -> Unit,
) {
    val presentation = routerConnectionPresentation(state)
    var confirmChangeRouter by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.router_connection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.router_connection_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectionStatusCard(
                profileName = state.selectedProfileName ?: stringResource(R.string.system_router_default),
                status = presentation.connectionStatus,
                message = state.messageResource?.let { stringResource(it) },
            )

            if (presentation.explainCredentials) {
                CredentialExplanation()
            }

            Button(
                onClick = {
                    when (presentation.primaryAction) {
                        RouterConnectionPrimaryAction.CHECK -> onCheck()
                        RouterConnectionPrimaryAction.RECOVER -> onRecover()
                    }
                },
                enabled = !presentation.checking,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                if (presentation.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.router_connection_checking))
                } else {
                    Icon(
                        if (presentation.primaryAction == RouterConnectionPrimaryAction.CHECK) Icons.Default.Refresh else Icons.Default.Router,
                        contentDescription = null,
                    )
                    Text(
                        stringResource(
                            if (presentation.primaryAction == RouterConnectionPrimaryAction.CHECK) {
                                R.string.router_connection_check
                            } else {
                                R.string.router_connection_recover
                            },
                        ),
                    )
                }
            }

            if (presentation.canRecover) {
                OutlinedButton(onClick = onRecover, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.router_connection_recover))
                }
                Text(
                    stringResource(R.string.router_connection_recover_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (presentation.canChangeRouter) {
                OutlinedButton(
                    onClick = { confirmChangeRouter = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.router_connection_change_router))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmChangeRouter) {
        AlertDialog(
            onDismissRequest = { confirmChangeRouter = false },
            title = { Text(stringResource(R.string.router_connection_change_confirm_title)) },
            text = { Text(stringResource(R.string.router_connection_change_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmChangeRouter = false
                    onChangeRouter()
                }) { Text(stringResource(R.string.router_connection_change_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangeRouter = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    profileName: String,
    status: SystemConnectionStatus,
    message: String?,
) {
    val color = when (status) {
        SystemConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
        SystemConnectionStatus.CHECKING -> MaterialTheme.colorScheme.primary
        SystemConnectionStatus.DEGRADED -> MaterialTheme.colorScheme.secondary
        SystemConnectionStatus.LOCKED,
        SystemConnectionStatus.SETUP_REQUIRED,
        -> MaterialTheme.colorScheme.error
    }
    val icon: ImageVector = when (status) {
        SystemConnectionStatus.CONNECTED -> Icons.Default.CheckCircle
        SystemConnectionStatus.CHECKING -> Icons.Default.Refresh
        SystemConnectionStatus.DEGRADED,
        SystemConnectionStatus.SETUP_REQUIRED,
        -> Icons.Default.WarningAmber
        SystemConnectionStatus.LOCKED -> Icons.Default.Lock
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(routerConnectionStatusText(status), color = color)
                if (!message.isNullOrBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CredentialExplanation() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.router_connection_credentials_title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.router_connection_credentials_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun routerConnectionStatusText(status: SystemConnectionStatus): String = stringResource(
    when (status) {
        SystemConnectionStatus.CHECKING -> R.string.router_connection_status_checking
        SystemConnectionStatus.CONNECTED -> R.string.router_connection_status_connected
        SystemConnectionStatus.DEGRADED -> R.string.router_connection_status_unavailable
        SystemConnectionStatus.LOCKED -> R.string.router_connection_status_locked
        SystemConnectionStatus.SETUP_REQUIRED -> R.string.router_connection_status_setup_required
    },
)
