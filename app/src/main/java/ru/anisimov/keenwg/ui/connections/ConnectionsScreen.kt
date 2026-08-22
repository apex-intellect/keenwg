package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.ui.components.StatusNotice
import ru.anisimov.keenwg.data.catalog.CatalogErrorCode
import ru.anisimov.keenwg.data.catalog.CatalogGroup
import ru.anisimov.keenwg.data.catalog.CatalogSource
import ru.anisimov.keenwg.data.catalog.CatalogNodeTest
import ru.anisimov.keenwg.data.catalog.ImportOrigin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onSettings: () -> Unit,
    viewModel: ConnectionsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var importLabel by remember { mutableStateOf("") }
    var importGroup by remember { mutableStateOf("primary") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use(::readImportBytes)
        }.getOrNull()?.let { viewModel.previewImport(it, ImportOrigin.FILE) }
    }
    val qrCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) runCatching {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decodeImportQr(bitmap.width, bitmap.height, pixels)
        }.getOrNull()?.let { viewModel.previewImport(it, ImportOrigin.QR) }
    }
    DisposableEffect(showImport, state.pendingImport, state.editingSubscriptionSourceId) {
        val activity = context.findActivity()
        if (showImport || state.pendingImport != null || state.editingSubscriptionSourceId != null) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(stringResource(R.string.ui_connectionsscreen_fe230e6f5b))
                Text(stringResource(R.string.ui_connectionsscreen_3e9283cbda), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        when {
            state.loading && state.catalog == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            state.setupRequired -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                StatusNotice(stringResource(R.string.ui_connectionsscreen_a0fb0b600a), detail = stringResource(R.string.ui_connectionsscreen_ebac69e24e))
                Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.ui_connectionsscreen_7c8ef82d31)) }
            }
            state.catalog == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                StatusNotice(
                    stringResource(R.string.connections_load_failed_title),
                    detail = if (state.loadError == CatalogErrorCode.UNSUPPORTED_SCHEMA) {
                        stringResource(R.string.connections_schema_unsupported)
                    } else {
                        stringResource(R.string.connections_load_failed_detail)
                    },
                    isError = true,
                )
                Button(
                    onClick = { viewModel.loadCatalog() },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(stringResource(R.string.connections_retry)) }
            }
            else -> ConnectionsContent(state, viewModel, { showImport = true }, Modifier.padding(padding))
        }
    }
    if (showImport && state.pendingImport == null) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.ui_connectionsscreen_19bb65cdf3)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.toByteArray()?.let {
                            viewModel.previewImport(it, ImportOrigin.CLIPBOARD)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_connectionsscreen_051272a3fc)) }
                    OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_connectionsscreen_e3663341c8)) }
                    OutlinedButton(onClick = { qrCamera.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_connectionsscreen_9cd88c358d)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb)) } },
        )
    }
    state.pendingImport?.let { pending ->
        val groups = state.catalog?.groups.orEmpty()
        val selectedGroup = importGroup.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id.orEmpty()
        AlertDialog(
            onDismissRequest = { showImport = false; importLabel = ""; viewModel.cancelImport() },
            title = { Text(stringResource(R.string.ui_connectionsscreen_b22b73eee2)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${pending.preview.protocol?.name ?: stringResource(R.string.connection_subscription)} · ${pending.preview.host}:${pending.preview.port}")
                    Text("${pending.preview.transport} · ${pending.preview.security}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pending.duplicateWarning) Text(stringResource(R.string.ui_connectionsscreen_e707467eef), color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        importLabel,
                        { importLabel = it },
                        label = { Text(stringResource(R.string.connections_import_name_optional)) },
                        supportingText = { Text(stringResource(R.string.connections_import_name_helper)) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { group ->
                            FilterChip(
                                selectedGroup == group.id,
                                { importGroup = group.id },
                                {
                                    Text(
                                        if (groupDisplayKind(group) == GroupDisplayKind.PRIMARY) {
                                            stringResource(R.string.connections_group_primary)
                                        } else {
                                            group.label
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.saveImport(importLabel, selectedGroup); importLabel = ""; showImport = false }) { Text(stringResource(R.string.ui_connectionsscreen_b4d30cae52)) } },
            dismissButton = { TextButton(onClick = { showImport = false; importLabel = ""; viewModel.cancelImport() }) { Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb)) } },
        )
    }
    state.pendingActivation?.let { node ->
        val tested = state.tests[node.id]?.result
        AlertDialog(
            onDismissRequest = viewModel::dismissActivation,
            title = { Text(stringResource(R.string.ui_connectionsscreen_e9cc9b4f17)) },
                text = { Text(stringResource(R.string.connection_switch_preview, node.displayName, node.host, node.port, tested?.latencyMs ?: 0)) },
            confirmButton = { Button(onClick = viewModel::confirmActivation) { Text(stringResource(R.string.ui_connectionsscreen_15fc037dc0)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissActivation) { Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb)) } },
        )
    }
    state.editingSubscriptionSourceId?.let { sourceId ->
        SubscriptionLinkDialog(
            sourceId = sourceId,
            error = state.subscriptionLinkError,
            saving = state.sourceActions[sourceId] == SourceActionState.SAVING_LINK,
            onDismiss = viewModel::dismissSubscriptionEditor,
            onSave = viewModel::saveSubscriptionLink,
        )
    }
}

@Composable
private fun ConnectionsContent(state: ConnectionsUiState, viewModel: ConnectionsViewModel, onAdd: () -> Unit, modifier: Modifier) {
    val catalog = state.catalog ?: return
    val cards = connectionCards(catalog, state.selectedGroupId)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.notice?.let { notice ->
            item {
                StatusNotice(
                    stringResource(R.string.connections_notice_title),
                    detail = connectionNoticeText(notice),
                    isError = notice !is ConnectionNotice.SubscriptionUpdated,
                )
            }
        }
        state.messageResource?.let { messageResource ->
            item {
                StatusNotice(
                    stringResource(R.string.connections_notice_title),
                    detail = stringResource(messageResource),
                    isError = true,
                )
            }
        }
        item {
            ConnectionToolbar(
                groups = catalog.groups,
                selectedGroupId = state.selectedGroupId,
                onSelectGroup = viewModel::selectGroup,
                onAdd = onAdd,
            )
        }
        item(key = "subscription-sources") {
            SubscriptionSources(
                sources = catalog.sources,
                state = state,
                onRefresh = viewModel::refreshSource,
                onEditLink = viewModel::editSubscriptionLink,
                onDelete = viewModel::deleteSource,
            )
        }
        item {
            Text(
                stringResource(R.string.ui_connectionsscreen_eef29a39f7),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(cards, key = { it.id }) { card ->
            val test = state.tests[card.id]?.result
            Card(
                onClick = { viewModel.requestActivation(card.id) },
                enabled = !card.active && card.id !in state.busyNodes,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (card.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (card.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = if (card.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    disabledContentColor = if (card.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            card.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            card.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (card.active) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            if (card.sourceKind == SourceDisplayKind.XKEEN_SUBSCRIPTION) {
                                stringResource(R.string.connections_source_xkeen_node)
                            } else {
                                card.customSourceLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (card.active) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = .9f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ConnectionCardTrailing(
                        card = card,
                        test = test,
                        busy = card.id in state.busyNodes,
                        onTest = { viewModel.testNode(card.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionToolbar(
    groups: List<CatalogGroup>,
    selectedGroupId: String?,
    onSelectGroup: (String?) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedGroupId == null,
                onClick = { onSelectGroup(null) },
                label = { Text(stringResource(R.string.ui_connectionsscreen_215816bf42)) },
            )
            groups.forEach { group ->
                FilterChip(
                    selected = selectedGroupId == group.id,
                    onClick = { onSelectGroup(group.id) },
                    label = {
                        Text(
                            if (groupDisplayKind(group) == GroupDisplayKind.PRIMARY) {
                                stringResource(R.string.connections_group_primary)
                            } else {
                                group.label
                            },
                        )
                    },
                )
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.connections_add_action))
        }
    }
}

@Composable
private fun SubscriptionSources(
    sources: List<CatalogSource>,
    state: ConnectionsUiState,
    onRefresh: (String) -> Unit,
    onEditLink: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuSourceId by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<CatalogSource?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            sources.forEachIndexed { index, source ->
                val action = state.sourceActions[source.id]
                val mode = subscriptionSourceMode(source, state.sourceConfiguration[source.id], action)
                val busy = source.id in state.busySources
                val canEditLink = source.id == XKEEN_SUBSCRIPTION_SOURCE_ID &&
                    state.sourceConfiguration[source.id] != null
                val canDelete = !source.foreign && source.adapterId == "catalog"
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            sourceDisplayTitle(source) ?: stringResource(R.string.connections_source_generic),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subscriptionSourceSubtitle(source, mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (source.status.name == "STALE" || source.status.name == "ERROR") {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                        mode == SubscriptionSourceMode.NEEDS_LINK -> IconButton(onClick = { onEditLink(source.id) }) {
                            Icon(Icons.Default.Link, stringResource(R.string.connections_subscription_add_link))
                        }
                        sourceCanRefresh(source) -> IconButton(onClick = { onRefresh(source.id) }) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.connections_subscription_refresh))
                        }
                    }
                    if (canEditLink || canDelete) {
                        Box {
                            IconButton(onClick = { menuSourceId = source.id }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.connections_source_actions))
                            }
                            DropdownMenu(
                                expanded = menuSourceId == source.id,
                                onDismissRequest = { menuSourceId = null },
                            ) {
                                if (canEditLink) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.connections_subscription_change_link)) },
                                        leadingIcon = { Icon(Icons.Default.Link, null) },
                                        onClick = { menuSourceId = null; onEditLink(source.id) },
                                    )
                                }
                                if (canDelete) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.connections_source_delete)) },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                        onClick = { menuSourceId = null; deleteCandidate = source },
                                    )
                                }
                            }
                        }
                    }
                }
                if (index != sources.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    deleteCandidate?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.connections_source_delete_title)) },
            text = { Text(stringResource(R.string.connections_source_delete_detail, sourceDisplayTitle(source) ?: stringResource(R.string.connections_source_generic))) },
            confirmButton = {
                TextButton(onClick = { deleteCandidate = null; onDelete(source.id) }) {
                    Text(stringResource(R.string.connections_source_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb)) }
            },
        )
    }
}

