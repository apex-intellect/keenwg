package ru.anisimov.keenwg.ui.xkeen

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.data.xkeen.XkeenActiveNode
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenNodeDiagnostic
import ru.anisimov.keenwg.data.xkeen.XkeenDiagnosticStatus
import ru.anisimov.keenwg.data.xkeen.XkeenOperationResult
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.data.store.serverIdentity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XkeenScreen(
    onSetupCompanion: () -> Unit,
    viewModel: XkeenViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ui_xkeenscreen_1f8c321147))
                        Text(
                            "Ручной выбор страны",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        when {
            state.loading && state.status == null -> LoadingRoute(Modifier.padding(scaffoldPadding))
            state.needsSetup -> SetupRoute(
                onSetupCompanion = onSetupCompanion,
                modifier = Modifier.padding(scaffoldPadding),
            )
            else -> RouteContent(
                state = state,
                onRefresh = viewModel::refreshSubscription,
                onRetry = viewModel::loadStatus,
                onDiagnostics = viewModel::runDiagnostics,
                onSelect = viewModel::requestSelect,
                onFavorite = viewModel::toggleFavorite,
                onFavoritesOnly = viewModel::setFavoritesOnly,
                modifier = Modifier.padding(scaffoldPadding),
            )
        }
    }

    state.pendingNode?.let { target ->
        SelectionDialog(
            current = state.status?.active,
            target = target,
            busy = state.busy,
            onDismiss = viewModel::dismissSelection,
            onConfirm = viewModel::confirmSelection,
        )
    }
}

@Composable
private fun RouteContent(
    state: XkeenUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDiagnostics: () -> Unit,
    onSelect: (XkeenNode) -> Unit,
    onFavorite: (XkeenNode) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        status?.active?.takeIf { showExceptionalActiveCard(status) }?.let { active ->
            item { ActiveRouteCard(active) }
        }
        if (state.staleStatus != null) {
            item {
                StatusNotice(stringResource(R.string.ui_xkeenscreen_b939a9e9d1), detail = stringResource(R.string.ui_xkeenscreen_0cffc5c72a), isError = true)
            }
        }
        if (status?.active?.missingFromSubscription == true) {
            item {
                StatusNotice(stringResource(R.string.ui_xkeenscreen_f723a00d13), detail = stringResource(R.string.ui_xkeenscreen_acfa7ce915), isError = true)
            }
        }
        if (state.blocksMutation) {
            item {
                StatusNotice(stringResource(R.string.ui_xkeenscreen_d33acb5d9b), detail = stringResource(R.string.ui_xkeenscreen_0fd6014bb4), isError = true)
            }
        }
        state.message?.let { message ->
            item {
                val safe = state.operation?.result == XkeenOperationResult.SUCCESS
                StatusNotice(if (safe) stringResource(R.string.ui_xkeenscreen_ef05d57959) else stringResource(R.string.ui_xkeenscreen_f2baf7459b), detail = message, isError = !safe)
            }
        }
        item {
            val refreshSubscriptionLabel = stringResource(R.string.ui_xkeenscreen_84f94e80b2)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRefresh,
                    enabled = status != null && !state.busy && !state.blocksMutation,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = refreshSubscriptionLabel },
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.busy) stringResource(R.string.ui_xkeenscreen_d2606cebc8) else stringResource(R.string.ui_xkeenscreen_ce7e3fd182))
                }
                Text(
                    lastRefreshLabel(status?.subscription?.refreshedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onDiagnostics,
                    enabled = status != null && !state.diagnosticsBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.diagnosticsBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.diagnosticsBusy) stringResource(R.string.ui_xkeenscreen_90028924db) else stringResource(R.string.ui_xkeenscreen_5579e6b769))
                }
                if (state.staleStatus != null || status == null) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_xkeenscreen_40f9ba8446)) }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_xkeenscreen_e307e02b5d), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.favoritesOnly, onClick = { onFavoritesOnly(false) }, label = { Text(stringResource(R.string.ui_xkeenscreen_215816bf42)) })
                    FilterChip(selected = state.favoritesOnly, onClick = { onFavoritesOnly(true) }, label = { Text(stringResource(R.string.ui_xkeenscreen_6f9ee3bf33)) })
                }
            }
        }
        val visibleNodes = status?.subscription?.nodes.orEmpty().filter { node ->
            !state.favoritesOnly || serverIdentity(node.host, node.port) in state.favorites
        }
        if (visibleNodes.isEmpty()) {
            item { StatusNotice(stringResource(R.string.ui_xkeenscreen_b600944340), detail = stringResource(R.string.ui_xkeenscreen_6a58b50a7c)) }
        } else {
            items(visibleNodes, key = XkeenNode::id) { node ->
                val identity = serverIdentity(node.host, node.port)
                NodeRouteCard(
                    node = node,
                    active = node.id == status?.active?.id,
                    enabled = !state.busy && !state.blocksMutation,
                    diagnostic = state.diagnostics[node.id],
                    favorite = identity in state.favorites,
                    recent = identity in state.recent,
                    onFavorite = { onFavorite(node) },
                    onClick = { onSelect(node) },
                )
            }
        }
    }
}

