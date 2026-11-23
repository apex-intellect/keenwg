package ru.anisimov.keenwg.ui.support

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSize

import ru.anisimov.keenwg.ui.theme.KeenSpacing

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import ru.anisimov.keenwg.ui.components.KeenButton as Button
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.anisimov.keenwg.ui.components.KeenOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            contentPadding = PaddingValues(start = KeenSpacing.screen, top = KeenSpacing.group, end = KeenSpacing.screen, bottom = KeenScale.dp32),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(KeenSpacing.card), verticalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
                        Icon(Icons.Outlined.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.support_intro_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.support_intro_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.errorResource?.let { errorResource ->
                item {
                    StatusNotice(
                        stringResource(R.string.support_error_title),
                        detail = stringResource(errorResource),
                        isError = true,
                    )
                }
            }
            if (state.requirement == SupportRequirement.COMPANION_PAIRING) {
                item {
                    StatusNotice(
                        stringResource(R.string.support_pairing_required_title),
                        detail = stringResource(R.string.support_pairing_required_detail),
                    )
                }
                item {
                    Button(onClick = onSetupCompanion, modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52)) {
                        Text(stringResource(R.string.support_pairing_action))
                    }
                }
            }
            val export = state.export
            if (export == null && state.requirement == null) {
                item {
                    Button(onClick = vm::generate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(KeenSpacing.screen), strokeWidth = KeenScale.dp2, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Outlined.Refresh, null)
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
                    Text(stringResource(R.string.support_txt_content), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = KeenSpacing.tight))
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        SelectionContainer {
                            Text(export.text, style = MonoLabel, modifier = Modifier.padding(KeenSpacing.item))
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52),
                    ) {
                        Icon(Icons.Outlined.IosShare, null)
                        Text("  " + stringResource(R.string.support_share))
                    }
                }
                item {
                    OutlinedButton(onClick = vm::generate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = KeenSize.touch)) {
                        Icon(Icons.Outlined.Refresh, null)
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
        "ok" -> Icons.Outlined.CheckCircle
        "failed" -> Icons.Outlined.Error
        else -> Icons.Outlined.Info
    }
    val tint = when (check.status) {
        "ok" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.fillMaxWidth().padding(KeenScale.dp14), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group)) {
            Icon(icon, null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KeenSpacing.tight)) {
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
