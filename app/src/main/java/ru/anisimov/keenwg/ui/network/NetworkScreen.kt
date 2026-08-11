package ru.anisimov.keenwg.ui.network

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.NetworkDevice
import ru.anisimov.keenwg.data.network.NetworkExclusionEntry
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: NetworkViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var recoveryConfirmOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(stringResource(R.string.ui_networkscreen_8006cb7a49)); Text(stringResource(R.string.ui_networkscreen_0986ba26a5), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { IconButton(onClick = vm::refresh, enabled = !state.refreshing && !state.busy) { Icon(Icons.Default.Refresh, "Обновить сеть") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { NetworkSegmentControl(state, vm::selectSegment) }
            if (state.refreshing || state.loading) item { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            state.message?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_8b0b387c96), detail = it, isError = it.contains(stringResource(R.string.ui_networkscreen_3a865e3616)) || it.contains(stringResource(R.string.ui_networkscreen_50ccde397c))) } }
            if (state.writesBlocked) item { StatusNotice(
                "Изменения приостановлены",
                detail = if (state.recoveryState?.pending == true) stringResource(R.string.ui_networkscreen_994086f49a)
                    else stringResource(R.string.network_writes_blocked_detail),
                isError = true,
            ) }

            when (state.selectedSegment) {
                NetworkSegment.DEVICES -> devicesSection(state, vm)
                NetworkSegment.IP_ADDRESSES -> ipSection(state, vm)
                NetworkSegment.DOMAINS -> domainSection(state, vm)
                NetworkSegment.EXPLAIN -> explainSection(state, vm)
                NetworkSegment.SCENARIOS -> scenariosSection(state, vm) { recoveryConfirmOpen = true }
            }
        }
    }
    state.pendingDevice?.let { StaticIpDialog(it, state.busy, vm::dismissEdit, vm::confirmStaticIp, vm::removeStaticIp) }
    if (state.exclusionEditorOpen) ExclusionAddDialog(state.busy, vm::dismissExclusionEditor, vm::addExclusion)
    state.pendingExclusionDelete?.let { ExclusionDeleteDialog(it, state.busy, vm::dismissExclusionEditor, vm::confirmDeleteExclusion) }
    state.domainEditor?.let { DomainRuleDialog(it, state.busy, vm::updateDomainDraft, vm::reviewDomainDraft, vm::confirmDomainMutation, vm::dismissDomainEditor) }
    state.pendingDomainDelete?.let { DomainDeleteDialog(it, state.busy, vm::confirmDomainDelete, vm::dismissDomainEditor) }
    state.scenarioReview?.let { review ->
        AlertDialog(
            onDismissRequest = { if (!state.scenarioBusy) vm.dismissScenarioReview() },
            title = { Text(stringResource(R.string.ui_networkscreen_fa98c16a41)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_networkscreen_20b3f51f41), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                review.plan.steps.forEach { step ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(step.module.scenarioModuleLabel(), style = MaterialTheme.typography.labelLarge)
                        Text("${step.matchKind.scenarioMatcherLabel()} · ${step.value}", style = MaterialTheme.typography.bodyMedium)
                        Text(step.outcome.scenarioOutcomeLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } }
                }
            review.plan.skippedModules.forEach { Text(stringResource(R.string.scenario_skipped_module, it.scenarioModuleLabel()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.scenarioBusy) CircularProgressIndicator(Modifier.size(22.dp))
            } },
            confirmButton = { Button(onClick = vm::applyReviewedScenario, enabled = !state.scenarioBusy && review.plan.steps.isNotEmpty()) { Text(stringResource(R.string.ui_networkscreen_e15f960d93)) } },
            dismissButton = { TextButton(onClick = vm::dismissScenarioReview, enabled = !state.scenarioBusy) { Text(stringResource(R.string.ui_networkscreen_8fbe9b75cb)) } },
        )
    }
    if (recoveryConfirmOpen && state.recoveryState?.pending == true) {
        val recovery = requireNotNull(state.recoveryState)
        AlertDialog(
            onDismissRequest = { if (!state.scenarioBusy) recoveryConfirmOpen = false },
            title = { Text(stringResource(R.string.ui_networkscreen_3c2a545019)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_networkscreen_0c5f334ead))
                Text(stringResource(R.string.recovery_plan, recovery.planId.orEmpty()), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.recovery_modules, recovery.modules.joinToString { it.scenarioModuleLabel() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { Button(onClick = { recoveryConfirmOpen = false; vm.confirmRecovery() }, enabled = !state.scenarioBusy) { Text(stringResource(R.string.ui_networkscreen_76f55a988f)) } },
            dismissButton = { TextButton(onClick = { recoveryConfirmOpen = false }, enabled = !state.scenarioBusy) { Text(stringResource(R.string.ui_networkscreen_8fbe9b75cb)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.scenariosSection(state: NetworkUiState, vm: NetworkViewModel, onRequestRecovery: () -> Unit) {
    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Сценарии", "Необязательные наборы правил с предварительным просмотром", Modifier.weight(1f))
        IconButton(onClick = vm::refreshScenarios, enabled = !state.scenarioBusy) { Icon(Icons.Default.Refresh, "Обновить сценарии") }
    } }
    if (state.scenarioBusy && state.scenarioCatalog == null) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
    state.scenarioError?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_32500a15fa), detail = it, isError = true) } }
    state.recoveryState?.takeIf { it.pending }?.let { recovery ->
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_networkscreen_66840f0894), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.recovery_blocked_detail, recovery.planId.orEmpty(), recovery.modules.joinToString { it.scenarioModuleLabel() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRequestRecovery, enabled = !state.scenarioBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_networkscreen_38f90bf620)) }
            }
        } }
    }
    state.scenarioResult?.let { result -> item { StatusNotice(
        if (result.status == "committed") "Сценарий применён" else "Результат требует внимания",
        detail = when (result.status) { "committed" -> stringResource(R.string.ui_networkscreen_9e12e80ae7); "rolled_back" -> stringResource(R.string.ui_networkscreen_5d1e492c1f); "rejected" -> stringResource(R.string.ui_networkscreen_3ce476d3b2); else -> stringResource(R.string.ui_networkscreen_be5772e20e) },
        isError = result.status != "committed",
    ) } }
    state.scenarioCatalog?.let { catalog ->
        item { StatusNotice(stringResource(R.string.ui_networkscreen_b28f9cb23a), detail = listOfNotNull(if (catalog.modules.domains) stringResource(R.string.ui_networkscreen_8fa9edd917) else null, if (catalog.modules.ip) "IP/CIDR" else null, if (catalog.modules.devices) stringResource(R.string.ui_networkscreen_d14eb265d3) else null, if (catalog.modules.services) stringResource(R.string.ui_networkscreen_2a5347d7f8) else null).joinToString().ifBlank { stringResource(R.string.ui_networkscreen_65329f77aa) }) }
        items(catalog.presets, key = { it.id }) { preset ->
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(preset.id.scenarioPresetLabel(preset.label), style = MaterialTheme.typography.titleMedium)
                Text(preset.conditions.scenarioConditionsLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(preset.outcome.scenarioOutcomeLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { vm.reviewScenario(preset.id) }, Modifier.fillMaxWidth(), enabled = !state.scenarioBusy && !state.writesBlocked) { Text(stringResource(R.string.ui_networkscreen_9a506a2a8b)) }
            } }
        }
    }
}

