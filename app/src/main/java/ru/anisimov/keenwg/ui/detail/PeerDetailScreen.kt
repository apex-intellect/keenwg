package ru.anisimov.keenwg.ui.detail

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.ui.SELF_PEER_BLOCK_MESSAGE
import ru.anisimov.keenwg.ui.SelfPeerGuard
import ru.anisimov.keenwg.ui.add.normalizePeerName
import ru.anisimov.keenwg.ui.components.ObservedTimeline
import ru.anisimov.keenwg.ui.components.SessionRail
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.peers.peerStatusLabel
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.QrImage
import ru.anisimov.keenwg.ui.util.bytesLabel
import ru.anisimov.keenwg.ui.util.shareConf
import ru.anisimov.keenwg.domain.model.AccessExpiry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailScreen(
    pub: String,
    onBack: () -> Unit,
    onNavigateToPeer: (String) -> Unit = {},
    writable: Boolean = true,
    vm: PeerDetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selfGuard = remember { SelfPeerGuard() }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRotateConfirm by remember { mutableStateOf(false) }
    var showTechnical by remember { mutableStateOf(false) }
    var showDanger by remember { mutableStateOf(false) }
    var showSelfBlocked by remember { mutableStateOf(false) }
    var safetyCheckInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(pub, lifecycle, vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val refreshJob = vm.startForegroundRefresh(pub)
            try {
                refreshJob.join()
            } finally {
                refreshJob.cancel()
            }
        }
    }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is PeerDetailEffect.NavigateToPeer -> {
                    onNavigateToPeer(effect.newPublicKey)
                    vm.acknowledgeNavigation(effect.newPublicKey)
                }
            }
        }
    }

    fun runIfSafe(peer: Peer, action: () -> Job) {
        if (safetyCheckInProgress) return
        safetyCheckInProgress = true
        scope.launch {
            try {
                if (selfGuard.blocks(peer)) showSelfBlocked = true else action().join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar("Не удалось безопасно проверить текущее подключение. Действие отменено.")
            } finally {
                safetyCheckInProgress = false
            }
        }
    }

    val peer = state.peer
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        peer?.name?.ifBlank { "Доступ WireGuard" } ?: "Доступ WireGuard",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.load(pub); vm.refreshStats(pub) },
                        enabled = !state.initialLoading && state.operation == null && !safetyCheckInProgress,
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.access_refresh_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { contentPadding ->
        when {
            state.initialLoading && peer == null -> LoadingDetail(Modifier.padding(contentPadding))
            peer == null -> MissingDetail(
                modifier = Modifier.padding(contentPadding),
                notFound = state.notFound,
                message = state.loadError,
                onBack = onBack,
                onRetry = { vm.load(pub) },
            )
            else -> Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!writable) {
                    StatusNotice(
                        title = stringResource(R.string.access_read_only_title),
                        detail = stringResource(R.string.access_read_only_detail),
                    )
                }
                state.refreshError?.let {
                    StatusNotice(stringResource(R.string.ui_peerdetailscreen_b9f61453dd), detail = it, isError = true)
                }
                PeerStatusHero(peer = peer, stats = state.stats)
                state.accessPolicy?.let { policy ->
                    StatusNotice(
                        title = stringResource(R.string.access_managed_policy),
                        detail = stringResource(R.string.access_policy_summary, policy.allowedNetworks.joinToString(), policy.dnsServers.joinToString().ifBlank { "—" }),
                    )
                    if (policy.expiryAt(state.observedAtEpochSeconds) == AccessExpiry.EXPIRED_REQUIRES_ACTION) {
                        StatusNotice(stringResource(R.string.access_expired_title), detail = stringResource(R.string.access_expired_detail), isError = true)
                    }
                }
                if (state.historySuppressed) {
                    StatusNotice(stringResource(R.string.access_history_disabled_title), detail = stringResource(R.string.access_history_disabled_detail))
                } else {
                    HistorySection(
                        state = state,
                        onSelectRange = { vm.selectRange(pub, it) },
                        onRetry = { vm.refreshStats(pub) },
                    )
                }
                ConfigurationSection(
                    hasOperation = state.operation != null || safetyCheckInProgress,
                    canRename = writable,
                    onShowConf = { vm.showConf(pub) },
                    onRename = { showRename = true },
                )
                if (writable) {
                    CurrentManagementSection(
                        peer = peer,
                        busy = state.operation != null || safetyCheckInProgress,
                        onToggle = {
                            if (peer.enabled) runIfSafe(peer) { vm.setEnabled(pub, false) }
                            else vm.setEnabled(pub, true)
                        },
                    )
                }
                ExpandableTechnical(
                    expanded = showTechnical,
                    onToggle = { showTechnical = !showTechnical },
                    peer = peer,
                )
                if (writable) {
                    DangerZone(
                        expanded = showDanger,
                        onToggle = { showDanger = !showDanger },
                        busy = state.operation != null || safetyCheckInProgress,
                        onRotate = { showRotateConfirm = true },
                        onDelete = { showDeleteConfirm = true },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    state.conf?.let { conf ->
        AlertDialog(
            onDismissRequest = vm::clearConf,
            title = { Text(stringResource(R.string.peer_configuration_title, peer?.name.orEmpty())) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                        QrImage(conf, Modifier.padding(10.dp).size(240.dp))
                    }
                    Text(
                        "QR-код содержит приватный ключ. Показывайте его только владельцу устройства.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareConf(context, peer?.name ?: "wireguard-access", conf) }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text(stringResource(R.string.ui_peerdetailscreen_62bb18ef10))
                }
            },
            dismissButton = { TextButton(onClick = vm::clearConf) { Text(stringResource(R.string.ui_peerdetailscreen_a7a4033657)) } },
        )
    }

    if (showRename && peer != null && writable) {
        var newName by remember(peer.publicKey) { mutableStateOf(peer.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.ui_peerdetailscreen_a6a6b0ec07)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.ui_peerdetailscreen_04e6a146c0)) },
                        singleLine = true,
                    )
                    if (newName.isNotBlank()) {
                    Text(stringResource(R.string.peer_router_name, normalizePeerName(newName)), style = MonoLabel)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && state.operation == null,
                    onClick = {
                        vm.rename(pub, normalizePeerName(newName))
                        showRename = false
                    },
                ) { Text(stringResource(R.string.ui_peerdetailscreen_b4d30cae52)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.ui_peerdetailscreen_8fbe9b75cb)) } },
        )
    }

    if (showDeleteConfirm && peer != null && writable) {
        AlertDialog(
            onDismissRequest = { if (state.operation == null) showDeleteConfirm = false },
            title = { Text(stringResource(R.string.peer_delete_title, peer.name)) },
            text = { Text(stringResource(R.string.ui_peerdetailscreen_cc88e1de5c)) },
            confirmButton = {
                TextButton(
                    enabled = state.operation == null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        runIfSafe(peer) { vm.delete(pub, onBack) }
                    },
                ) { Text(stringResource(R.string.ui_peerdetailscreen_11b30800ac)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.ui_peerdetailscreen_8fbe9b75cb)) } },
        )
    }

    if (showRotateConfirm && peer != null && writable) {
        AlertDialog(
            onDismissRequest = { if (state.operation == null) showRotateConfirm = false },
            title = { Text(stringResource(R.string.ui_peerdetailscreen_09d07af144)) },
            text = {
                Text(stringResource(R.string.ui_peerdetailscreen_cd9088fc7d))
            },
            confirmButton = {
                TextButton(
                    enabled = state.operation == null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showRotateConfirm = false
                        runIfSafe(peer) { vm.regenerate(pub) }
                    },
                ) { Text(stringResource(R.string.ui_peerdetailscreen_361a991268)) }
            },
            dismissButton = { TextButton(onClick = { showRotateConfirm = false }) { Text(stringResource(R.string.ui_peerdetailscreen_8fbe9b75cb)) } },
        )
    }

    if (showSelfBlocked) {
        AlertDialog(
            onDismissRequest = { showSelfBlocked = false },
            title = { Text(stringResource(R.string.ui_peerdetailscreen_22d7f3b33c)) },
            text = { Text(SELF_PEER_BLOCK_MESSAGE) },
            confirmButton = { TextButton(onClick = { showSelfBlocked = false }) { Text(stringResource(R.string.ui_peerdetailscreen_e2ce86b38f)) } },
        )
    }
}

