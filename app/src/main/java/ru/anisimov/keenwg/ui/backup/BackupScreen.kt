package ru.anisimov.keenwg.ui.backup

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import ru.anisimov.keenwg.ui.components.KeenButton as Button
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.anisimov.keenwg.ui.components.KeenOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import ru.anisimov.keenwg.ui.components.KeenTextButton as TextButton
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.backup.BackupPreview
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.BACKUP_MIME
import ru.anisimov.keenwg.ui.util.readBackupArchive
import ru.anisimov.keenwg.ui.util.shareBackupArchive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var confirmPlanId by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { readBackupArchive(context.contentResolver, uri) }
                .onSuccess(vm::loadArchive)
                .onFailure { Toast.makeText(context, R.string.backup_import_failed, Toast.LENGTH_SHORT).show() }
        }
    }
    DisposableEffect(vm) {
        onDispose {
            passphrase = ""
            vm.clearSensitiveState()
        }
    }

    fun consumePassphrase(action: (CharArray) -> Unit) {
        if (passphrase.length < 8 || state.busy) return
        val transient = passphrase.toCharArray()
        passphrase = ""
        action(transient)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.backup_title))
                        Text(
                            stringResource(R.string.backup_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = KeenSpacing.screen, top = KeenSpacing.group, end = KeenSpacing.screen, bottom = KeenScale.dp32),
            verticalArrangement = Arrangement.spacedBy(KeenSpacing.group),
        ) {
            item { BackupIntroCard() }
            state.error?.let { error -> item { BackupErrorNotice(error) } }
            item {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(1024) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    supportingText = { Text(stringResource(R.string.backup_passphrase_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
            }
            item {
                Button(
                    onClick = { consumePassphrase(vm::create) },
                    enabled = !state.busy && passphrase.length >= 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52),
                ) {
                    BusyIcon(state.busy, Icons.Outlined.Backup)
                    Text("  " + stringResource(R.string.backup_create))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
                    OutlinedButton(
                        onClick = { importer.launch(arrayOf(BACKUP_MIME, "application/json", "application/octet-stream")) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = KeenScale.dp50),
                    ) {
                        Icon(Icons.Outlined.FileOpen, null)
                        Text("  " + stringResource(R.string.backup_import))
                    }
                    OutlinedButton(
                        onClick = {
                            state.archive?.let {
                                runCatching { shareBackupArchive(context, it) }
                                    .onFailure { Toast.makeText(context, R.string.backup_share_failed, Toast.LENGTH_SHORT).show() }
                            }
                        },
                        enabled = !state.busy && state.archive != null,
                        modifier = Modifier.weight(1f).heightIn(min = KeenScale.dp50),
                    ) {
                        Icon(Icons.Outlined.IosShare, null)
                        Text("  " + stringResource(R.string.backup_export))
                    }
                }
            }
            if (state.archive != null) {
                item {
                    StatusNotice(
                        stringResource(R.string.backup_archive_loaded),
                        detail = stringResource(R.string.backup_archive_size, state.archive!!.size),
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { consumePassphrase(vm::preview) },
                        enabled = !state.busy && passphrase.length >= 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52),
                    ) {
                        BusyIcon(state.busy, Icons.Outlined.VerifiedUser)
                        Text("  " + stringResource(R.string.backup_preview))
                    }
                }
            }
            state.preview?.let { preview ->
                item { BackupPreviewCard(preview) }
                item {
                    Button(
                        onClick = { confirmPlanId = preview.planId },
                        enabled = !state.busy && passphrase.length >= 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp52),
                    ) {
                        Icon(Icons.Outlined.Restore, null)
                        Text("  " + stringResource(R.string.backup_apply))
                    }
                }
            }
            state.result?.let { result ->
                item {
                    StatusNotice(
                        stringResource(R.string.backup_restore_complete),
                        detail = stringResource(
                            R.string.backup_restore_result,
                            result.applied.size,
                            result.skippedForeign.size,
                        ),
                    )
                }
            }
        }
    }

    confirmPlanId?.let { planId ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmPlanId = null },
            icon = { Icon(Icons.Outlined.Restore, null) },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
                    Text(stringResource(R.string.backup_confirm_body))
                    Text(planId, style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        consumePassphrase { vm.apply(it, planId) }
                        confirmPlanId = null
                    },
                    enabled = !state.busy && passphrase.length >= 8,
                ) { Text(stringResource(R.string.backup_confirm_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPlanId = null }, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_cancel))
                }
            },
        )
    }
}

@Composable
private fun BackupIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(KeenSpacing.card), verticalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(KeenScale.dp28))
            Text(stringResource(R.string.backup_intro_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.backup_intro_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BackupPreviewCard(preview: BackupPreview) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(KeenSpacing.card), verticalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
            Text(stringResource(R.string.backup_preview_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.backup_source_version, preview.sourceVersion),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(preview.planId, style = MonoLabel, color = MaterialTheme.colorScheme.primary)
            preview.entries.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.id, style = MonoLabel, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.backup_entry_size, entry.bytes), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                stringResource(R.string.backup_preview_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BackupErrorNotice(error: BackupUiError) {
    val detail = stringResource(
        when (error) {
            BackupUiError.COMPANION_REQUIRED -> R.string.backup_error_companion
            BackupUiError.ARCHIVE_REQUIRED -> R.string.backup_error_archive
            BackupUiError.REVIEW_REQUIRED -> R.string.backup_error_review
            BackupUiError.CREATE_FAILED -> R.string.backup_error_create
            BackupUiError.PREVIEW_FAILED -> R.string.backup_error_preview
            BackupUiError.APPLY_FAILED -> R.string.backup_error_apply
        },
    )
    StatusNotice(stringResource(R.string.backup_error_title), detail = detail, isError = true)
}

@Composable
private fun BusyIcon(busy: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    if (busy) CircularProgressIndicator(Modifier.size(KeenSpacing.screen), strokeWidth = KeenScale.dp2, color = MaterialTheme.colorScheme.onPrimary)
    else Icon(icon, null)
}