private fun String.scenarioPresetLabel(fallback: String) = when(this) { "russia-direct" -> "Российские ресурсы напрямую"; "okko-direct" -> "Okko напрямую"; "emias-direct" -> "ЕМИАС напрямую"; else -> fallback }
private fun ru.anisimov.keenwg.data.routes.ScenarioConditions.scenarioConditionsLabel(): String = buildList { if(domains.isNotEmpty()) add("домены: ${domains.joinToString()}"); if(suffixes.isNotEmpty()) add("зоны: ${suffixes.joinToString()}"); if(geosites.isNotEmpty()) add("GeoSite: ${geosites.joinToString()}"); if(cidrs.isNotEmpty()) add("IP: ${cidrs.joinToString()}"); if(deviceIds.isNotEmpty()) add("устройства: ${deviceIds.joinToString()}"); if(services.isNotEmpty()) add("сервисы: ${services.joinToString()}") }.joinToString(" · ")
private fun ru.anisimov.keenwg.data.routes.ScenarioOutcome.scenarioOutcomeLabel() = if(mode=="direct") "Напрямую" else "Через VPN${groupId?.let { " · $it" }.orEmpty()}"
private fun String.scenarioModuleLabel() = when(this) { "domains" -> "Доменные правила"; "ip" -> "IP-маршруты"; "devices" -> "Устройства"; "services" -> "Сервисы"; else -> "Маршруты" }
private fun String.scenarioMatcherLabel() = when(this) { "domain" -> "домен"; "suffix" -> "зона"; "geosite" -> "GeoSite"; "cidr" -> "CIDR"; else -> this }

