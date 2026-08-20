package ru.anisimov.keenwg.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.components.KeenGlassSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionUpdateScreen(
    onBack: () -> Unit,
    onCredentialUpgrade: () -> Unit,
    onDiagnostics: () -> Unit,
    vm: CompanionUpdateViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val presentation = updatePresentation(state.phase)
    val busy = state.phase in setOf(
        UpdatePhase.LOADING,
        UpdatePhase.VERIFYING,
        UpdatePhase.UPLOADING,
        UpdatePhase.INSTALLING,
        UpdatePhase.RECONNECTING,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.update_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ComponentSummary(state, presentation, busy)
            VersionCard(state)
            DiagnosticCard(state.checks)
            ComponentActions(
                phase = state.phase,
                action = presentation.action,
                busy = busy,
                onInstall = vm::install,
                onRetry = vm::check,
                onCredentialUpgrade = onCredentialUpgrade,
                onDone = onBack,
                onDiagnostics = onDiagnostics,
            )
        }
    }
}

@Composable
private fun ComponentSummary(
    state: CompanionUpdateUiState,
    presentation: UpdatePresentation,
    busy: Boolean,
) {
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusIcon(state.phase, presentation.error, busy)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (state.phase == UpdatePhase.AVAILABLE) {
                        stringResource(presentation.title, state.targetVersion.orEmpty())
                    } else {
                        stringResource(presentation.title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(presentation.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(phase: UpdatePhase, error: Boolean, busy: Boolean) {
    val color = when {
        error -> MaterialTheme.colorScheme.error
        phase == UpdatePhase.UP_TO_DATE || phase == UpdatePhase.SUCCESS -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(10.dp).size(24.dp),
                strokeWidth = 2.5.dp,
                color = color,
            )
        } else {
            Icon(
                imageVector = when {
                    error -> Icons.Default.WarningAmber
                    phase == UpdatePhase.UP_TO_DATE || phase == UpdatePhase.SUCCESS -> Icons.Default.CheckCircle
                    else -> Icons.Default.SecurityUpdateGood
                },
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
    }
}

@Composable
private fun VersionCard(state: CompanionUpdateUiState) {
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_versions_title), style = MaterialTheme.typography.titleMedium)
            VersionRow(R.string.update_version_installed, state.currentVersion)
            VersionRow(R.string.update_version_bundled, state.targetVersion)
        }
    }
}

@Composable
private fun VersionRow(label: Int, version: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(version ?: "—", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiagnosticCard(checks: List<CompanionStatusCheck>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            stringResource(R.string.update_checks_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            stringResource(R.string.update_checks_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        KeenGlassSurface(Modifier.fillMaxWidth()) {
            checks.forEachIndexed { index, check ->
                CheckRow(check)
                if (index < checks.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: CompanionStatusCheck) {
    val color = when (check.state) {
        CompanionCheckState.OK -> MaterialTheme.colorScheme.tertiary
        CompanionCheckState.ERROR -> MaterialTheme.colorScheme.error
        CompanionCheckState.ATTENTION -> MaterialTheme.colorScheme.secondary
        CompanionCheckState.CHECKING -> MaterialTheme.colorScheme.primary
        CompanionCheckState.NOT_CHECKED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (check.state) {
            CompanionCheckState.CHECKING -> CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = color)
            CompanionCheckState.OK -> Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = color)
            CompanionCheckState.ERROR,
            CompanionCheckState.ATTENTION,
            -> Icon(Icons.Default.WarningAmber, null, Modifier.size(20.dp), tint = color)
            CompanionCheckState.NOT_CHECKED -> Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(20.dp), tint = color)
        }
        Text(
            checkLabel(check.id),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            checkStateText(check),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComponentActions(
    phase: UpdatePhase,
    action: UpdateAction,
    busy: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onCredentialUpgrade: () -> Unit,
    onDone: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (action) {
            UpdateAction.UPDATE -> PrimaryAction(R.string.update_action_install, onInstall)
            UpdateAction.RETRY -> PrimaryAction(R.string.update_action_retry, onRetry)
            UpdateAction.CREDENTIAL_UPGRADE -> PrimaryAction(
                when (phase) {
                    UpdatePhase.NOT_CONFIGURED -> R.string.update_action_connect
                    UpdatePhase.NEEDS_PASSWORD -> R.string.update_action_transition
                    else -> R.string.update_action_recover
                },
                onCredentialUpgrade,
            )
            UpdateAction.DONE -> PrimaryAction(R.string.update_action_done, onDone)
            UpdateAction.NONE -> Unit
        }
        if (!busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (action != UpdateAction.RETRY) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.update_action_retry)) }
                }
                TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.update_action_diagnostics)) }
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

@Composable
private fun checkLabel(id: CompanionCheckId): String = stringResource(
    when (id) {
        CompanionCheckId.CONFIGURATION -> R.string.update_check_configuration
        CompanionCheckId.SERVICE -> R.string.update_check_service
        CompanionCheckId.STORAGE -> R.string.update_check_storage
        CompanionCheckId.PHONE_ACCESS -> R.string.update_check_phone
        CompanionCheckId.API -> R.string.update_check_api
        CompanionCheckId.UPDATE -> R.string.update_check_version
    },
)

@Composable
private fun checkStateText(check: CompanionStatusCheck): String = stringResource(
    when (check.state) {
        CompanionCheckState.CHECKING -> R.string.update_check_checking
        CompanionCheckState.NOT_CHECKED -> R.string.update_check_not_checked
        CompanionCheckState.ATTENTION -> R.string.update_check_attention
        CompanionCheckState.OK -> when (check.id) {
            CompanionCheckId.CONFIGURATION -> R.string.update_check_configuration_ok
            CompanionCheckId.SERVICE -> R.string.update_check_service_ok
            CompanionCheckId.STORAGE -> R.string.update_check_storage_ok
            CompanionCheckId.PHONE_ACCESS -> R.string.update_check_phone_ok
            CompanionCheckId.API -> R.string.update_check_api_ok
            CompanionCheckId.UPDATE -> R.string.update_check_version_ok
        }
        CompanionCheckState.ERROR -> when (check.id) {
            CompanionCheckId.CONFIGURATION -> R.string.update_check_configuration_error
            CompanionCheckId.SERVICE -> R.string.update_check_service_error
            CompanionCheckId.STORAGE -> R.string.update_check_storage_error
            CompanionCheckId.PHONE_ACCESS -> R.string.update_check_phone_error
            CompanionCheckId.API -> R.string.update_check_api_error
            CompanionCheckId.UPDATE -> R.string.update_check_version_error
        }
    },
)
