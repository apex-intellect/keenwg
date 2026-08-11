package ru.anisimov.keenwg.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.data.support.SupportCheck
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.shareSupportReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onSetupCompanion: () -> Unit,
    vm: SupportViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(stringResource(R.string.support_title))
                    Text(stringResource(R.string.support_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.support_intro_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.support_intro_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.error?.let { item { StatusNotice(stringResource(R.string.support_error_title), detail = it, isError = true) } }
            if (state.requirement == SupportRequirement.COMPANION_PAIRING) {
                item {
                    StatusNotice(
                        stringResource(R.string.support_pairing_required_title),
                        detail = stringResource(R.string.support_pairing_required_detail),
                    )
                }
                item {
                    Button(onClick = onSetupCompanion, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.support_pairing_action))
                    }
                }
            }
            val export = state.export
            if (export == null && state.requirement == null) {
                item {
                    Button(onClick = vm::generate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Default.Refresh, null)
                        Text("  " + stringResource(if (state.busy) R.string.support_generating else R.string.support_generate))
                    }
                }
            } else if (export != null) {
                item {
                    StatusNotice(
                        stringResource(R.string.support_ready_title),
                        detail = stringResource(R.string.support_ready_detail, export.bundle.generatedAt),
                    )
                }
                items(export.bundle.report.checks, key = { it.layer }) { check -> SupportCheckCard(check) }
                item {
                    Text(stringResource(R.string.support_txt_content), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        SelectionContainer {
                            Text(export.text, style = MonoLabel, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            runCatching { shareSupportReport(context, export) }
                                .onFailure { Toast.makeText(context, context.getString(R.string.support_share_failed), Toast.LENGTH_SHORT).show() }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.IosShare, null)
                        Text("  " + stringResource(R.string.support_share))
                    }
                }
                item {
                    OutlinedButton(onClick = vm::generate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Refresh, null)
                        Text("  " + stringResource(R.string.support_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportCheckCard(check: SupportCheck) {
    val icon = when (check.status) {
        "ok" -> Icons.Default.CheckCircle
        "failed" -> Icons.Default.Error
        else -> Icons.Default.Info
    }
    val tint = when (check.status) {
        "ok" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(check.layer.supportLayerLabel(), style = MaterialTheme.typography.titleMedium)
                Text(check.status.supportStatusLabel(), color = tint, style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.support_observation, check.observation.code.supportEvidenceLabel(), check.observation.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.support_inference, check.inference.code.supportEvidenceLabel(), check.inference.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable private fun String.supportLayerLabel() = when (this) { "dns" -> "DNS"; "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; "tcp" -> "TCP"; "quic" -> "QUIC"; else -> stringResource(R.string.support_layer_network) }
@Composable private fun String.supportStatusLabel() = stringResource(when (this) { "ok" -> R.string.support_status_ok; "failed" -> R.string.support_status_failed; else -> R.string.support_status_unsupported })
private fun String.supportEvidenceLabel() = replace('_', ' ')