@Composable
private fun ActiveRouteCard(active: XkeenActiveNode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteRail(active = true)
            Text(active.flag ?: "◎", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_xkeenscreen_51d102ff33), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(cleanNodeName(active.flag, active.displayName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${active.host}:${active.port} · ${active.resolvedIp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.CheckCircle, "Маршрут активен", tint = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun NodeRouteCard(
    node: XkeenNode,
    active: Boolean,
    enabled: Boolean,
    diagnostic: XkeenNodeDiagnostic?,
    favorite: Boolean,
    recent: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val cardColor = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled && !active, onClickLabel = stringResource(R.string.select_node, node.displayName), onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteRail(active)
            Text(node.flag ?: "◎", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cleanNodeName(node.flag, node.displayName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (active) ActiveBadge()
                }
                Text(
                    nodeSubtitle(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                warningLabel(node)?.let { warning ->
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(15.dp))
                        Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                diagnostic?.let {
                    Text(
                        diagnosticLabel(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.status == XkeenDiagnosticStatus.REACHABLE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (recent) {
                    Text(stringResource(R.string.ui_xkeenscreen_53944be764), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (favorite) stringResource(R.string.ui_xkeenscreen_334c3299a8) else stringResource(R.string.ui_xkeenscreen_2a6b5f7278),
                    tint = if (favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun diagnosticLabel(value: XkeenNodeDiagnostic): String = when (value.status) {
    XkeenDiagnosticStatus.REACHABLE -> "Доступен · ${value.connectMs} мс"
    XkeenDiagnosticStatus.UNREACHABLE -> "Недоступен"
    XkeenDiagnosticStatus.TIMEOUT -> "Таймаут подключения"
    XkeenDiagnosticStatus.DNS_ERROR -> "Ошибка DNS"
}

@Composable
private fun RouteRail(active: Boolean) {
    Box(
        Modifier
            .width(4.dp)
            .height(56.dp)
            .background(if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Text(stringResource(R.string.ui_xkeenscreen_c22462bc76), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun SelectionDialog(
    current: XkeenActiveNode?,
    target: XkeenNode,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Language, contentDescription = null) },
        title = { Text(stringResource(R.string.ui_xkeenscreen_0dc7eba110)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${current?.flag.orEmpty()} ${current?.let { cleanNodeName(it.flag, it.displayName) } ?: stringResource(R.string.xkeen_current_route)}  →  ${target.flag.orEmpty()} ${cleanNodeName(target.flag, target.displayName)}")
                Text(stringResource(R.string.ui_xkeenscreen_e1bd32a0d4))
                warningLabel(target)?.let { StatusNotice(stringResource(R.string.ui_xkeenscreen_233a8e993a), detail = it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) { Text(if (busy) stringResource(R.string.ui_xkeenscreen_62ed32eb22) else stringResource(R.string.ui_xkeenscreen_6c5a2b0849)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_xkeenscreen_8fbe9b75cb)) }
        },
    )
}

@Composable
private fun LoadingRoute(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.ui_xkeenscreen_d6138fa03b), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun SetupRoute(onSetupCompanion: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ui_xkeenscreen_72909eb470), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp))
        Text(
            stringResource(R.string.xkeen_pairing_required_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        Button(onClick = onSetupCompanion, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_xkeenscreen_7c8ef82d31)) }
    }
}
