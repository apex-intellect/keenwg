package ru.anisimov.keenwg.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KeenGlassSurface(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                        } else {
                            Icon(
                                when {
                                    state.phase == UpdatePhase.SUCCESS || state.phase == UpdatePhase.UP_TO_DATE -> Icons.Default.CheckCircle
                                    presentation.error -> Icons.Default.WarningAmber
                                    else -> Icons.Default.SecurityUpdateGood
                                },
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = if (presentation.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            if (state.phase == UpdatePhase.AVAILABLE) {
                                stringResource(presentation.title, state.targetVersion.orEmpty())
                            } else {
                                stringResource(presentation.title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(presentation.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (presentation.action) {
                        UpdateAction.UPDATE -> Button(onClick = { vm.install() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_action_install))
                        }
                        UpdateAction.RETRY -> Button(onClick = { vm.check() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_action_retry))
                        }
                        UpdateAction.CREDENTIAL_UPGRADE -> Button(onClick = onCredentialUpgrade, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_action_transition))
                        }
                        UpdateAction.DONE -> Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_action_done))
                        }
                        UpdateAction.NONE -> Unit
                    }
                }
            }
        }
    }
}
