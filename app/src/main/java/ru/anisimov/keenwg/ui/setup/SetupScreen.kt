package ru.anisimov.keenwg.ui.setup

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallProbe
import ru.anisimov.keenwg.data.installer.SshEndpoint
import ru.anisimov.keenwg.ui.components.KeenGlassSurface
import ru.anisimov.keenwg.ui.theme.MonoLabel

private const val XKEEN_GUIDE_URL = "https://github.com/Corvus-Malus/XKeen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    vm: SetupViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val suggestedHost by vm.suggestedHost.collectAsStateWithLifecycle()
    val suggestedPort by vm.suggestedPort.collectAsStateWithLifecycle()
    val suggestedUsername by vm.suggestedUsername.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("222") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var showParameters by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var confirmChangedKey by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val busy = state is SetupState.Checking

    BackHandler(enabled = busy) { }
    LaunchedEffect(suggestedHost, suggestedPort, suggestedUsername) {
        if (host.isBlank()) host = suggestedHost
        if (port == "222") port = suggestedPort.toString()
        if (username == "root") username = suggestedUsername
    }

    fun submit() {
        if (password.isBlank()) {
            formError = "password"
            return
        }
        val candidate = runCatching { SshEndpoint(host.trim(), port.toInt(), username.trim()) }
            .getOrElse {
                formError = "endpoint"
                return
            }
        val bytes = password.toByteArray()
        password = ""
        formError = null
        vm.connect(candidate, bytes, "KeenWG ${Build.MODEL}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_top_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                SetupState.Credentials -> CredentialsContent(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    error = formError,
                    showParameters = showParameters,
                    onHostChange = { host = it; formError = null },
                    onPortChange = { port = it.filter(Char::isDigit); formError = null },
                    onUsernameChange = { username = it; formError = null },
                    onPasswordChange = { password = it; formError = null },
                    onToggleParameters = { showParameters = !showParameters },
                    onHelp = { showHelp = true },
                    onSubmit = ::submit,
                )
                is SetupState.Checking -> CheckingContent(current)
                is SetupState.PrerequisiteMissing -> PrerequisiteContent(
                    state = current,
                    onOpenGuide = { runCatching { uriHandler.openUri(XKEEN_GUIDE_URL) } },
                    onRetry = { vm.retryPrerequisites() },
                    onReset = vm::reset,
                )
                is SetupState.HostKeyChanged -> HostKeyChangedContent(
                    state = current,
                    onConfirm = { confirmChangedKey = true },
                    onReset = vm::reset,
                )
                is SetupState.Completed -> CompletedContent(current, onCompleted)
                is SetupState.Failed -> FailureContent(current, vm::reset)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.setup_help_title)) },
            text = { Text(stringResource(R.string.setup_help_body)) },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_understood)) } },
            dismissButton = {
                TextButton(onClick = { runCatching { uriHandler.openUri(XKEEN_GUIDE_URL) } }) {
                    Text(stringResource(R.string.setup_open_guide))
                }
            },
        )
    }

    if (confirmChangedKey) {
        AlertDialog(
            onDismissRequest = { confirmChangedKey = false },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.setup_key_confirm_title)) },
            text = { Text(stringResource(R.string.setup_key_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmChangedKey = false
                    vm.acceptChangedHostKey()
                }) { Text(stringResource(R.string.setup_key_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmChangedKey = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun CredentialsContent(
    host: String,
    port: String,
    username: String,
    password: String,
    error: String?,
    showParameters: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleParameters: () -> Unit,
    onHelp: () -> Unit,
    onSubmit: () -> Unit,
) {
    SetupHeader(Icons.Default.Router, stringResource(R.string.setup_connect_title), stringResource(R.string.setup_connect_body))
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setup_router_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(host.ifBlank { stringResource(R.string.setup_router_default) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setup_router_local_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.setup_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(password, onPasswordChange, error == "password")
    TextButton(onClick = onHelp) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.setup_where_credentials))
    }
    Text(
        stringResource(R.string.setup_credential_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onToggleParameters, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_change_parameters), modifier = Modifier.weight(1f))
        Icon(if (showParameters) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    AnimatedVisibility(showParameters) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.setup_router_address)) },
                singleLine = true,
                modifier = Modifier.weight(0.68f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.setup_router_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.32f),
            )
        }
    }
    if (error == "endpoint") InlineError(stringResource(R.string.setup_error_endpoint))
    Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.setup_connect_action))
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, error: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.setup_password)) },
        supportingText = if (error) ({ InlineError(stringResource(R.string.setup_error_password)) }) else null,
        isError = error,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.action_hide_password else R.string.action_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckingContent(state: SetupState.Checking) {
    SetupHeader(Icons.Default.Security, stringResource(R.string.setup_checking_title), stringResource(R.string.setup_checking_body))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ProgressRow(stringResource(R.string.setup_check_router), setupRowStatus(state.progress, 0))
            ProgressRow(stringResource(R.string.setup_check_entware), setupRowStatus(state.progress, 1))
            ProgressRow(stringResource(R.string.setup_check_modules), setupRowStatus(state.progress, 2))
            ProgressRow(stringResource(R.string.setup_check_access), setupRowStatus(state.progress, 3))
        }
    }
    InfoNotice(stringResource(R.string.setup_preserve_notice))
}

