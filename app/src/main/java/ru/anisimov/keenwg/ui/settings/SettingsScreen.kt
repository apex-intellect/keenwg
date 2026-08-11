package ru.anisimov.keenwg.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.discovery.DiscoveryPreview
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewState
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    productState: OverviewState? = null,
    onSetupCompanion: () -> Unit = {},
    onTrustedDevices: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onBackup: () -> Unit = {},
) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var draft by remember(saved) { mutableStateOf(saved) }
    var portText by remember(saved) { mutableStateOf(saved.port.toString()) }
    var mtuText by remember(saved) { mutableStateOf(saved.mtu.toString()) }
    var keepaliveText by remember(saved) { mutableStateOf(saved.keepalive.toString()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var hiddenPreview by remember { mutableStateOf<DiscoveryPreview?>(null) }

    LaunchedEffect(vm) {
        vm.msg.collect { snackbar.showSnackbar(it) }
    }

    fun parsedDraft(): ServerSettings? = parseNumericSettings(draft, portText, mtuText, keepaliveText)
        .onFailure { formError = it.message }
        .getOrNull()

    fun runOperation(label: String, operation: (ServerSettings) -> Job) {
        if (productState?.mutationsEnabled == false) return
        val current = parsedDraft() ?: return
        if (busyAction != null) return
        formError = null
        busyAction = label
        val job = operation(current)
        scope.launch {
            job.join()
            busyAction = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_settingsscreen_985b5e0f2c)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                Button(
                    onClick = { runOperation("Сохраняем и проверяем…", vm::saveAndTest) },
                    enabled = busyAction == null && productState?.mutationsEnabled != false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .height(52.dp),
                ) {
                    if (busyAction != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("  $busyAction")
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text(stringResource(R.string.ui_settingsscreen_ae95f45059))
                    }
                }
            }
        },
    ) { contentPadding ->
        CompositionLocalProvider(LocalSettingsFieldsEnabled provides (busyAction == null && productState?.mutationsEnabled != false)) {
            Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "KeenWG проверяет доступ к роутеру до сохранения. Пароль и токены остаются в защищённом хранилище этого телефона.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            productState?.let { SystemProfileCard(it) }
            OutlinedButton(
                onClick = onSetupCompanion,
                enabled = busyAction == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Key, contentDescription = null)
                Text(stringResource(R.string.ui_settingsscreen_705bba5971))
            }
            if (productState?.capabilities?.capabilities.orEmpty().any { it.id == "system.devices" && it.available }) {
                OutlinedButton(
                    onClick = onTrustedDevices,
                    enabled = busyAction == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Devices, contentDescription = null)
                    Text(stringResource(R.string.ui_settingsscreen_4ca7c3972e))
                }
            }
            OutlinedButton(
                onClick = onDiagnostics,
                enabled = busyAction == null && productState?.health != OverviewHealth.SETUP_REQUIRED,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Text("  " + stringResource(R.string.settings_support_report))
            }
            OutlinedButton(
                onClick = onBackup,
                enabled = busyAction == null && productState?.health != OverviewHealth.SETUP_REQUIRED,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Backup, contentDescription = null)
                Text("  " + stringResource(R.string.backup_settings_action))
            }
            formError?.let { StatusNotice(stringResource(R.string.ui_settingsscreen_f236f158aa), detail = it, isError = true) }

            SettingsSection(
                icon = { Icon(Icons.Default.Router, contentDescription = null) },
                title = stringResource(R.string.ui_settingsscreen_4a65bf9c4b),
                subtitle = stringResource(R.string.ui_settingsscreen_5b430f96ba),
            ) {
                SettingsTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_83cabd76c8),
                    placeholder = "192.168.1.1",
                )
                SettingsTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_39d3c42bd5),
                    keyboardType = KeyboardType.Number,
                )
                SettingsTextField(
                    value = draft.login,
                    onValueChange = { draft = draft.copy(login = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_5be12bfe49),
                )
                SettingsTextField(
                    value = draft.password,
                    onValueChange = { draft = draft.copy(password = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_cb1a2074b3),
                    password = true,
                )
            }

            SettingsSection(
                icon = { Icon(Icons.Default.Key, contentDescription = null) },
                title = "WireGuard",
                subtitle = stringResource(R.string.ui_settingsscreen_8f508532ef),
            ) {
                SettingsTextField(
                    value = draft.interfaceId,
                    onValueChange = { draft = draft.copy(interfaceId = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_517c6d91fc),
                    placeholder = "Wireguard0",
                )
                SettingsTextField(
                    value = draft.endpoint,
                    onValueChange = { draft = draft.copy(endpoint = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_8480b573ef),
                    placeholder = "vpn.example.net:51820",
                )
                SettingsTextField(
                    value = draft.serverPublicKey,
                    onValueChange = { draft = draft.copy(serverPublicKey = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_151e612124),
                    mono = true,
                )
            }

            SettingsSection(
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                title = stringResource(R.string.ui_settingsscreen_1417b7792d),
                subtitle = stringResource(R.string.ui_settingsscreen_0737242cfc),
            ) {
                SettingsTextField(
                    value = draft.collectorUrl,
                    onValueChange = { draft = draft.copy(collectorUrl = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_fd4b2db378),
                    placeholder = "http://10.8.0.1:18777",
                )
                SettingsTextField(
                    value = draft.collectorToken,
                    onValueChange = { draft = draft.copy(collectorToken = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_8a39c18d47),
                    password = true,
                )
                OutlinedButton(
                    onClick = { runOperation("Проверяем историю…", vm::testCollector) },
                    enabled = busyAction == null && productState?.mutationsEnabled != false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Text(stringResource(R.string.ui_settingsscreen_5ca026bf4c))
                }
            }

            SettingsSection(
                icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                title = stringResource(R.string.ui_settingsscreen_ff296ddaed),
                subtitle = stringResource(R.string.ui_settingsscreen_8ac3871e5a),
            ) {
                SettingsTextField(
                    value = draft.subnetBase,
                    onValueChange = { draft = draft.copy(subnetBase = it); formError = null },
                    label = stringResource(R.string.ui_settingsscreen_2635aebaee),
                    placeholder = "10.8.0.",
                    mono = true,
                )
                SettingsTextField(
                    value = draft.dns,
                    onValueChange = { draft = draft.copy(dns = it); formError = null },
                    label = "DNS",
                    mono = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsTextField(
                        value = mtuText,
                        onValueChange = { mtuText = it.filter(Char::isDigit); formError = null },
                        label = "MTU",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsTextField(
                        value = keepaliveText,
                        onValueChange = { keepaliveText = it.filter(Char::isDigit); formError = null },
                        label = stringResource(R.string.ui_settingsscreen_a4ddfbe571),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        enabled = busyAction == null && productState?.mutationsEnabled != false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Text(stringResource(R.string.ui_settingsscreen_a3c4da8156), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Icon(if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    if (showAdvanced) {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Получить интерфейс и публичный ключ с роутера. Найденные изменения сначала появятся для проверки — endpoint не заменяется без вашего согласия.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    hiddenPreview = null
                                    runOperation("Получаем параметры…", vm::discover)
                                },
                                enabled = busyAction == null && productState?.mutationsEnabled != false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Text(stringResource(R.string.ui_settingsscreen_b810b420a8))
                            }
                            Text(
                                "HTTP разрешён только для локальных адресов. Для публичного имени сборщика требуется HTTPS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            }
        }
    }

    preview?.takeIf { it != hiddenPreview }?.let { found ->
        DiscoveryPreviewDialog(
            current = draft,
            preview = found,
            busy = busyAction != null,
            mutationsEnabled = productState?.mutationsEnabled != false,
            onDismiss = { hiddenPreview = found },
            onApply = { acceptEndpoint ->
                val current = parsedDraft() ?: return@DiscoveryPreviewDialog
                busyAction = "Применяем и проверяем…"
                val job = vm.applyPreviewAndSave(current, found, acceptEndpoint)
                scope.launch {
                    job.join()
                    hiddenPreview = if (vm.preview.value == null) found else null
                    busyAction = null
                }
            },
        )
    }
}

@Composable
private fun SystemProfileCard(state: OverviewState) {
    val modules = state.capabilities?.capabilities.orEmpty().count { it.available }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(state.selectedProfileName ?: stringResource(R.string.ui_settingsscreen_a4ee9eab34), fontWeight = FontWeight.SemiBold)
                Text(
                    when (state.health) {
                        OverviewHealth.HEALTHY -> "Companion подключён · модулей: $modules"
                        OverviewHealth.LOCKED -> "Изменения заблокированы до восстановления"
                        OverviewHealth.DEGRADED -> "Защищённый канал временно недоступен"
                        OverviewHealth.SETUP_REQUIRED -> "Требуется настройка companion"
                        OverviewHealth.LOADING -> "Проверяем состояние…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(0.dp),
                ) {
                    Row(Modifier.padding(8.dp)) { icon() }
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        enabled = LocalSettingsFieldsEnabled.current,
        textStyle = if (mono) MonoLabel else MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

@Composable
private fun DiscoveryPreviewDialog(
    current: ServerSettings,
    preview: DiscoveryPreview,
    busy: Boolean,
    mutationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (Boolean) -> Unit,
) {
    var acceptEndpoint by remember(preview) { mutableStateOf(false) }
    val rows = discoveryPreviewRows(current, preview)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ui_settingsscreen_103fb24291)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            Text(
                                if (row.changed) "изменится" else "без изменений",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (row.changed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(row.value, style = MonoLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                preview.endpointCandidate?.let { candidate ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acceptEndpoint, onCheckedChange = { acceptEndpoint = it }, enabled = !busy && mutationsEnabled)
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(stringResource(R.string.ui_settingsscreen_4f3fa90799))
                            Text(candidate, style = MonoLabel)
                        }
                    }
                    Text(
                        "Текущий endpoint не меняется, пока вы явно не выберете этот вариант.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(acceptEndpoint) }, enabled = !busy && mutationsEnabled) { Text(stringResource(R.string.ui_settingsscreen_37aab38ece)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_settingsscreen_8fbe9b75cb)) } },
    )
}

internal data class DiscoveryPreviewRow(val label: String, val value: String, val changed: Boolean)

private val LocalSettingsFieldsEnabled = staticCompositionLocalOf { true }

internal fun discoveryPreviewRows(current: ServerSettings, preview: DiscoveryPreview): List<DiscoveryPreviewRow> = listOf(
    DiscoveryPreviewRow("Интерфейс", preview.interfaceId, preview.interfaceId != current.interfaceId),
    DiscoveryPreviewRow(
        "Публичный ключ",
        preview.serverPublicKey,
        preview.serverPublicKey != current.serverPublicKey,
    ),
    DiscoveryPreviewRow(
        "Endpoint",
        preview.reviewedEndpoint.ifBlank { current.endpoint.ifBlank { "не задан" } },
        preview.reviewedEndpoint.isNotBlank() && preview.reviewedEndpoint != current.endpoint,
    ),
)

internal fun parseNumericSettings(
    draft: ServerSettings,
    port: String,
    mtu: String,
    keepalive: String,
): Result<ServerSettings> = runCatching {
    val parsedPort = port.toIntOrNull() ?: error("Порт должен быть числом")
    val parsedMtu = mtu.toIntOrNull() ?: error("MTU должен быть числом")
    val parsedKeepalive = keepalive.toIntOrNull() ?: error("Keepalive должен быть числом")
    draft.copy(port = parsedPort, mtu = parsedMtu, keepalive = parsedKeepalive)
}
