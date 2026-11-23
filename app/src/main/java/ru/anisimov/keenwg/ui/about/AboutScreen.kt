package ru.anisimov.keenwg.ui.about

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import ru.anisimov.keenwg.ui.components.KeenButton as Button
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.BuildConfig
import ru.anisimov.keenwg.R

private val AboutCardShape = ContinuousShape(KeenRadius.card)
private val AboutRowShape = ContinuousShape(KeenRadius.control)

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
                .padding(horizontal = KeenSpacing.screen, vertical = KeenSpacing.group),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            IdentityCard(provenance)

            RouterComponentCard(onClick = onOpenRouterComponent)

            CompanyCard(
                onOpenLink = { uriHandler.openUri(it.url) },
            )

            Text(
                stringResource(R.string.about_expert_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = KeenSpacing.tight, top = KeenSpacing.tight),
            )
            ExpertModeCard(
                enabled = expertMode,
                onEnabledChange = { enabled ->
                    if (enabled) confirmExpertMode = true else vm.setExpertMode(false)
                },
                onOpenManualSettings = onOpenManualSettings,
            )
            Spacer(Modifier.height(KeenSpacing.inline))
        }
    }

    if (confirmExpertMode) {
        AlertDialog(
            onDismissRequest = { confirmExpertMode = false },
            title = { Text(stringResource(R.string.about_expert_confirm_title)) },
            text = { Text(stringResource(R.string.about_expert_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExpertMode = false
                        vm.setExpertMode(true)
                    },
                ) {
                    Text(stringResource(R.string.about_expert_confirm))
                }
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
private fun IdentityCard(provenance: BuildProvenance) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = AboutCardShape,
    ) {
        Column(
            modifier = Modifier.padding(KeenSpacing.item),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            ) {
                Surface(
                    modifier = Modifier.size(KeenScale.dp72),
                    shape = ContinuousShape(KeenRadius.control),
                    color = colorResource(R.color.launcher_background),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        tint = Color.Unspecified,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KeenScale.dp2),
                ) {
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

            Text(
                stringResource(R.string.about_summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ProvenanceRow(provenance)
        }
    }
}

@Composable
private fun ProvenanceRow(provenance: BuildProvenance) {
    val official = provenance == BuildProvenance.OFFICIAL
    val color = if (official) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (official) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(KeenSpacing.section),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KeenScale.dp2),
        ) {
            Text(
                stringResource(if (official) R.string.about_official_build else R.string.about_unverified_build),
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    if (official) {
                        R.string.about_official_build_body
                    } else {
                        R.string.about_unverified_build_body
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouterComponentCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AboutCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = KeenScale.dp72)
                .padding(horizontal = KeenSpacing.item, vertical = KeenSpacing.group),
            horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KeenSpacing.section),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(KeenScale.dp2),
            ) {
                Text(
                    stringResource(R.string.about_router_component),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.about_router_component_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(KeenSpacing.card),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompanyCard(onOpenLink: (AboutLinkTarget) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = AboutCardShape,
    ) {
        Column(
            modifier = Modifier.padding(KeenSpacing.item),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.inline),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_apex_intellect_wordmark),
                contentDescription = stringResource(R.string.about_company_logo_description),
                modifier = Modifier
                    .width(KeenScale.dp201)
                    .height(KeenScale.dp60),
                tint = Color.Unspecified,
            )
            Text(
                stringResource(R.string.about_company_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = KeenSpacing.tight),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            aboutLinkTargets().forEachIndexed { index, target ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = KeenScale.dp52),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                AboutLink(
                    target = target,
                    onClick = { onOpenLink(target) },
                )
            }

            Text(
                stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = KeenSpacing.inline, top = KeenSpacing.tight),
            )
        }
    }
}

@Composable
private fun AboutLink(
    target: AboutLinkTarget,
    onClick: () -> Unit,
) {
    val icon: ImageVector
    val label: String
    when (target) {
        AboutLinkTarget.OFFICIAL_SOURCE -> {
            icon = Icons.Outlined.Code
            label = stringResource(R.string.about_official_source)
        }
        AboutLinkTarget.STAR_REPOSITORY -> {
            icon = Icons.Outlined.StarOutline
            label = stringResource(R.string.about_star_github)
        }
        AboutLinkTarget.COMPANY_WEBSITE -> {
            icon = Icons.Outlined.Language
            label = stringResource(R.string.about_company_website)
        }
        AboutLinkTarget.DEVELOPER -> {
            icon = Icons.AutoMirrored.Filled.OpenInNew
            label = stringResource(R.string.about_developer)
        }
        AboutLinkTarget.BRAND_POLICY -> {
            icon = Icons.AutoMirrored.Filled.OpenInNew
            label = stringResource(R.string.about_trademarks)
        }
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AboutRowShape,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = KeenScale.dp56)
                .padding(horizontal = KeenSpacing.inline, vertical = KeenSpacing.inline),
            horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KeenSpacing.section),
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(KeenSpacing.card),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpertModeCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenManualSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = AboutCardShape,
    ) {
        Column(
            modifier = Modifier.padding(KeenSpacing.item),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(KeenSpacing.section),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.about_expert_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.about_expert_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            AnimatedVisibility(enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(KeenSpacing.group)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KeenScale.dp10),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            stringResource(R.string.about_expert_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onOpenManualSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AboutRowShape,
                    ) {
                        Text(stringResource(R.string.about_manual_settings))
                    }
                }
            }
        }
    }
}