private fun androidx.compose.foundation.lazy.LazyListScope.explainSection(state: NetworkUiState, vm: NetworkViewModel) {
    item { SectionTitle("Почему трафик идёт так", "Разовая проверка без изменения маршрутов") }
    item { RouteExplainForm(state.routeChecking, vm::explainRoute) }
    state.routeError?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_4436729f8c), detail = it, isError = true) } }
    state.routeExplanation?.let { explanation ->
        item {
            val outcome = when {
                explanation.decision.outcome == "direct" -> "Напрямую"
                explanation.decision.outcome.startsWith("group:") -> "Через VPN"
                else -> "Не удалось определить"
            }
            StatusNotice(stringResource(R.string.route_outcome, outcome), detail = stringResource(R.string.route_evidence, explanation.decision.confidence.routeEvidenceLabel()))
        }
        explanation.warnings.forEach { warning -> item { StatusNotice(stringResource(R.string.ui_networkscreen_f348d7ff4e), detail = warning.routeWarningLabel(), isError = true) } }
        items(explanation.steps, key = { "${it.kind}:${it.label}:${it.observedAt}" }) { step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.kind.routeStepLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(step.label, style = MaterialTheme.typography.titleMedium)
                    Text(step.source.routeEvidenceLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (explanation.shadowedRuleIds.isNotEmpty()) item {
                StatusNotice(stringResource(R.string.ui_networkscreen_2c542affde), detail = stringResource(R.string.route_shadowed, explanation.shadowedRuleIds.joinToString()))
        }
    }
}

@Composable
private fun RouteExplainForm(busy: Boolean, onCheck: (String, String, Int, String) -> Unit) {
    var target by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("tcp") }
    var port by remember { mutableStateOf("443") }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_networkscreen_5eb5dfd20a)) }, singleLine = true, enabled = !busy)
            OutlinedTextField(device, { device = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_networkscreen_e8fa921829)) }, singleLine = true, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("tcp", "udp").forEach { value ->
                    OutlinedButton(onClick = { protocol = value }, enabled = !busy && protocol != value) { Text(value.uppercase()) }
                }
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text(stringResource(R.string.ui_networkscreen_90947197f8)) }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Button(onClick = { onCheck(target, protocol, port.toIntOrNull() ?: 0, device) }, Modifier.fillMaxWidth(), enabled = !busy && target.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.ui_networkscreen_be52d3380f))
            }
        }
    }
}

private fun String.routeEvidenceLabel() = if (this == "observed") "Наблюдалось на роутере" else "Вычислено по правилам"
private fun String.routeStepLabel() = when (this) { "dns" -> "DNS"; "rule" -> "Правило"; "selector" -> "Выбранный сервер"; "egress" -> "Выход в интернет"; else -> "Этап" }
private fun String.routeWarningLabel() = when (this) {
    "dns_unavailable" -> "DNS не ответил; часть правил нельзя подтвердить"
    "geo_data_stale" -> "Базы GeoIP/GeoSite устарели"
    "geo_data_age_unknown" -> "Возраст баз GeoIP/GeoSite не определён"
    "geosite_membership_unavailable" -> "Состав GeoSite пока нельзя проверить точно"
    "adapter_partial_failure" -> "Один из движков недоступен; остальные данные показаны"
    "quic_may_bypass" -> "QUIC (UDP/443) может идти другим путём"
    "selector_unavailable" -> "Не удалось подтвердить выбранный сервер"
    else -> "Часть маршрута не удалось подтвердить"
}

