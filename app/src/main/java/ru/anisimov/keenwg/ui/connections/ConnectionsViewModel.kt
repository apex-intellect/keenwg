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
import ru.anisimov.keenwg.data.catalog.CatalogErrorCode
import ru.anisimov.keenwg.data.catalog.CatalogGateway
import ru.anisimov.keenwg.data.catalog.CatalogOperation
import ru.anisimov.keenwg.data.catalog.CatalogSourceDraft
import ru.anisimov.keenwg.data.catalog.ImportDraftGateway
import ru.anisimov.keenwg.data.catalog.ImportOrigin
import ru.anisimov.keenwg.data.catalog.ImportParser
import ru.anisimov.keenwg.data.catalog.SourceConfigurationGateway
import ru.anisimov.keenwg.data.catalog.SourceConfigurationStatus
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.R

class ConnectionsViewModel(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val gateway: CatalogGateway,
    private val drafts: ImportDraftGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val sourceConfigurations: SourceConfigurationGateway = UnavailableSourceConfigurationGateway,
) : ViewModel() {
    constructor() : this(
        ServiceLocator.routerProfileStore.activeProfile,
        ServiceLocator.catalogGateway,
        ServiceLocator.importDraftStore,
        sourceConfigurations = ServiceLocator.sourceConfigurationGateway,
    )

    private val _state = MutableStateFlow(ConnectionsUiState())
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    init { loadCatalog() }

    fun loadCatalog(): Job = viewModelScope.launch {
        val active = activeProfile.first()
        if (active == null || active.profile.companionUrl.isBlank() || active.secrets.companionToken.isBlank()) {
            _state.value = ConnectionsUiState(loading = false, setupRequired = true)
            return@launch
        }
        _state.value = _state.value.copy(loading = true, loadError = null, messageResource = null, notice = null)
        runCatching { gateway.snapshot(active.profile, active.secrets.companionToken) }
            .onSuccess { catalog ->
                val selected = _state.value.selectedGroupId?.takeIf { id -> catalog.groups.any { it.id == id } }
                val source = catalog.sources.firstOrNull { it.id == XKEEN_SUBSCRIPTION_SOURCE_ID }
                val configuration = if (source == null) {
                    emptyMap()
                } else {
                    runCatching {
                        sourceConfigurations.status(
                            active.profile,
                            active.secrets.companionToken,
                            XKEEN_SUBSCRIPTION_SOURCE_ID,
                        )
                    }.getOrNull()?.let { mapOf(XKEEN_SUBSCRIPTION_SOURCE_ID to it) }.orEmpty()
                }
                _state.value = _state.value.copy(
                    loading = false,
                    loadError = null,
                    catalog = catalog,
                    selectedGroupId = selected,
                    sourceConfiguration = configuration,
                )
            }
            .onFailure { failure ->
                _state.value = _state.value.copy(
                    loading = false,
                    loadError = (failure as? CatalogException)?.code,
                    messageResource = null,
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
            _state.value = _state.value.copy(pendingImport = PendingImport(preview, duplicate), messageResource = null, notice = null)
        } catch (_: Exception) {
            source.fill(0)
            drafts.clear()
            _state.value = _state.value.copy(pendingImport = null, messageResource = R.string.connections_message_import_unrecognized)
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
            _state.value = _state.value.copy(messageResource = R.string.connections_message_choose_group_and_name)
            return@launch
        }
        val source = drafts.take()
        if (source == null) {
            _state.value = _state.value.copy(pendingImport = null, messageResource = R.string.connections_message_import_expired)
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
            _state.value = _state.value.copy(pendingImport = null, messageResource = R.string.connections_message_save_source_failed)
        } finally {
            source.fill(0)
        }
    }

    fun refreshSource(sourceId: String): Job {
        if (sourceId == XKEEN_SUBSCRIPTION_SOURCE_ID &&
            _state.value.sourceConfiguration[sourceId]?.configured == false
        ) {
            _state.value = _state.value.copy(
                editingSubscriptionSourceId = sourceId,
                subscriptionLinkError = null,
                notice = null,
                messageResource = null,
            )
            return viewModelScope.launch { }
        }
        return viewModelScope.launch { refreshSourceNow(sourceId) }
    }

    fun dismissSubscriptionEditor() {
        _state.value = _state.value.copy(
            editingSubscriptionSourceId = null,
            subscriptionLinkError = null,
        )
    }

    fun editSubscriptionLink(sourceId: String) {
        if (sourceId != XKEEN_SUBSCRIPTION_SOURCE_ID ||
            _state.value.catalog?.sources?.none { it.id == sourceId } != false
        ) return
        _state.value = _state.value.copy(
            editingSubscriptionSourceId = sourceId,
            subscriptionLinkError = null,
            notice = null,
            messageResource = null,
        )
    }

    fun saveSubscriptionLink(value: ByteArray): Job = viewModelScope.launch {
        val sourceId = _state.value.editingSubscriptionSourceId
        try {
            if (sourceId != XKEEN_SUBSCRIPTION_SOURCE_ID ||
                _state.value.sourceActions[sourceId] == SourceActionState.SAVING_LINK
            ) return@launch
            val active = activeProfile.first() ?: return@launch
            setSourceAction(sourceId, SourceActionState.SAVING_LINK)
            _state.value = _state.value.copy(subscriptionLinkError = null, notice = null, messageResource = null)
            val result = sourceConfigurations.replace(
                active.profile,
                active.secrets.companionToken,
                sourceId,
                value,
            )
            _state.value = _state.value.copy(
                sourceConfiguration = _state.value.sourceConfiguration + (sourceId to result),
                editingSubscriptionSourceId = null,
                subscriptionLinkError = null,
            )
            setSourceAction(sourceId, null)
            refreshSourceNow(sourceId)
        } catch (failure: Exception) {
            _state.value = _state.value.copy(
                subscriptionLinkError = subscriptionLinkError(failure),
                editingSubscriptionSourceId = sourceId,
                notice = null,
                messageResource = null,
            )
        } finally {
            value.fill(0)
            if (sourceId != null) setSourceAction(sourceId, null)
        }
    }

    fun testNode(nodeId: String): Job = viewModelScope.launch {
        val current = _state.value
        val catalog = current.catalog ?: return@launch
        val node = catalog.nodes.firstOrNull { it.id == nodeId && it.testable } ?: return@launch
        if (nodeId in current.busyNodes) return@launch
        val active = activeProfile.first() ?: return@launch
        _state.value = current.copy(busyNodes = current.busyNodes + nodeId, messageResource = null)
        try {
            val operation = gateway.testNode(active.profile, active.secrets.companionToken, catalog.stateVersion, keyFactory(), nodeId)
            val nextCatalog = operation.catalog ?: catalog
            val test = operation.test
            val tests = if (test != null && test.nodeId == nodeId) {
                _state.value.tests + (nodeId to TestedNode(test, nextCatalog.stateVersion, nowMillis()))
            } else _state.value.tests
            _state.value = _state.value.copy(catalog = nextCatalog, tests = tests, messageResource = null, notice = null)
        } catch (_: Exception) {
            reconcile(R.string.connections_message_test_failed)
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
        _state.value = if (valid) current.copy(pendingActivation = node, messageResource = null)
        else current.copy(messageResource = R.string.connections_message_test_first)
    }

    fun dismissActivation() { _state.value = _state.value.copy(pendingActivation = null) }

    fun confirmActivation(): Job = viewModelScope.launch {
        val current = _state.value
        val target = current.pendingActivation ?: return@launch
        val catalog = current.catalog ?: return@launch
        val active = activeProfile.first() ?: return@launch
        _state.value = current.copy(busyNodes = current.busyNodes + target.id, messageResource = null)
        try {
            applyOperation(gateway.activateNode(active.profile, active.secrets.companionToken, catalog.stateVersion, keyFactory(), target.id))
        } catch (_: Exception) {
            reconcile(R.string.connections_message_response_lost)
        }
        _state.value = _state.value.copy(pendingActivation = null, busyNodes = _state.value.busyNodes - target.id)
    }

    private suspend fun refreshSourceNow(sourceId: String) {
        mutateSourceNow(sourceId) { profile, token, version ->
            gateway.refreshSource(profile.profile, token, version, keyFactory(), sourceId)
        }
    }

    private suspend fun mutateSourceNow(
        sourceId: String,
        call: suspend (ActiveRouterProfile, String, ULong) -> CatalogOperation,
    ) {
        val current = _state.value
        val catalog = current.catalog ?: return
        if (sourceId in current.busySources) return
        val active = activeProfile.first() ?: return
        _state.value = current.copy(
            busySources = current.busySources + sourceId,
            sourceActions = current.sourceActions + (sourceId to SourceActionState.REFRESHING),
            messageResource = null,
            notice = null,
        )
        try {
            val operation = call(active, active.secrets.companionToken, catalog.stateVersion)
            if (sourceId == XKEEN_SUBSCRIPTION_SOURCE_ID && operation.error == "subscription_not_configured") {
                _state.value = _state.value.copy(
                    catalog = operation.catalog ?: catalog,
                    sourceConfiguration = _state.value.sourceConfiguration +
                        (sourceId to SourceConfigurationStatus(false)),
                    editingSubscriptionSourceId = sourceId,
                    subscriptionLinkError = null,
                    messageResource = null,
                    notice = null,
                )
                return
            }
            val serverCount = operation.catalog?.sources
                ?.firstOrNull { it.id == sourceId }
                ?.nodeCount
                ?: catalog.sources.firstOrNull { it.id == sourceId }?.nodeCount
                ?: 0
            applyOperation(operation, connectionOperationNotice(operation.result, operation.error, serverCount))
        } catch (_: Exception) {
            reconcile(R.string.connections_message_refresh_failed, ConnectionNotice.ActionFailed)
        } finally {
            _state.value = _state.value.copy(
                busySources = _state.value.busySources - sourceId,
                sourceActions = _state.value.sourceActions - sourceId,
            )
        }
    }

    private fun setSourceAction(sourceId: String, action: SourceActionState?) {
        _state.value = _state.value.copy(
            sourceActions = if (action == null) {
                _state.value.sourceActions - sourceId
            } else {
                _state.value.sourceActions + (sourceId to action)
            },
        )
    }

    private fun subscriptionLinkError(failure: Exception): SubscriptionLinkError = when (
        (failure as? CatalogException)?.code
    ) {
        CatalogErrorCode.INVALID_SETTINGS, CatalogErrorCode.PAYLOAD_TOO_LARGE ->
            SubscriptionLinkError.INVALID_LINK
        CatalogErrorCode.UNAUTHORIZED, CatalogErrorCode.FORBIDDEN ->
            SubscriptionLinkError.PERMISSION_DENIED
        CatalogErrorCode.UNSUPPORTED_SCHEMA, CatalogErrorCode.NOT_FOUND ->
            SubscriptionLinkError.UNSUPPORTED
        else -> SubscriptionLinkError.UNAVAILABLE
    }

    private fun applyOperation(operation: CatalogOperation, notice: ConnectionNotice? = null) {
        val old = _state.value
        val next = operation.catalog ?: old.catalog
        _state.value = old.copy(
            catalog = next,
            tests = if (next?.stateVersion != old.catalog?.stateVersion) emptyMap() else old.tests,
            messageResource = null,
            notice = notice,
        )
    }

    private suspend fun reconcile(messageResource: Int, notice: ConnectionNotice? = null) {
        val active = activeProfile.first() ?: return
        val snapshot = runCatching { gateway.snapshot(active.profile, active.secrets.companionToken) }.getOrNull()
        _state.value = _state.value.copy(
            catalog = snapshot ?: _state.value.catalog,
            tests = emptyMap(),
            messageResource = messageResource.takeIf { notice == null },
            notice = notice,
        )
    }

    companion object { const val TEST_TTL_MILLIS = 5 * 60 * 1000L }
}

private object UnavailableSourceConfigurationGateway : SourceConfigurationGateway {
    override suspend fun status(
        profile: ru.anisimov.keenwg.domain.model.RouterProfile,
        token: String,
        sourceId: String,
    ): SourceConfigurationStatus = throw CatalogException(CatalogErrorCode.NOT_FOUND)

    override suspend fun replace(
        profile: ru.anisimov.keenwg.domain.model.RouterProfile,
        token: String,
        sourceId: String,
        subscriptionUrl: ByteArray,
    ): SourceConfigurationStatus {
        subscriptionUrl.fill(0)
        throw CatalogException(CatalogErrorCode.NOT_FOUND)
    }
}
