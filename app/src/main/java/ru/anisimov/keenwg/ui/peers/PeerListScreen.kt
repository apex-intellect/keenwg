package ru.anisimov.keenwg.ui.peers

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.ui.SELF_PEER_BLOCK_MESSAGE
import ru.anisimov.keenwg.ui.SelfPeerGuard
import ru.anisimov.keenwg.ui.components.SessionRail
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.ui.theme.MonoLabel
import ru.anisimov.keenwg.ui.util.bytesLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onPeer: (String) -> Unit,
    writable: Boolean = true,
    vm: PeerListViewModel = viewModel(),
) {
    val newAccessLabel = stringResource(R.string.ui_peerlistscreen_d820ccbe3d)
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selfGuard = remember { SelfPeerGuard() }
    var blockedPeer by remember { mutableStateOf<Peer?>(null) }
    var selfKeys by remember { mutableStateOf(emptySet<String>()) }
    var safetyBusyKeys by remember { mutableStateOf(emptySet<String>()) }
    val refreshErrorText = state.refreshError?.let { peerListErrorText(it) }

    LaunchedEffect(lifecycle, vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val refreshJob = vm.startForegroundRefresh()
            try {
                refreshJob.join()
            } finally {
                refreshJob.cancel()
            }
        }
    }
    LaunchedEffect(state.refreshError, refreshErrorText) {
        if (state.peers.isNotEmpty()) refreshErrorText?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(state.peers, state.lastUpdated) {
        selfKeys = runCatching { selfGuard.unsafeKeys(state.peers) }.getOrDefault(emptySet())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.access_title))
                        Text(
                            stringResource(R.string.access_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.initialLoading) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.access_refresh_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (writable) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(newAccessLabel) },
                    modifier = Modifier.semantics { contentDescription = newAccessLabel },
                )
            }
        },
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding).fillMaxSize()) {
            when {
                state.initialLoading -> PeerListSkeleton()
                state.peers.isEmpty() && state.refreshError != null -> InitialLoadError(
                    message = refreshErrorText.orEmpty(),
                    onRetry = vm::refresh,
                    onSettings = onSettings,
                )
                state.peers.isEmpty() -> EmptyPeers(writable = writable, onAdd = onAdd)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!writable) item {
                        StatusNotice(
                            title = stringResource(R.string.access_read_only_title),
                            detail = stringResource(R.string.access_read_only_detail),
                        )
                    }
                    item {
                        RouterFreshnessCard(state)
                    }
                    items(state.peers, key = Peer::publicKey) { peer ->
                        PeerRow(
                            peer = peer,
                            writable = writable,
                            isSelf = peer.publicKey in selfKeys,
                            busy = peer.publicKey in state.busyKeys || peer.publicKey in safetyBusyKeys,
                            onClick = { onPeer(peer.publicKey) },
                            onSetEnabled = { enabled ->
                                if (enabled) {
                                    vm.setEnabled(peer.publicKey, true)
                                } else {
                                    scope.launch {
                                        if (peer.publicKey in safetyBusyKeys) return@launch
                                        safetyBusyKeys = safetyBusyKeys + peer.publicKey
                                        try {
                                            if (selfGuard.blocks(peer)) blockedPeer = peer
                                            else vm.setEnabled(peer.publicKey, false).join()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            snackbar.showSnackbar("Не удалось безопасно проверить текущее подключение. Действие отменено.")
                                        } finally {
                                            safetyBusyKeys = safetyBusyKeys - peer.publicKey
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    blockedPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { blockedPeer = null },
            title = { Text(stringResource(R.string.ui_peerlistscreen_22d7f3b33c)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(peer.name.ifBlank { stringResource(R.string.ui_peerlistscreen_53e577f59c) })
                    Text(SELF_PEER_BLOCK_MESSAGE)
                }
            },
            confirmButton = { TextButton(onClick = { blockedPeer = null }) { Text(stringResource(R.string.ui_peerlistscreen_e2ce86b38f)) } },
        )
    }
}

@Composable
private fun RouterFreshnessCard(state: PeerListUiState) {
    val enabled = state.peers.count(Peer::enabled)
    val online = state.peers.count { it.enabled && it.online }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.peers_online_summary, online, enabled), style = MaterialTheme.typography.titleSmall)
                    Text(
                        updatedAtLabel(state.lastUpdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (state.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: Peer,
    writable: Boolean,
    isSelf: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = !busy, onClickLabel = stringResource(R.string.open_peer, peerDisplayName(peer)), onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (peer.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, end = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionRail(peer.online, peer.enabled)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peerDisplayName(peer),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSelf) SelfBadge()
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PeerStateIcon(peer)
                    Text(
                        peerStatusLabel(peer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(peer),
                    )
                }
                Text(
                    peer.ip ?: stringResource(R.string.access_ip_unassigned),
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (peer.clientDownloadBytes > 0 || peer.clientUploadBytes > 0) {
                    Text(
                        stringResource(
                            R.string.access_traffic_summary,
                            bytesLabel(peer.clientDownloadBytes),
                            bytesLabel(peer.clientUploadBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (writable) PeerActions(peer = peer, busy = busy, onSetEnabled = onSetEnabled)
        }
    }
}

@Composable
private fun PeerActions(peer: Peer, busy: Boolean, onSetEnabled: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.access_peer_actions_description, peerDisplayName(peer)))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (peer.enabled) stringResource(R.string.ui_peerlistscreen_bb5bdd176c) else stringResource(R.string.ui_peerlistscreen_a54eaf3785)) },
                onClick = {
                    expanded = false
                    onSetEnabled(!peer.enabled)
                },
                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SelfBadge() {
    Surface(
        modifier = Modifier.padding(start = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(stringResource(R.string.ui_peerlistscreen_e15d947af3), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun PeerStateIcon(peer: Peer) {
    val icon = when {
        !peer.enabled -> Icons.Default.PowerSettingsNew
        peer.online -> Icons.Default.Wifi
        peer.handshake.kind == HandshakeKind.UNKNOWN || peer.handshake.kind == HandshakeKind.INVALID -> Icons.AutoMirrored.Outlined.HelpOutline
        else -> Icons.Default.WifiOff
    }
    Icon(icon, contentDescription = null, tint = statusColor(peer), modifier = Modifier.size(17.dp))
}

@Composable
private fun statusColor(peer: Peer): Color = when {
    !peer.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
    peer.online -> MaterialTheme.colorScheme.tertiary
    peer.handshake.kind == HandshakeKind.UNKNOWN || peer.handshake.kind == HandshakeKind.INVALID -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
internal fun peerStatusLabel(peer: Peer): String = when (peerConnectionState(peer)) {
    PeerConnectionState.CONNECTED_NOW -> stringResource(R.string.access_status_connected_now)
    PeerConnectionState.RECENTLY_CONNECTED -> stringResource(R.string.access_status_recently_connected)
    PeerConnectionState.ACCESS_DISABLED -> stringResource(R.string.access_status_disabled)
    PeerConnectionState.NEVER_CONNECTED -> stringResource(R.string.access_status_never_connected)
    PeerConnectionState.NO_CONNECTION_DATA -> stringResource(R.string.access_status_no_data)
}

private fun peerDisplayName(peer: Peer): String = peer.name.ifBlank { peer.publicKey.take(10) + "…" }

private fun updatedAtLabel(updatedAt: Long?): String {
    if (updatedAt == null) return "Ещё не обновлялось"
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAt))
    return "Данные роутера обновлены в $time"
}

@Composable
private fun PeerListSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.ui_peerlistscreen_762c10f506), style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(3) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 52.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.55f).height(17.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
                        Box(Modifier.fillMaxWidth(0.75f).height(13.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialLoadError(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusNotice(
            title = stringResource(R.string.ui_peerlistscreen_1d09b54809),
            detail = message,
            isError = true,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.ui_peerlistscreen_5189135a61)) }
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_peerlistscreen_7c8ef82d31)) }
    }
}

@Composable
private fun EmptyPeers(writable: Boolean, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ui_peerlistscreen_b100b551e4), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            stringResource(if (writable) R.string.access_empty_detail else R.string.access_empty_read_only_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (writable) Button(onClick = onAdd, modifier = Modifier.padding(top = 18.dp)) { Text(stringResource(R.string.ui_peerlistscreen_3adaf4bd83)) }
    }
}

@Composable
private fun peerListErrorText(error: PeerListError): String = stringResource(
    when (error) {
        PeerListError.UNAVAILABLE -> R.string.access_error_unavailable
        PeerListError.UPDATE_REQUIRED -> R.string.access_error_update_required
        PeerListError.RECONNECT_REQUIRED -> R.string.access_error_reconnect_required
    },
)
