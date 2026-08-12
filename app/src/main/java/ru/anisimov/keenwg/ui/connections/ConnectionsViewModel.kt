package ru.anisimov.keenwg.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.catalog.CatalogException
import ru.anisimov.keenwg.data.catalog.CatalogGateway
import ru.anisimov.keenwg.data.catalog.CatalogOperation
import ru.anisimov.keenwg.data.catalog.CatalogSourceDraft
import ru.anisimov.keenwg.data.catalog.ImportDraftGateway
import ru.anisimov.keenwg.data.catalog.ImportOrigin
import ru.anisimov.keenwg.data.catalog.ImportParser
import ru.anisimov.keenwg.data.store.ActiveRouterProfile

class ConnectionsViewModel(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val gateway: CatalogGateway,
    private val drafts: ImportDraftGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    constructor() : this(ServiceLocator.routerProfileStore.activeProfile, ServiceLocator.catalogGateway, ServiceLocator.importDraftStore)

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    init { loadCatalog() }

    fun loadCatalog(): Job = viewModelScope.launch {
        val active = activeProfile.first()
        if (active == null || active.profile.companionUrl.isBlank() || active.secrets.companionToken.isBlank()) {
            _state.value = ConnectionsUiState(loading = false, setupRequired = true)
            return@launch
        }
        _state.value = _state.value.copy(loading = true, loadError = null, message = null, notice = null)
        runCatching { gateway.snapshot(active.profile, active.secrets.companionToken) }
            .onSuccess { catalog ->
                val selected = _state.value.selectedGroupId?.takeIf { id -> catalog.groups.any { it.id == id } }
                _state.value = _state.value.copy(loading = false, loadError = null, catalog = catalog, selectedGroupId = selected)
            }
            .onFailure { failure ->
                _state.value = _state.value.copy(
                    loading = false,
                    loadError = (failure as? CatalogException)?.code,
                    message = null,
                    notice = null,
                )
            }
    }

    fun selectGroup(id: String?) { _state.value = _state.value.copy(selectedGroupId = id) }

    fun previewImport(source: ByteArray, origin: ImportOrigin) {
        val parserInput = source.copyOf()
        try {
            val preview = ImportParser.preview(parserInput, origin)
            drafts.put(source)
            val duplicate = _state.value.catalog?.nodes.orEmpty().any { node ->
                node.host.equals(preview.host, ignoreCase = true) && node.port == preview.port &&
                    (preview.protocol == null || node.protocol.name == preview.protocol.name)
            }
            _state.value = _state.value.copy(pendingImport = PendingImport(preview, duplicate), message = null, notice = null)
        } catch (_: Exception) {
            source.fill(0)
            drafts.clear()
            _state.value = _state.value.copy(pendingImport = null, message = "Не удалось распознать источник")
        }
    }

    fun cancelImport() {
        drafts.clear()
        _state.value = _state.value.copy(pendingImport = null)
    }

    fun saveImport(label: String, groupId: String): Job = viewModelScope.launch {
        val pending = _state.value.pendingImport ?: return@launch
        val catalog = _state.value.catalog ?: return@launch
        if (label.isBlank() || catalog.groups.none { it.id == groupId }) {
            _state.value = _state.value.copy(message = "Выберите группу и название")
            return@launch
        }
        val source = drafts.take()
        if (source == null) {
            _state.value = _state.value.copy(pendingImport = null, message = "Черновик импорта истёк — добавьте источник снова")
            return@launch
        }
        val active = activeProfile.first() ?: return@launch
        try {
            val operation = gateway.saveSource(
                active.profile, active.secrets.companionToken, catalog.stateVersion, keyFactory(),
                CatalogSourceDraft(groupId, pending.preview.sourceKind, label.trim(), "catalog"), source,
            )
            applyOperation(operation)
            _state.value = _state.value.copy(pendingImport = null)
        } catch (_: Exception) {
            _state.value = _state.value.copy(pendingImport = null, message = "Не удалось сохранить источник")
        } finally {
            source.fill(0)
        }
    }

    fun refreshSource(sourceId: String): Job = mutateSource(sourceId) { profile, token, version ->
        gateway.refreshSource(profile.profile, token, version, keyFactory(), sourceId)
    }

    fun testNode(nodeId: String): Job = viewModelScope.launch {
        val current = _state.value
        val catalog = current.catalog ?: return@launch
        val node = catalog.nodes.firstOrNull { it.id == nodeId && it.testable } ?: return@launch
        if (nodeId in current.busyNodes) return@launch
        val active = activeProfile.first() ?: return@launch
        _state.value = current.copy(busyNodes = current.busyNodes + nodeId, message = null)
        try {
            val operation = gateway.testNode(active.profile, active.secrets.companionToken, catalog.stateVersion, keyFactory(), nodeId)
            val nextCatalog = operation.catalog ?: catalog
            val test = operation.test
            val tests = if (test != null && test.nodeId == nodeId) {
                _state.value.tests + (nodeId to TestedNode(test, nextCatalog.stateVersion, nowMillis()))
            } else _state.value.tests
            _state.value = _state.value.copy(catalog = nextCatalog, tests = tests, message = null, notice = null)
        } catch (_: Exception) {
            reconcile("Не удалось проверить узел")
        }
        _state.value = _state.value.copy(busyNodes = _state.value.busyNodes - nodeId)
    }

    fun requestActivation(nodeId: String) {
        val current = _state.value
        val catalog = current.catalog ?: return
        val node = catalog.nodes.firstOrNull { it.id == nodeId && it.activatable && !it.active } ?: return
        val test = current.tests[nodeId]
        val valid = test?.result?.reachable == true && test.catalogVersion == catalog.stateVersion &&
            nowMillis() - test.receivedAtMillis <= TEST_TTL_MILLIS
        _state.value = if (valid) current.copy(pendingActivation = node, message = null)
        else current.copy(message = "Сначала проверьте доступность этого узла")
    }

    fun dismissActivation() { _state.value = _state.value.copy(pendingActivation = null) }

    fun confirmActivation(): Job = viewModelScope.launch {
        val current = _state.value
        val target = current.pendingActivation ?: return@launch
        val catalog = current.catalog ?: return@launch
        val active = activeProfile.first() ?: return@launch
        _state.value = current.copy(busyNodes = current.busyNodes + target.id, message = null)
        try {
            applyOperation(gateway.activateNode(active.profile, active.secrets.companionToken, catalog.stateVersion, keyFactory(), target.id))
        } catch (_: Exception) {
            reconcile("Ответ потерян — состояние перечитано")
        }
        _state.value = _state.value.copy(pendingActivation = null, busyNodes = _state.value.busyNodes - target.id)
    }

    private fun mutateSource(sourceId: String, call: suspend (ActiveRouterProfile, String, ULong) -> CatalogOperation): Job = viewModelScope.launch {
        val current = _state.value
        val catalog = current.catalog ?: return@launch
        if (sourceId in current.busySources) return@launch
        val active = activeProfile.first() ?: return@launch
        _state.value = current.copy(busySources = current.busySources + sourceId, message = null, notice = null)
        try {
            val operation = call(active, active.secrets.companionToken, catalog.stateVersion)
            val serverCount = operation.catalog?.sources
                ?.firstOrNull { it.id == sourceId }
                ?.nodeCount
                ?: catalog.sources.firstOrNull { it.id == sourceId }?.nodeCount
                ?: 0
            applyOperation(operation, connectionOperationNotice(operation.result, operation.error, serverCount))
        } catch (_: Exception) {
            reconcile("Не удалось загрузить актуальный список серверов", ConnectionNotice.ActionFailed)
        }
        _state.value = _state.value.copy(busySources = _state.value.busySources - sourceId)
    }

    private fun applyOperation(operation: CatalogOperation, notice: ConnectionNotice? = null) {
        val old = _state.value
        val next = operation.catalog ?: old.catalog
        _state.value = old.copy(
            catalog = next,
            tests = if (next?.stateVersion != old.catalog?.stateVersion) emptyMap() else old.tests,
            message = null,
            notice = notice,
        )
    }

    private suspend fun reconcile(message: String, notice: ConnectionNotice? = null) {
        val active = activeProfile.first() ?: return
        val snapshot = runCatching { gateway.snapshot(active.profile, active.secrets.companionToken) }.getOrNull()
        _state.value = _state.value.copy(
            catalog = snapshot ?: _state.value.catalog,
            tests = emptyMap(),
            message = message.takeIf { notice == null },
            notice = notice,
        )
    }

    companion object { const val TEST_TTL_MILLIS = 5 * 60 * 1000L }
}
