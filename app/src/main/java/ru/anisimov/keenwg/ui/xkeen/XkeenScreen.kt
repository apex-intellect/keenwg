package ru.anisimov.keenwg.ui.xkeen

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSize

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import ru.anisimov.keenwg.ui.components.KeenButton as Button
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.anisimov.keenwg.ui.components.KeenOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ru.anisimov.keenwg.ui.components.KeenTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                            stringResource(R.string.xkeen_manual_country),
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
        contentPadding = PaddingValues(start = KeenSpacing.screen, top = KeenSpacing.group, end = KeenSpacing.screen, bottom = KeenScale.dp32),
        verticalArrangement = Arrangement.spacedBy(KeenScale.dp10),
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
        state.messageResource?.let { messageResource ->
            item {
                val safe = state.operation?.result == XkeenOperationResult.SUCCESS
                StatusNotice(
                    if (safe) stringResource(R.string.ui_xkeenscreen_ef05d57959) else stringResource(R.string.ui_xkeenscreen_f2baf7459b),
                    detail = stringResource(messageResource),
                    isError = !safe,
                )
            }
        }
        item {
            val refreshSubscriptionLabel = stringResource(R.string.ui_xkeenscreen_84f94e80b2)
            Column(verticalArrangement = Arrangement.spacedBy(KeenSpacing.inline)) {
                Button(
                    onClick = onRefresh,
                    enabled = status != null && !state.busy && !state.blocksMutation,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = refreshSubscriptionLabel },
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(KeenSpacing.card), strokeWidth = KeenScale.dp2, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(KeenScale.dp10))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(KeenSpacing.inline))
                    }
                    Text(if (state.busy) stringResource(R.string.ui_xkeenscreen_d2606cebc8) else stringResource(R.string.ui_xkeenscreen_ce7e3fd182))
                }
                Text(
                    lastRefreshText(status?.subscription?.refreshedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onDiagnostics,
                    enabled = status != null && !state.diagnosticsBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.diagnosticsBusy) {
                        CircularProgressIndicator(Modifier.size(KeenSpacing.card), strokeWidth = KeenScale.dp2)
                        Spacer(Modifier.width(KeenScale.dp10))
                    } else {
                        Icon(Icons.Outlined.Speed, contentDescription = null)
                        Spacer(Modifier.width(KeenSpacing.inline))
                    }
                    Text(if (state.diagnosticsBusy) stringResource(R.string.ui_xkeenscreen_90028924db) else stringResource(R.string.ui_xkeenscreen_5579e6b769))
                }
                if (state.staleStatus != null || status == null) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_xkeenscreen_40f9ba8446)) }
                }
            }
        }
        item {
            Column(Modifier.padding(top = KeenSpacing.inline), verticalArrangement = Arrangement.spacedBy(KeenSpacing.inline)) {
                Text(stringResource(R.string.ui_xkeenscreen_e307e02b5d), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(KeenSpacing.inline)) {
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
        Row(Modifier.fillMaxWidth().padding(KeenSpacing.item), verticalAlignment = Alignment.CenterVertically) {
            RouteRail(active = true)
            Text(active.flag ?: "◎", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = KeenScale.dp14))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_xkeenscreen_51d102ff33), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    cleanNodeName(active.flag, active.displayName, stringResource(R.string.xkeen_server_fallback)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${active.host}:${active.port} · ${active.resolvedIp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.CheckCircle,
                stringResource(R.string.xkeen_route_active_description),
                tint = MaterialTheme.colorScheme.tertiary,
            )
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
        onClick = onClick,
        enabled = enabled && !active,
        modifier = Modifier.fillMaxWidth().heightIn(min = KeenScale.dp106),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Row(Modifier.fillMaxWidth().padding(KeenScale.dp14), verticalAlignment = Alignment.CenterVertically) {
            RouteRail(active)
            Text(node.flag ?: "◎", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = KeenSpacing.group))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cleanNodeName(node.flag, node.displayName, stringResource(R.string.xkeen_server_fallback)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) ActiveBadge()
                }
                Text(
                    nodeSubtitle(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = KeenScale.dp3),
                )
                Box(Modifier.fillMaxWidth().height(KeenSize.icon), contentAlignment = Alignment.CenterStart) {
                    when {
                        diagnostic != null -> Text(
                            diagnosticLabel(diagnostic),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (diagnostic.status == XkeenDiagnosticStatus.REACHABLE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        hasConfigurationWarning(node) -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(KeenScale.dp15))
                            Text(
                                stringResource(R.string.xkeen_configuration_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = KeenSpacing.tight),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        recent -> Text(
                            stringResource(R.string.ui_xkeenscreen_53944be764),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (favorite) stringResource(R.string.ui_xkeenscreen_334c3299a8) else stringResource(R.string.ui_xkeenscreen_2a6b5f7278),
                    tint = if (favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun diagnosticLabel(value: XkeenNodeDiagnostic): String = stringResource(
    when (value.status) {
        XkeenDiagnosticStatus.REACHABLE -> R.string.xkeen_diagnostic_reachable
        XkeenDiagnosticStatus.UNREACHABLE -> R.string.xkeen_diagnostic_unreachable
        XkeenDiagnosticStatus.TIMEOUT -> R.string.xkeen_diagnostic_timeout
        XkeenDiagnosticStatus.DNS_ERROR -> R.string.xkeen_diagnostic_dns
    },
    value.connectMs,
)

@Composable
private fun RouteRail(active: Boolean) {
    Box(
        Modifier
            .width(KeenSpacing.tight)
            .height(KeenScale.dp56)
            .background(if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline, RoundedCornerShape(KeenSpacing.tight)),
    )
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape = ContinuousShape(KeenRadius.card),
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Text(stringResource(R.string.ui_xkeenscreen_c22462bc76), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = KeenSpacing.inline, vertical = KeenScale.dp3))
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
        icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
        title = { Text(stringResource(R.string.ui_xkeenscreen_0dc7eba110)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KeenScale.dp10)) {
                val fallback = stringResource(R.string.xkeen_server_fallback)
                Text("${current?.flag.orEmpty()} ${current?.let { cleanNodeName(it.flag, it.displayName, fallback) } ?: stringResource(R.string.xkeen_current_route)}  →  ${target.flag.orEmpty()} ${cleanNodeName(target.flag, target.displayName, fallback)}")
                Text(stringResource(R.string.ui_xkeenscreen_e1bd32a0d4))
                if (hasConfigurationWarning(target)) {
                    StatusNotice(
                        stringResource(R.string.ui_xkeenscreen_233a8e993a),
                        detail = stringResource(R.string.xkeen_configuration_warning),
                    )
                }
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
private fun lastRefreshText(epochSeconds: Long?): String {
    if (epochSeconds == null) return stringResource(R.string.xkeen_subscription_never_updated)
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        R.string.xkeen_subscription_updated_at,
        formatRefreshTimestamp(epochSeconds, locale = locale),
    )
}

@Composable
private fun LoadingRoute(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(KeenSpacing.section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.ui_xkeenscreen_d6138fa03b), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = KeenSpacing.item))
    }
}

@Composable
private fun SetupRoute(onSetupCompanion: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(KeenSpacing.section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(KeenScale.dp44), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ui_xkeenscreen_72909eb470), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = KeenScale.dp14))
        Text(
            stringResource(R.string.xkeen_pairing_required_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = KeenSpacing.inline, bottom = KeenSpacing.card),
        )
        Button(onClick = onSetupCompanion, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_xkeenscreen_7c8ef82d31)) }
    }
}