@Composable
private fun PeerStatusHero(peer: Peer, stats: PeerStats?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (peer.online && peer.enabled) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SessionRail(peer.online, peer.enabled, Modifier.height(64.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(
                        if (peer.online && peer.enabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (peer.online && peer.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    )
                    Text(peerStatusLabel(peer), style = MaterialTheme.typography.titleLarge)
                }
                Text(peer.ip ?: stringResource(R.string.ui_peerdetailscreen_c83580eaa5), style = MonoLabel, modifier = Modifier.padding(top = 4.dp))
                stats?.lastOnlineAt?.let {
                    Text(
                        "Последнее наблюдение: ${timestampLabel(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySection(
    state: PeerDetailUiState,
    onSelectRange: (PeerHistoryRange) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_peerdetailscreen_45451fbf75), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Пробелы в данных не считаются отключением",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.collectorLastUpdated?.let {
                    Text(
                        "История обновлена ${timestampLabel(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.collectorRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeChip("24 часа", PeerHistoryRange.DAY, state.selectedRange, onSelectRange)
            RangeChip("7 дней", PeerHistoryRange.WEEK, state.selectedRange, onSelectRange)
            RangeChip("30 дней", PeerHistoryRange.MONTH, state.selectedRange, onSelectRange)
        }
        when {
            state.collectorLoading && state.stats == null -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ui_peerdetailscreen_efb18c179d))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.stats != null -> StatsContent(state.stats)
            state.collectorError != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusNotice(
                    title = stringResource(R.string.ui_peerdetailscreen_a9d6061f62),
                    detail = stringResource(R.string.ui_peerdetailscreen_48af135c57),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.ui_peerdetailscreen_298e41327f)) }
            }
            else -> StatusNotice(
                title = stringResource(R.string.ui_peerdetailscreen_b2a7ed60d8),
                detail = stringResource(R.string.ui_peerdetailscreen_1bacf422f8),
            )
        }
        if (state.collectorError != null && state.stats != null) {
            StatusNotice(
                title = stringResource(R.string.ui_peerdetailscreen_8fb994bbd9),
                detail = state.collectorError,
            )
        }
    }
}

