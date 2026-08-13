package ru.anisimov.keenwg.ui.about

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.BuildConfig
import ru.anisimov.keenwg.R

private const val REPOSITORY_URL = "https://github.com/apex-intellect/keenwg"
private const val COMPANY_URL = "https://apex-intellect.ru/"
private const val DEVELOPER_URL = "https://github.com/th-notorious"
private const val TRADEMARKS_URL = "$REPOSITORY_URL/blob/main/TRADEMARKS.md"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenManualSettings: () -> Unit,
    onOpenRouterComponent: () -> Unit,
    vm: AboutViewModel = viewModel(),
) {
    val expertMode by vm.expertMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val provenance = remember(context.applicationContext) {
        currentBuildProvenance(context.applicationContext)
    }
    var confirmExpertMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_apex_route_mark),
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp).size(42.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.brand_by_apex),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(stringResource(R.string.about_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProvenanceCard(provenance)
                }
            }

            OutlinedButton(
                onClick = onOpenRouterComponent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(R.string.about_router_component), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.about_router_component_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.about_company), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.about_company_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AboutLink(
                        icon = Icons.Default.Code,
                        label = stringResource(R.string.about_official_source),
                        onClick = { uriHandler.openUri(REPOSITORY_URL) },
                    )
                    AboutLink(
                        icon = Icons.Default.StarOutline,
                        label = stringResource(R.string.about_star_github),
                        onClick = { uriHandler.openUri(REPOSITORY_URL) },
                    )
                    AboutLink(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.about_company_website),
                        onClick = { uriHandler.openUri(COMPANY_URL) },
                    )
                    AboutLink(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = stringResource(R.string.about_developer),
                        onClick = { uriHandler.openUri(DEVELOPER_URL) },
                    )
                    AboutLink(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = stringResource(R.string.about_trademarks),
                        onClick = { uriHandler.openUri(TRADEMARKS_URL) },
                    )
                    Text(
                        stringResource(R.string.about_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                stringResource(R.string.about_expert_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.about_expert_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.about_expert_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = expertMode,
                            onCheckedChange = { enabled ->
                                if (enabled) confirmExpertMode = true else vm.setExpertMode(false)
                            },
                        )
                    }
                    AnimatedVisibility(expertMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Text(
                                    stringResource(R.string.about_expert_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = onOpenManualSettings, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.about_manual_settings))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmExpertMode) {
        AlertDialog(
            onDismissRequest = { confirmExpertMode = false },
            title = { Text(stringResource(R.string.about_expert_confirm_title)) },
            text = { Text(stringResource(R.string.about_expert_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExpertMode = false
                    vm.setExpertMode(true)
                }) { Text(stringResource(R.string.about_expert_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExpertMode = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProvenanceCard(provenance: BuildProvenance) {
    val official = provenance == BuildProvenance.OFFICIAL
    val color = if (official) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (official) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(if (official) R.string.about_official_build else R.string.about_unverified_build),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (official) R.string.about_official_build_body else R.string.about_unverified_build_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
