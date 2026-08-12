package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
    DisposableEffect(showImport, state.pendingImport) {
        val activity = context.findActivity()
        if (showImport || state.pendingImport != null) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            onDismissRequest = { showImport = false; viewModel.cancelImport() },
            title = { Text(stringResource(R.string.ui_connectionsscreen_b22b73eee2)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${pending.preview.protocol?.name ?: stringResource(R.string.connection_subscription)} · ${pending.preview.host}:${pending.preview.port}")
                    Text("${pending.preview.transport} · ${pending.preview.security}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pending.duplicateWarning) Text(stringResource(R.string.ui_connectionsscreen_e707467eef), color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(importLabel, { importLabel = it }, label = { Text(stringResource(R.string.ui_connectionsscreen_0918b4ba92)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { group -> FilterChip(selectedGroup == group.id, { importGroup = group.id }, { Text(group.label) }) }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.saveImport(importLabel, selectedGroup); showImport = false }) { Text(stringResource(R.string.ui_connectionsscreen_b4d30cae52)) } },
            dismissButton = { TextButton(onClick = { showImport = false; viewModel.cancelImport() }) { Text(stringResource(R.string.ui_connectionsscreen_8fbe9b75cb)) } },
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
}

@Composable
private fun ConnectionsContent(state: ConnectionsUiState, viewModel: ConnectionsViewModel, onAdd: () -> Unit, modifier: Modifier) {
    val catalog = state.catalog ?: return
    val cards = connectionCards(catalog, state.selectedGroupId)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(stringResource(R.string.ui_connectionsscreen_19bb65cdf3)) } }
        state.message?.let { item { StatusNotice(stringResource(R.string.ui_connectionsscreen_225077c6d4), detail = it, isError = it != stringResource(R.string.ui_connectionsscreen_ef05d57959)) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.selectedGroupId == null, onClick = { viewModel.selectGroup(null) }, label = { Text(stringResource(R.string.ui_connectionsscreen_215816bf42)) })
                catalog.groups.forEach { group ->
                    FilterChip(selected = state.selectedGroupId == group.id, onClick = { viewModel.selectGroup(group.id) }, label = { Text(group.label) })
                }
            }
        }
        items(catalog.sources, key = { "source-${it.id}" }) { source ->
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.label, fontWeight = FontWeight.SemiBold)
                        val readiness = if (source.status.name == "STALE") " · нужно обновить" else ""
                Text(stringResource(R.string.connection_source_summary, source.nodeCount, source.adapterId, readiness), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (source.foreign) TextButton(onClick = { viewModel.refreshSource(source.id) }, enabled = source.id !in state.busySources) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_connectionsscreen_603e460bf5))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.ui_connectionsscreen_eef29a39f7), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(cards, key = { it.id }) { card ->
            val test = state.tests[card.id]?.result
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !card.active && card.id !in state.busyNodes) { viewModel.requestActivation(card.id) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (card.active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(card.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(card.engine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (card.active) Icon(Icons.Default.CheckCircle, stringResource(R.string.active), tint = MaterialTheme.colorScheme.tertiary)
                    }
                    if (test != null) Text(if (test.reachable) stringResource(R.string.reachable_latency, test.latencyMs) else stringResource(R.string.ui_connectionsscreen_53ba89acf4), style = MaterialTheme.typography.labelMedium)
                    if (card.testable && !card.active) OutlinedButton(onClick = { viewModel.testNode(card.id) }, enabled = card.id !in state.busyNodes, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (test == null) stringResource(R.string.ui_connectionsscreen_e4424a6df6) else stringResource(R.string.ui_connectionsscreen_dc7dbeb809))
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