@Composable
private fun RangeChip(
    label: String,
    range: PeerHistoryRange,
    selected: PeerHistoryRange,
    onSelect: (PeerHistoryRange) -> Unit,
) {
    FilterChip(
        selected = range == selected,
        onClick = { onSelect(range) },
        label = { Text(label) },
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun StatsContent(stats: PeerStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ObservedTimeline(stats)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timestampLabel(stats.from), style = MaterialTheme.typography.labelMedium)
                    Text(timestampLabel(stats.to), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (stats.observedSeconds == 0L) {
            StatusNotice(
                title = stringResource(R.string.ui_peerdetailscreen_799c93f90e),
                detail = stringResource(R.string.ui_peerdetailscreen_d55799ca33),
            )
        } else if (stats.coverageRatio < 0.95) {
            StatusNotice(
            title = stringResource(R.string.history_coverage, (stats.coverageRatio * 100).roundToInt()),
                detail = stringResource(R.string.ui_peerdetailscreen_d4641f3f0b),
            )
        }
        if (stats.counterResets > 0) {
            StatusNotice(
                title = stringResource(R.string.ui_peerdetailscreen_9ea0599235),
                detail = stringResource(R.string.history_counter_resets, stats.counterResets),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("В сети", durationLabel(stats.onlineSeconds), Modifier.weight(1f))
            MetricCard("Покрытие", "${(stats.coverageRatio * 100).roundToInt()}%", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("На устройство", bytesLabel(stats.clientDownloadBytes), Modifier.weight(1f))
            MetricCard("С устройства", bytesLabel(stats.clientUploadBytes), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun ConfigurationSection(hasOperation: Boolean, canRename: Boolean, onShowConf: () -> Unit, onRename: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_peerdetailscreen_f67a185fb3), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.access_configuration_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onShowConf, enabled = !hasOperation, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCode2, contentDescription = null)
                Text(stringResource(R.string.ui_peerdetailscreen_1394e56458))
            }
            if (canRename) {
                TextButton(onClick = onRename, enabled = !hasOperation, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(stringResource(R.string.ui_peerdetailscreen_2b30c7105c))
                }
            }
        }
    }
}

@Composable
private fun CurrentManagementSection(peer: Peer, busy: Boolean, onToggle: () -> Unit) {
    OutlinedButton(
        onClick = onToggle,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
        Text(if (peer.enabled) stringResource(R.string.ui_peerdetailscreen_3d48738ce8) else stringResource(R.string.ui_peerdetailscreen_8121f6f155))
    }
}

@Composable
private fun ExpandableTechnical(expanded: Boolean, onToggle: () -> Unit, peer: Peer) {
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Text(stringResource(R.string.ui_peerdetailscreen_cb1ec12927), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TechnicalLine("IP", peer.ip ?: "не назначен")
                    TechnicalLine("Публичный ключ", peer.publicKey)
                    TechnicalLine("Состояние", peerStatusLabel(peer))
                    TechnicalLine("На устройство (с запуска интерфейса)", bytesLabel(peer.clientDownloadBytes))
                    TechnicalLine("С устройства (с запуска интерфейса)", bytesLabel(peer.clientUploadBytes))
                }
            }
        }
    }
}

@Composable
private fun TechnicalLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoLabel, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DangerZone(
    expanded: Boolean,
    onToggle: () -> Unit,
    busy: Boolean,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Text(stringResource(R.string.ui_peerdetailscreen_f173ac574b), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.access_danger_helper),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onRotate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Text(stringResource(R.string.ui_peerdetailscreen_ce0569c6db))
                    }
                    Button(
                        onClick = onDelete,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(stringResource(R.string.ui_peerdetailscreen_eaf03570b0))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingDetail(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.ui_peerdetailscreen_666b4235cf))
        }
    }
}

@Composable
private fun MissingDetail(
    modifier: Modifier,
    notFound: Boolean,
    message: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusNotice(
            title = if (notFound) stringResource(R.string.ui_peerdetailscreen_e6ee0dd136) else stringResource(R.string.ui_peerdetailscreen_d263fe2576),
            detail = if (notFound) stringResource(R.string.ui_peerdetailscreen_38af38c89c) else message,
            isError = !notFound,
        )
        if (!notFound) Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(stringResource(R.string.ui_peerdetailscreen_5189135a61)) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_peerdetailscreen_59df144dcc)) }
    }
}

internal fun durationLabel(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val days = safe / 86_400
    val hours = (safe % 86_400) / 3_600
    val minutes = (safe % 3_600) / 60
    return buildList {
        if (days > 0) add("$days д")
        if (hours > 0) add("$hours ч")
        if (minutes > 0 || isEmpty()) add("$minutes мин")
    }.joinToString(" ")
}

private fun timestampLabel(epochSeconds: Long): String =
    SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1_000L))
