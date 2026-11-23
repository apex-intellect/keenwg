package ru.anisimov.keenwg.ui.system

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSize

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import ru.anisimov.keenwg.ui.components.KeenTextButton as TextButton
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.core.os.LocaleListCompat
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.components.KeenGlassSurface
import ru.anisimov.keenwg.ui.localization.AppLanguage
import ru.anisimov.keenwg.ui.overview.OverviewState
import ru.anisimov.keenwg.ui.navigation.LocalKeenBottomClearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    state: OverviewState,
    onSetup: () -> Unit,
    onCompanion: () -> Unit,
    onTrustedDevices: () -> Unit,
    onDiagnostics: () -> Unit,
    onBackup: () -> Unit,
    onAbout: () -> Unit,
) {
    val presentation = systemPresentation(state)
    var languageDialogOpen by remember { mutableStateOf(false) }
    val currentLanguage = AppLanguage.fromLanguageTags(
        AppCompatDelegate.getApplicationLocales().toLanguageTags(),
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.system_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KeenSpacing.screen, vertical = KeenSpacing.group),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            RouterStatusCard(presentation)
            Text(
                text = stringResource(R.string.system_section_management),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = KeenSpacing.tight),
            )
            KeenGlassSurface(Modifier.fillMaxWidth()) {
                presentation.rows.forEachIndexed { index, row ->
                    SystemMenuRow(
                        row = row,
                        currentLanguage = currentLanguage,
                        onClick = {
                            when (row.action) {
                                SystemAction.CONNECTION -> onSetup()
                                SystemAction.COMPANION -> onCompanion()
                                SystemAction.DEVICES -> onTrustedDevices()
                                SystemAction.DIAGNOSTICS -> onDiagnostics()
                                SystemAction.BACKUP -> onBackup()
                                SystemAction.LANGUAGE -> languageDialogOpen = true
                                SystemAction.ABOUT -> onAbout()
                            }
                        },
                    )
                    if (index < presentation.rows.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = KeenSpacing.item),
                        )
                    }
                }
            }
            Spacer(Modifier.height(LocalKeenBottomClearance.current + KeenSpacing.tight))
        }
    }

    if (languageDialogOpen) {
        LanguageDialog(
            current = currentLanguage,
            onDismiss = { languageDialogOpen = false },
            onSelect = { language ->
                languageDialogOpen = false
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(language.languageTags),
                )
            },
        )
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
        SystemConnectionStatus.CONNECTED -> Icons.Outlined.CheckCircle
        SystemConnectionStatus.CHECKING -> Icons.Outlined.Refresh
        SystemConnectionStatus.DEGRADED,
        SystemConnectionStatus.SETUP_REQUIRED,
        -> Icons.Outlined.WarningAmber
        SystemConnectionStatus.LOCKED -> Icons.Outlined.Lock
    }

    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(KeenSpacing.group),
            horizontalArrangement = Arrangement.spacedBy(KeenScale.dp10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = statusColor.copy(alpha = 0.14f),
                contentColor = statusColor,
            ) {
                Icon(statusIcon, contentDescription = null, modifier = Modifier.padding(KeenScale.dp10).size(KeenSize.icon))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KeenScale.dp3)) {
                Text(
                    presentation.profileName ?: stringResource(R.string.system_router_default),
                    style = MaterialTheme.typography.titleMedium,
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
private fun SystemMenuRow(row: SystemRow, currentLanguage: AppLanguage, onClick: () -> Unit) {
    val (icon, title, body) = rowContent(row.action, currentLanguage)
    Surface(
        onClick = onClick,
        enabled = row.enabled,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().alpha(if (row.enabled) 1f else 0.46f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.heightIn(min = KeenScale.dp58).padding(horizontal = KeenSpacing.group, vertical = KeenScale.dp9),
            horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(KeenSize.icon))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KeenScale.dp2)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun rowContent(action: SystemAction, currentLanguage: AppLanguage): Triple<ImageVector, String, String> = when (action) {
    SystemAction.CONNECTION -> Triple(
        Icons.Outlined.Router,
        stringResource(R.string.system_connection_title),
        stringResource(R.string.system_connection_body),
    )
    SystemAction.COMPANION -> Triple(
        Icons.Outlined.SystemUpdate,
        stringResource(R.string.system_companion_title),
        stringResource(R.string.system_companion_body),
    )
    SystemAction.DEVICES -> Triple(
        Icons.Outlined.Devices,
        stringResource(R.string.system_devices_title),
        stringResource(R.string.system_devices_body),
    )
    SystemAction.DIAGNOSTICS -> Triple(
        Icons.Outlined.BugReport,
        stringResource(R.string.system_diagnostics_title),
        stringResource(R.string.system_diagnostics_body),
    )
    SystemAction.BACKUP -> Triple(
        Icons.Outlined.Backup,
        stringResource(R.string.system_backup_title),
        stringResource(R.string.system_backup_body),
    )
    SystemAction.LANGUAGE -> Triple(
        Icons.Outlined.Translate,
        stringResource(R.string.system_language_title),
        stringResource(
            when (currentLanguage) {
                AppLanguage.SYSTEM -> R.string.system_language_body_system
                AppLanguage.RUSSIAN -> R.string.system_language_body_russian
                AppLanguage.ENGLISH -> R.string.system_language_body_english
            },
        ),
    )
    SystemAction.ABOUT -> Triple(
        Icons.Outlined.Info,
        stringResource(R.string.system_about_title),
        stringResource(R.string.system_about_body),
    )
}

@Composable
private fun LanguageDialog(
    current: AppLanguage,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = KeenScale.dp360),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = KeenScale.dp6,
        ) {
            Column(Modifier.padding(KeenSpacing.item), verticalArrangement = Arrangement.spacedBy(KeenScale.dp6)) {
                Text(stringResource(R.string.language_dialog_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.language_change_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppLanguage.entries.forEach { language ->
                    Surface(
                        onClick = { onSelect(language) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (language == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = KeenSize.touch).padding(horizontal = KeenSpacing.inline),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == current, onClick = null)
                            Text(
                                stringResource(
                                    when (language) {
                                        AppLanguage.SYSTEM -> R.string.language_system
                                        AppLanguage.RUSSIAN -> R.string.language_russian
                                        AppLanguage.ENGLISH -> R.string.language_english
                                    },
                                ),
                                modifier = Modifier.padding(start = KeenSpacing.inline),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        }
    }
}
