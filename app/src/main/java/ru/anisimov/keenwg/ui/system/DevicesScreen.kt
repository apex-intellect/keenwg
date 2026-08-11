package ru.anisimov.keenwg.ui.system

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.DeviceScope
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.QrImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    vm: DevicesViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.refresh() }
    state.offer?.let { offer ->
        LaunchedEffect(offer.id, offer.expiresAt) {
            val wait = offer.expiresAt.toEpochMilli() - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            vm.expireOfferIfNeeded()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_devicesscreen_b960d16efb)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_devicesscreen_1a9fb1f3cf))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading && !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ui_devicesscreen_603e460bf5))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item { CompanionStatusCard(state) }
            state.error?.let { item { StatusNotice(stringResource(R.string.ui_devicesscreen_87f1858048), detail = it, isError = true) } }
            state.message?.let { item { StatusNotice(it) } }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.access == CapabilityAccess.NONE) {
                item { StatusNotice(stringResource(R.string.ui_devicesscreen_9e572dd462), detail = stringResource(R.string.ui_devicesscreen_10e47c4114)) }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_devicesscreen_25bc483d04), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${state.devices.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.devices, key = DeviceItem::id) { device -> DeviceCard(device, state.access == CapabilityAccess.WRITE && !state.busy, vm::requestRevoke) }
                if (state.access == CapabilityAccess.WRITE) {
                    item {
                        Button(
                            onClick = vm::createViewerOffer,
                            enabled = !state.busy && state.offer == null,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.ui_devicesscreen_1425e75173))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    state.offer?.let { offer -> PairingOfferDialog(offer, state.busy, vm::dismissOffer) }
    state.revokeConfirmation?.let { confirmation ->
        RevokeDialog(confirmation, state.busy, vm::cancelRevoke, vm::confirmRevoke)
    }
}

@Composable
private fun CompanionStatusCard(state: DevicesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("KeenWG companion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("API: ${state.apiState}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusRow("Версия", state.companionVersion.ifBlank { "—" })
            StatusRow("TLS pin", if (state.pinSuffix.isBlank()) "—" else "…${state.pinSuffix}", mono = true)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = if (mono) MonoLabel else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceCard(device: DeviceItem, canRevoke: Boolean, onRevoke: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (device.current) {
                        Surface(
                            modifier = Modifier.padding(start = 8.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) { Text(stringResource(R.string.ui_devicesscreen_153c990b62), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
                Text(scopeLabel(device.scope), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            device.lastUsed?.let { Text(stringResource(R.string.device_last_activity, it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            if (canRevoke) {
                IconButton(onClick = { onRevoke(device.id) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.ui_devicesscreen_de1aec86ab), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PairingOfferDialog(offer: VisiblePairingOffer, busy: Boolean, onDismiss: () -> Unit) {
    SecureWindowEffect()
    BackHandler(enabled = !busy, onBack = onDismiss)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ui_devicesscreen_d41e207576)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrImage(
                    text = offer.qrPayload,
                    contentDescription = stringResource(R.string.ui_devicesscreen_27e38db9bc),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                )
                Text(stringResource(R.string.ui_devicesscreen_07d098a986), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_devicesscreen_c90de22043)) } },
    )
}

@Composable
private fun RevokeDialog(confirmation: RevokeConfirmation, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val current = confirmation.device.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (current && confirmation.finalWarning) stringResource(R.string.ui_devicesscreen_fb921ce49a) else stringResource(R.string.ui_devicesscreen_f6612bd9f1)) },
        text = {
            Text(
                when {
                    current && confirmation.finalWarning -> "После отзыва этот телефон сразу потеряет доступ к companion. Вернуть доступ можно будет только новым приглашением владельца."
                    current -> "Вы выбрали текущий телефон. Потребуется ещё одно явное подтверждение."
                    else -> "${confirmation.device.label} больше не сможет подключаться к этому роутеру через KeenWG."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (current && !confirmation.finalWarning) stringResource(R.string.ui_devicesscreen_a42aee58d6) else stringResource(R.string.ui_devicesscreen_b66927e0fc), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_devicesscreen_8fbe9b75cb)) } },
    )
}

@Composable
private fun SecureWindowEffect() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun scopeLabel(scope: DeviceScope) = when (scope) {
    DeviceScope.OWNER -> "Владелец"
    DeviceScope.OPERATOR -> "Оператор"
    DeviceScope.VIEWER -> "Только просмотр"
}