@Composable
private fun subscriptionSourceSubtitle(source: CatalogSource, mode: SubscriptionSourceMode): String {
    if (mode == SubscriptionSourceMode.CHECKING) return stringResource(R.string.connections_subscription_checking)
    if (mode == SubscriptionSourceMode.NEEDS_LINK) return stringResource(R.string.connections_subscription_missing)
    if (source.status.name == "STALE" || source.status.name == "ERROR") {
        return stringResource(R.string.connections_subscription_stale)
    }
    val context = LocalContext.current
    val parts = mutableListOf(
        pluralStringResource(R.plurals.connections_server_count, source.nodeCount, source.nodeCount),
    )
    val used = subscriptionUsedBytes(source)
    val total = source.subscriptionInfo?.totalBytes
    if (used != null) {
        val usedText = Formatter.formatShortFileSize(context, used)
        parts += when {
            total != null && total > 0 -> stringResource(
                R.string.connections_subscription_traffic,
                usedText,
                Formatter.formatShortFileSize(context, total),
            )
            total == 0L -> stringResource(R.string.connections_subscription_traffic_unlimited, usedText)
            else -> stringResource(R.string.connections_subscription_traffic_used, usedText)
        }
    }
    source.subscriptionInfo?.expiresAt?.takeIf { it > 0 }?.let { expiresAt ->
        parts += stringResource(
            R.string.connections_subscription_expires,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiresAt * 1000)),
        )
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ConnectionCardTrailing(
    card: ConnectionCard,
    test: CatalogNodeTest?,
    busy: Boolean,
    onTest: () -> Unit,
) {
    if (card.active) {
        Icon(Icons.Default.CheckCircle, stringResource(R.string.active), tint = MaterialTheme.colorScheme.onPrimary)
        return
    }
    if (!card.testable) return
    Column(horizontalAlignment = Alignment.End) {
        Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                test != null -> Text(
                    if (test.reachable) stringResource(R.string.reachable_latency, test.latencyMs)
                    else stringResource(R.string.ui_connectionsscreen_53ba89acf4),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onTest, enabled = !busy) {
            Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.connections_test_action))
        }
    }
}

