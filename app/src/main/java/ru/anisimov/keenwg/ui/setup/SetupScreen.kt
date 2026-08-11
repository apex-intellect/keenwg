package ru.anisimov.keenwg.ui.setup

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallMode
import ru.anisimov.keenwg.data.installer.SshEndpoint
import ru.anisimov.keenwg.ui.theme.MonoLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    vm: SetupViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val suggestedHost by vm.suggestedHost.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("222") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val installing = state is SetupState.Installing
    BackHandler(enabled = installing) { }

    LaunchedEffect(suggestedHost) {
        if (host.isBlank() && suggestedHost.isNotBlank()) host = suggestedHost
    }

    fun endpoint(): SshEndpoint? = runCatching {
        SshEndpoint(host.trim(), port.toInt(), username.trim())
    }.onFailure { formError = "Проверьте адрес, SSH-порт и пользователя" }.getOrNull()

    fun consumePassword(action: (ByteArray) -> Unit) {
        if (password.isBlank()) {
            formError = "Введите временный SSH-пароль"
            return
        }
        val bytes = password.toByteArray()
        password = ""
        formError = null
        action(bytes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Companion") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !installing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_setupscreen_1a9fb1f3cf))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                SetupState.Idle -> IdleContent(
                    host = host,
                    port = port,
                    username = username,
                    error = formError,
                    onHost = { host = it; formError = null },
                    onPort = { port = it.filter(Char::isDigit); formError = null },
                    onUsername = { username = it; formError = null },
                    onObserve = { endpoint()?.let(vm::observeHostKey) },
                )
                is SetupState.HostKeyApproval -> {
                    StepHeader("1 из 2", "Подтвердите ключ роутера", Icons.Default.Security)
                    Text(stringResource(R.string.ui_setupscreen_148cb9cbf6), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KeyCard(current.key.algorithm, current.key.sha256)
                    PasswordField(password, { password = it; formError = null })
                    formError?.let { ErrorText(it) }
                    Button(
                        onClick = { consumePassword(vm::approveHostKey) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.ui_setupscreen_f1080dfab1)) }
                    OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_setupscreen_8fbe9b75cb)) }
                }
                is SetupState.Probing -> BusyContent(current.step)
                is SetupState.Review -> {
                    StepHeader("2 из 2", "Проверьте план установки", Icons.Default.Router)
                    ProbeCard(current)
                    current.plan.effects.forEachIndexed { index, effect ->
                        InfoRow("${index + 1}", effect)
                    }
                    Text(stringResource(R.string.ui_setupscreen_6d6cb8638d), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(password, { password = it; formError = null })
                    formError?.let { ErrorText(it) }
                    Button(
                        onClick = { consumePassword { vm.confirmInstall(it, "KeenWG ${Build.MODEL}") } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(
                        when (current.plan.mode) {
                            InstallMode.CLEAN_INSTALL -> stringResource(R.string.setup_install_companion, current.plan.version)
                            InstallMode.UPDATE -> stringResource(R.string.setup_update_companion, current.plan.version)
                            InstallMode.PAIR_ONLY -> stringResource(R.string.setup_pair_phone)
                        },
                    )
                }
                    OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_setupscreen_8fbe9b75cb)) }
                }
                is SetupState.Installing -> InstallingContent(current)
                is SetupState.Completed -> {
                    StepHeader("Готово", "Companion подключён", Icons.Default.CheckCircle)
                    InfoRow("Версия", current.report.version)
                    InfoRow("HTTPS", current.report.secureBaseUrl)
                    InfoRow("Телефон", current.report.deviceId)
                    InfoRow("Очистка", if (current.report.cleanupSucceeded) "Временные файлы удалены" else "Нужна ручная проверка")
                    Button(onClick = onCompleted, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.ui_setupscreen_a9c74e8d03)) }
                }
                is SetupState.Failed -> FailureContent(current, vm::reset)
            }
        }
    }
}

@Composable
private fun IdleContent(
    host: String,
    port: String,
    username: String,
    error: String?,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onUsername: (String) -> Unit,
    onObserve: () -> Unit,
) {
    StepHeader("Безопасная установка", "Подключите companion к роутеру", Icons.Default.Key)
    Text(
        "KeenWG сначала получит публичный SSH-ключ без пароля. Установка начнётся только после двух явных подтверждений.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(host, onHost, label = { Text(stringResource(R.string.ui_setupscreen_dde7f6b38a)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            port, onPort, label = { Text(stringResource(R.string.ui_setupscreen_90947197f8)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.36f),
        )
        OutlinedTextField(username, onUsername, label = { Text(stringResource(R.string.ui_setupscreen_2a5c42af7c)) }, singleLine = true, modifier = Modifier.weight(0.64f))
    }
    error?.let { ErrorText(it) }
    Button(onClick = onObserve, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.ui_setupscreen_b2e75a12c2)) }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.ui_setupscreen_6e766fc57a)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeyCard(algorithm: String, fingerprint: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(algorithm, style = MaterialTheme.typography.labelLarge)
            Text(fingerprint, style = MonoLabel)
        }
    }
}

@Composable
private fun ProbeCard(state: SetupState.Review) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_setupscreen_25bab021a2), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            InfoRow("Архитектура", state.probe.architecture)
            InfoRow("Прошивка", state.probe.firmware)
            InfoRow("Свободно /opt", "${state.probe.optFreeBytes / 1024 / 1024} МиБ")
            InfoRow("Адрес API", state.plan.secureBaseUrl ?: stringResource(R.string.setup_companion_address_pending))
        }
    }
}

@Composable
private fun BusyContent(step: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(step, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_setupscreen_dccef2005c), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstallingContent(state: SetupState.Installing) {
    StepHeader("Установка", phaseLabel(state.phase), Icons.Default.Security)
    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.ui_setupscreen_edb20a8227), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FailureContent(state: SetupState.Failed, onReset: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    StepHeader("Не завершено", state.safeMessage, Icons.Default.Warning)
    InfoRow("Этап", phaseLabel(state.phase))
    InfoRow("Откат", if (state.rollbackVerified) "Проверен" else "Не подтверждён — проверьте отчёт")
    OutlinedButton(
        onClick = { clipboard.setText(AnnotatedString("KeenWG: ${state.phase.name}\n${state.safeMessage}\nrollback=${state.rollbackVerified}")) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null)
        Text(stringResource(R.string.ui_setupscreen_5f95c40b65))
    }
    Button(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.ui_setupscreen_9a885083e0)) }
}

@Composable
private fun StepHeader(eyebrow: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.34f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.66f))
    }
}

@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

private fun phaseLabel(phase: InstallPhase) = when (phase) {
    InstallPhase.VERIFY_ASSET -> "Проверяем пакет"
    InstallPhase.CONNECT -> "Подключаемся по SSH"
    InstallPhase.PROBE -> "Проверяем роутер"
    InstallPhase.UPLOAD -> "Передаём пакет"
    InstallPhase.INSTALL -> "Устанавливаем с rollback"
    InstallPhase.PAIRING_OFFER -> "Создаём одноразовую привязку"
    InstallPhase.PAIRING_EXCHANGE -> "Проверяем сертификат"
    InstallPhase.HEALTH -> "Проверяем защищённый API"
    InstallPhase.SAVE_PROFILE -> "Сохраняем профиль"
    InstallPhase.CLEANUP -> "Удаляем временные файлы"
}