private fun androidx.compose.foundation.lazy.LazyListScope.devicesSection(state: NetworkUiState, vm: NetworkViewModel) {
    item { SectionTitle("Устройства", "Статические адреса домашней техники") }
    state.deviceError?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_aab7e69417), detail = it, isError = true) } }
    if (state.devices.isEmpty() && !state.loading) item { StatusNotice(stringResource(R.string.ui_networkscreen_e3b2260459), detail = stringResource(R.string.ui_networkscreen_1dbbe8a201)) }
    items(state.devices, key = NetworkDevice::mac) { DeviceCard(it) { vm.requestStaticEdit(it) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ipSection(state: NetworkUiState, vm: NetworkViewModel) {
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("IP-адреса", "Адреса и подсети, идущие напрямую", Modifier.weight(1f))
            OutlinedButton(onClick = vm::openExclusionEditor, enabled = state.exclusions != null && !state.busy && !state.writesBlocked) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.ui_networkscreen_4fd5b3ee63)) }
        }
    }
    state.exclusionError?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_e9f3bcf7d6), detail = it, isError = true) } }
    val exclusions = state.exclusions
    when {
        exclusions == null && state.exclusionError == null && !state.loading -> item { StatusNotice(stringResource(R.string.ui_networkscreen_e9f3bcf7d6), detail = stringResource(R.string.ui_networkscreen_6f11c69c5d)) }
        exclusions?.entries?.isEmpty() == true -> item { StatusNotice(stringResource(R.string.ui_networkscreen_395e53e0e2), detail = stringResource(R.string.ui_networkscreen_b9b4b37995)) }
        exclusions != null -> items(exclusions.entries, key = NetworkExclusionEntry::id) { entry -> ExclusionCard(entry) { vm.requestDeleteExclusion(entry) } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.domainSection(state: NetworkUiState, vm: NetworkViewModel) {
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Доменные правила", "GeoSite, зоны и отдельные сайты", Modifier.weight(1f))
            OutlinedButton(onClick = vm::openDomainCreate, enabled = state.domains != null && !state.busy && !state.writesBlocked) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.ui_networkscreen_4fd5b3ee63)) }
        }
    }
    state.domainError?.let { item { StatusNotice(stringResource(R.string.ui_networkscreen_a8bfafa797), detail = it, isError = true) } }
    state.domains?.warnings?.forEach { warning -> item { StatusNotice(stringResource(R.string.ui_networkscreen_2e92862ecd), detail = warning, isError = true) } }
    val rules = state.domains?.rules.orEmpty()
    if (rules.isEmpty() && state.domainError == null && !state.loading) item { StatusNotice(stringResource(R.string.ui_networkscreen_d9d5f36869), detail = stringResource(R.string.ui_networkscreen_1bd6688e06)) }
    listOf("direct" to "Напрямую", "vpn" to "Через VPN").forEach { (effect, title) ->
        val group = rules.filter { it.effect == effect }
        if (group.isNotEmpty()) {
            item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(group, key = DomainRule::id) { rule -> DomainRuleCard(rule, { vm.openDomainEdit(rule) }, { vm.requestDomainDelete(rule) }) }
        }
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ExclusionCard(entry: NetworkExclusionEntry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (entry.isProtected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (entry.isProtected) Icons.Default.Lock else Icons.Default.Router, null, tint = if (entry.isProtected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.value, style = MonoLabel)
                Text(if (entry.isProtected) stringResource(R.string.ui_networkscreen_c16a7f6e30) else stringResource(R.string.ui_networkscreen_7a70e219d3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!entry.isProtected) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить исключение ${entry.value}", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable private fun ExclusionAddDialog(busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.ui_networkscreen_9072846785)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.ui_networkscreen_4cd4ff8882)) }, singleLine = true, enabled = !busy); Text(stringResource(R.string.ui_networkscreen_c28e773439), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = !busy && value.isNotBlank()) { Text(stringResource(R.string.ui_networkscreen_71038c53bb)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_networkscreen_8fbe9b75cb)) } })
}

@Composable private fun ExclusionDeleteDialog(entry: NetworkExclusionEntry, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.ui_networkscreen_66460f3c57)) }, text = { Text(stringResource(R.string.exclusion_delete_detail, entry.value)) },
        confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.ui_networkscreen_be99b13612)) } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_networkscreen_8fbe9b75cb)) } })
}

@Composable private fun DeviceCard(device: NetworkDevice, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (device.online) Icons.Default.Wifi else Icons.Default.WifiOff, if (device.online) "Устройство онлайн" else "Устройство офлайн", tint = if (device.online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(device.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (device.staticReservation) Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) { Text(stringResource(R.string.ui_networkscreen_6e61611687), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) } }
                Text(device.ip ?: stringResource(R.string.ui_networkscreen_c83580eaa5), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(device.mac, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Изменить статический IP для ${device.name}") }
        }
    }
}

@Composable private fun StaticIpDialog(device: NetworkDevice, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit, onRemove: () -> Unit) {
    var value by remember(device) { mutableStateOf(device.reservedIp ?: device.ip.orEmpty()) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, icon = { Icon(Icons.Default.Router, null) }, title = { Text(stringResource(R.string.ui_networkscreen_0fa7484f3c)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("${device.name} · ${device.mac}"); OutlinedTextField(value, { value = it }, enabled = !busy, label = { Text(stringResource(R.string.ui_networkscreen_ce77976823)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); Text(stringResource(R.string.ui_networkscreen_449e859f81), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (busy) CircularProgressIndicator(Modifier.size(22.dp)) } },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = !busy && value.isNotBlank()) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.ui_networkscreen_b4d30cae52)) } },
        dismissButton = { Row { if (device.staticReservation) TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.ui_networkscreen_8b9781c793), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_networkscreen_8fbe9b75cb)) } } })
}