@Composable
private fun SubscriptionLinkDialog(
    sourceId: String,
    error: SubscriptionLinkError?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit,
) {
    var value by remember(sourceId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.connections_subscription_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.connections_subscription_link_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.connections_subscription_link_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        subscriptionLinkErrorText(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val secret = value.toByteArray()
                    value = ""
                    onSave(secret)
                },
                enabled = !saving && value.isNotBlank(),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (saving) R.string.connections_subscription_link_saving
                        else R.string.connections_subscription_link_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb))
            }
        },
    )
}

@Composable
private fun subscriptionLinkErrorText(error: SubscriptionLinkError): String = stringResource(
    when (error) {
        SubscriptionLinkError.INVALID_LINK -> R.string.connections_subscription_link_invalid
        SubscriptionLinkError.PERMISSION_DENIED -> R.string.connections_subscription_link_permission
        SubscriptionLinkError.UNAVAILABLE -> R.string.connections_subscription_link_unavailable
        SubscriptionLinkError.UNSUPPORTED -> R.string.connections_subscription_link_unsupported
    },
)

@Composable
private fun connectionNoticeText(notice: ConnectionNotice): String = when (notice) {
    is ConnectionNotice.SubscriptionUpdated -> stringResource(
        R.string.connections_notice_updated,
        pluralStringResource(R.plurals.connections_server_count, notice.serverCount, notice.serverCount),
    )
    ConnectionNotice.SubscriptionDownloadFailed -> stringResource(R.string.connections_notice_download_failed)
    ConnectionNotice.InvalidSubscription -> stringResource(R.string.connections_notice_invalid)
    ConnectionNotice.RouterBusy -> stringResource(R.string.connections_notice_busy)
    ConnectionNotice.ReloadAndRetry -> stringResource(R.string.connections_notice_reload)
    ConnectionNotice.ResultUnconfirmed -> stringResource(R.string.connections_notice_unconfirmed)
    ConnectionNotice.ActionFailed -> stringResource(R.string.connections_notice_failed)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
