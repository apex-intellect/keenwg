package ru.anisimov.keenwg.ui.add

import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.domain.normalizePeerName

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.QrImage
import ru.anisimov.keenwg.ui.util.shareConf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPeerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onUpdateCompanion: () -> Unit,
    vm: AddPeerViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.prepare() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.result == null) stringResource(R.string.ui_addpeerscreen_d820ccbe3d) else stringResource(R.string.ui_addpeerscreen_1e3072c041)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AddProgressHeader(success = state.result != null)

            if (state.result == null) {
                if (state.stage == AddPeerStage.REVIEW) {
                    AccessReviewCard(state)
                } else {
                    AddPeerForm(
                        state = state,
                        onNameChange = vm::onNameChange,
                        onIpChange = vm::onIpChange,
                        onAllowedNetworksChange = vm::onAllowedNetworksChange,
                        onDnsServersChange = vm::onDnsServersChange,
                        onExpiryDaysChange = vm::onExpiryDaysChange,
                        onHistoryEnabledChange = vm::onHistoryEnabledChange,
                    )
                }
                state.errorResource?.let { errorResource ->
                    StatusNotice(
                        title = stringResource(R.string.ui_addpeerscreen_5e9288c0d8),
                        detail = stringResource(errorResource),
                        isError = true,
                    )
                }
                if (state.companionUpdateRequired) {
                    Button(
                        onClick = onUpdateCompanion,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(stringResource(R.string.add_update_companion))
                    }
                } else {
                    Button(
                        onClick = { if (state.stage == AddPeerStage.REVIEW) vm.create() else vm.review() },
                        enabled = !state.busy && !state.preparing && state.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(stringResource(R.string.ui_addpeerscreen_0bfd2694df))
                        } else {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Text(stringResource(if (state.stage == AddPeerStage.REVIEW) R.string.access_apply else R.string.access_review))
                        }
                    }
                }
                if (state.stage == AddPeerStage.REVIEW && !state.busy) {
                    OutlinedButton(onClick = vm::cancelReview, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.access_edit))
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.access_apply_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val result = requireNotNull(state.result)
                StatusNotice(
                    title = stringResource(R.string.ui_addpeerscreen_dadb26499e),
                    detail = stringResource(R.string.ui_addpeerscreen_d5b491dc6a),
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(result.peer.name, style = MaterialTheme.typography.titleLarge)
                        Text(result.peer.ip ?: stringResource(R.string.ui_addpeerscreen_c83580eaa5), style = MonoLabel)
                        Surface(
                            color = androidx.compose.ui.graphics.Color.White,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            QrImage(result.conf, Modifier.padding(12.dp).size(248.dp), stringResource(R.string.qr_code_description))
                        }
                    }
                }
                StatusNotice(
                    title = stringResource(R.string.ui_addpeerscreen_628ed1505f),
                    detail = stringResource(R.string.access_single_reveal),
                )
                OutlinedButton(
                    onClick = { shareConf(context, result.peer.name, result.conf) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text(stringResource(R.string.ui_addpeerscreen_62bb18ef10))
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.ui_addpeerscreen_02624a1f36))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

}

@Composable
private fun AddProgressHeader(success: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepBadge(number = "1", label = stringResource(R.string.ui_addpeerscreen_dba20ab538), active = !success, completed = success)
        Box(
            Modifier.weight(1f).height(1.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.HorizontalDivider()
        }
        StepBadge(number = "2", label = stringResource(R.string.ui_addpeerscreen_9c365ff920), active = success, completed = false)
    }
}

@Composable
private fun StepBadge(number: String, label: String, active: Boolean, completed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (active || completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active || completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Text(if (completed) "✓" else number, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active || completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddPeerForm(
    state: AddPeerUiState,
    onNameChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onAllowedNetworksChange: (String) -> Unit,
    onDnsServersChange: (String) -> Unit,
    onExpiryDaysChange: (String) -> Unit,
    onHistoryEnabledChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ui_addpeerscreen_f92eb412b8), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.access_name_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.ui_addpeerscreen_04e6a146c0)) },
                placeholder = { Text(stringResource(R.string.ui_addpeerscreen_977e224b3c)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.name.isNotBlank()) {
                Text(
                    stringResource(R.string.access_router_name_preview, normalizePeerName(state.name)),
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.ip,
                onValueChange = onIpChange,
                label = { Text(stringResource(R.string.ui_addpeerscreen_df2ce7ac57)) },
                placeholder = { Text(if (state.preparing) stringResource(R.string.ui_addpeerscreen_c1f277f4a8) else "10.8.0.2") },
                supportingText = { Text(stringResource(R.string.ui_addpeerscreen_8559311a60)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.access_policy_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.allowedNetworks,
                onValueChange = onAllowedNetworksChange,
                label = { Text(stringResource(R.string.access_allowed_networks)) },
                supportingText = { Text(stringResource(R.string.access_allowed_networks_hint)) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.dnsServers,
                onValueChange = onDnsServersChange,
                label = { Text(stringResource(R.string.access_dns_servers)) },
                supportingText = { Text(stringResource(R.string.access_dns_hint)) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.expiryDays,
                onValueChange = onExpiryDaysChange,
                label = { Text(stringResource(R.string.access_expiry_days)) },
                supportingText = { Text(stringResource(R.string.access_expiry_hint)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.access_history_enabled), Modifier.weight(1f))
                Switch(checked = state.historyEnabled, onCheckedChange = onHistoryEnabledChange, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun AccessReviewCard(state: AddPeerUiState) {
    val policy = requireNotNull(state.reviewedPolicy)
    val expiry = state.expiryDays.takeIf(String::isNotBlank)?.let { stringResource(R.string.access_days, it) }
        ?: stringResource(R.string.access_never_expires)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.access_review_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.access_review_detail,
                    policy.allowedNetworks.joinToString(),
                    policy.dnsServers.joinToString().ifBlank { "—" },
                    expiry,
                    if (policy.historyEnabled) "✓" else "—",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