@Composable
private fun ProgressRow(label: String, state: SetupRowStatus) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                SetupRowStatus.COMPLETE -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                SetupRowStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                SetupRowStatus.PENDING -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                when (state) {
                    SetupRowStatus.COMPLETE -> R.string.setup_status_ready
                    SetupRowStatus.ACTIVE -> R.string.setup_status_now
                    SetupRowStatus.PENDING -> R.string.setup_status_pending
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (state == SetupRowStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PrerequisiteContent(
    state: SetupState.PrerequisiteMissing,
    onOpenGuide: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    SetupHeader(Icons.Default.Info, stringResource(R.string.setup_prerequisite_title), stringResource(R.string.setup_prerequisite_body))
    RouterProbeCard(state.probe)
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.missing.forEach { missing ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(stringResource(missing.labelResource()), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.setup_no_format_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (SetupPrerequisite.ENTWARE in state.missing) {
        Button(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Text(stringResource(R.string.setup_open_guide))
        }
    }
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.setup_check_again))
    }
    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_enter_again)) }
}

@Composable
private fun HostKeyChangedContent(
    state: SetupState.HostKeyChanged,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
) {
    SetupHeader(Icons.Default.WarningAmber, stringResource(R.string.setup_key_changed_title), stringResource(R.string.setup_key_changed_body))
    InfoNotice(stringResource(R.string.setup_key_changed_warning), error = true)
    TechnicalDetails {
        Text(stringResource(R.string.setup_key_previous), style = MaterialTheme.typography.labelMedium)
        Text("${state.expected.algorithm} · ${state.expected.sha256}", style = MonoLabel)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_key_observed), style = MaterialTheme.typography.labelMedium)
        Text("${state.observed.algorithm} · ${state.observed.sha256}", style = MonoLabel)
    }
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.setup_key_changed_continue))
    }
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun CompletedContent(state: SetupState.Completed, onCompleted: () -> Unit) {
    SetupHeader(Icons.Default.CheckCircle, stringResource(R.string.setup_complete_title), stringResource(R.string.setup_complete_body))
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            ResultRow(stringResource(R.string.setup_result_protected_access))
        }
    }
    Text(
        stringResource(R.string.setup_complete_version, state.report.version),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onCompleted, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.setup_go_overview))
    }
}

@Composable
private fun ResultRow(label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(label, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.setup_status_configured), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun FailureContent(state: SetupState.Failed, onReset: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    SetupHeader(
        Icons.Default.WarningAmber,
        stringResource(R.string.setup_failed_title),
        stringResource(state.phase.failureBodyResource()),
    )
    InfoNotice(
        stringResource(if (state.rollbackVerified) R.string.setup_failure_unchanged else R.string.setup_failure_uncertain),
        error = !state.rollbackVerified,
    )
    TechnicalDetails {
        Text(stringResource(R.string.setup_failure_stage, state.phase.userCode()), style = MonoLabel)
        Spacer(Modifier.height(6.dp))
        Text(state.safeMessage, style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.action_try_again))
    }
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString("KeenWG ${state.phase.userCode()}\n${state.safeMessage}\nrouter_unchanged_confirmed=${state.rollbackVerified}"))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null)
        Text(stringResource(R.string.setup_copy_report))
    }
}

@Composable
private fun SetupHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouterProbeCard(probe: InstallProbe) {
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_router_compatible), style = MaterialTheme.typography.titleMedium)
            InfoRow(stringResource(R.string.setup_probe_architecture), probe.architecture)
            InfoRow(stringResource(R.string.setup_probe_firmware), probe.firmware)
            InfoRow(stringResource(R.string.setup_probe_storage), stringResource(R.string.setup_storage_mib, probe.optFreeBytes / 1024 / 1024))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun InfoNotice(message: String, error: Boolean = false) {
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (error) Icons.Default.WarningAmber else Icons.Default.Lock,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TechnicalDetails(content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    KeenGlassSurface(Modifier.fillMaxWidth()) {
        Column {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.setup_technical_details), modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = { content() })
            }
        }
    }
}

@Composable
private fun InlineError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun SetupPrerequisite.labelResource(): Int = when (this) {
    SetupPrerequisite.ENTWARE -> R.string.setup_missing_entware
    SetupPrerequisite.STORAGE -> R.string.setup_missing_storage
}

private fun InstallPhase.userCode(): String = when (this) {
    InstallPhase.VERIFY_ASSET -> "PACKAGE"
    InstallPhase.CONNECT -> "CONNECT"
    InstallPhase.PROBE -> "CHECK"
    InstallPhase.UPLOAD -> "TRANSFER"
    InstallPhase.INSTALL -> "INSTALL"
    InstallPhase.PAIRING_OFFER, InstallPhase.PAIRING_EXCHANGE -> "PHONE_ACCESS"
    InstallPhase.HEALTH -> "VERIFY"
    InstallPhase.SAVE_PROFILE -> "SAVE"
    InstallPhase.CLEANUP -> "CLEANUP"
}

private fun InstallPhase.failureBodyResource(): Int = when (this) {
    InstallPhase.VERIFY_ASSET -> R.string.setup_failure_package
    InstallPhase.CONNECT -> R.string.setup_failure_connection
    InstallPhase.PROBE -> R.string.setup_failure_readiness
    InstallPhase.UPLOAD,
    InstallPhase.INSTALL,
    -> R.string.setup_failure_installation
    InstallPhase.PAIRING_OFFER,
    InstallPhase.PAIRING_EXCHANGE,
    -> R.string.setup_failure_phone_access
    InstallPhase.HEALTH,
    InstallPhase.SAVE_PROFILE,
    InstallPhase.CLEANUP,
    -> R.string.setup_failure_verification
}
