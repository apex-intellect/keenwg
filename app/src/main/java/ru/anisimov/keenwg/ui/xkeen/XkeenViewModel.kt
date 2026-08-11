package ru.anisimov.keenwg.ui.xkeen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenNodeDiagnostic
import ru.anisimov.keenwg.data.xkeen.XkeenOperation
import ru.anisimov.keenwg.data.xkeen.XkeenOperationResult
import ru.anisimov.keenwg.data.xkeen.XkeenOperationState
import ru.anisimov.keenwg.data.xkeen.XkeenRepositoryGateway
import ru.anisimov.keenwg.data.xkeen.XkeenStatus
import ru.anisimov.keenwg.data.store.XkeenPreferenceGateway
import ru.anisimov.keenwg.data.store.XkeenPreferences
import ru.anisimov.keenwg.data.store.serverIdentity

data class XkeenUiState(
    val loading: Boolean = true,
    val needsSetup: Boolean = false,
    val status: XkeenStatus? = null,
    val staleStatus: XkeenStatus? = null,
    val pendingNode: XkeenNode? = null,
    val operation: XkeenOperation? = null,
    val message: String? = null,
    val blocksMutation: Boolean = false,
    val busy: Boolean = false,
    val diagnosticsBusy: Boolean = false,
    val diagnostics: Map<String, XkeenNodeDiagnostic> = emptyMap(),
    val diagnosticsCheckedAt: Long? = null,
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
)

class XkeenViewModel constructor(
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val repository: XkeenRepositoryGateway,
    private val preferenceGateway: XkeenPreferenceGateway,
) : ViewModel() {
    constructor() : this(ServiceLocator.routerProfileStore.activeProfile, ServiceLocator.xkeenRepository, ServiceLocator.xkeenPreferenceStore)
    constructor(activeProfileFlow: Flow<ActiveRouterProfile?>, repository: XkeenRepositoryGateway) : this(activeProfileFlow, repository, EmptyPreferences)
    private val loadMutex = Mutex()
    private val mutationMutex = Mutex()
    private val diagnosticsMutex = Mutex()
    private val _state = MutableStateFlow(XkeenUiState())
    val state: StateFlow<XkeenUiState> = _state.asStateFlow()

    init {
        loadStatus()
        viewModelScope.launch {
            preferenceGateway.preferences.collect { preferences ->
                _state.value = _state.value.copy(favorites = preferences.favorites, recent = preferences.recent)
            }
        }
    }

    fun loadStatus(): Job = viewModelScope.launch {
        if (!loadMutex.tryLock()) return@launch
        try {
            val endpoint = runCatching { activeProfileFlow.first()?.requireCompanionEndpoint() }.getOrNull()
            if (endpoint == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    needsSetup = true,
                    message = null,
                )
                return@launch
            }
            _state.value = _state.value.copy(loading = _state.value.status == null, needsSetup = false, message = null)
            try {
                applyStatus(repository.status(endpoint))
            } catch (_: Exception) {
                val previous = _state.value.status
                _state.value = _state.value.copy(
                    loading = false,
                    staleStatus = previous,
                    message = LOAD_FAILURE,
                )
            }
        } finally {
            loadMutex.unlock()
        }
    }

    fun refreshSubscription(): Job = mutate { endpoint, status ->
        repository.refreshAndAwait(endpoint, status.stateVersion)
    }

    fun requestSelect(node: XkeenNode) {
        val current = _state.value
        if (current.busy || current.blocksMutation || current.pendingNode != null) return
        val canonical = current.status?.subscription?.nodes?.firstOrNull { it.id == node.id } ?: return
        if (canonical.id == current.status.active?.id) return
        _state.value = current.copy(pendingNode = canonical, message = null)
    }

    fun dismissSelection() {
        if (!_state.value.busy) _state.value = _state.value.copy(pendingNode = null)
    }

    fun confirmSelection(): Job {
        val target = _state.value.pendingNode ?: return completedJob()
        return mutate(target) { endpoint, status ->
            repository.selectAndAwait(endpoint, target.id, status.stateVersion)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun setFavoritesOnly(value: Boolean) {
        _state.value = _state.value.copy(favoritesOnly = value)
    }

    fun toggleFavorite(node: XkeenNode): Job = viewModelScope.launch {
        preferenceGateway.toggleFavorite(serverIdentity(node.host, node.port))
    }

    fun runDiagnostics(): Job = viewModelScope.launch {
        if (!diagnosticsMutex.tryLock()) return@launch
        try {
            val endpoint = runCatching { activeProfileFlow.first()?.requireCompanionEndpoint() }.getOrNull()
            if (endpoint == null || _state.value.status == null) return@launch
            _state.value = _state.value.copy(diagnosticsBusy = true, message = null)
            try {
                val report = repository.diagnostics(endpoint)
                _state.value = _state.value.copy(
                    diagnostics = report.results.associateBy { it.nodeId },
                    diagnosticsCheckedAt = report.checkedAt,
                )
            } catch (failure: Exception) {
                _state.value = _state.value.copy(message = failure.message ?: DIAGNOSTICS_FAILURE)
            }
        } finally {
            _state.value = _state.value.copy(diagnosticsBusy = false)
            diagnosticsMutex.unlock()
        }
    }

    private fun mutate(
        target: XkeenNode? = null,
        operation: suspend (CompanionEndpoint, XkeenStatus) -> XkeenOperation,
    ): Job {
        val before = _state.value
        if (before.busy || before.blocksMutation || before.status == null || !mutationMutex.tryLock()) {
            return completedJob()
        }
        _state.value = before.copy(busy = true, pendingNode = target, message = null)
        return viewModelScope.launch {
            try {
                val status = before.status
                val endpoint = runCatching { activeProfileFlow.first()?.requireCompanionEndpoint() }.getOrNull()
                if (endpoint == null) {
                    _state.value = _state.value.copy(needsSetup = true)
                    return@launch
                }
                val result = operation(endpoint, status)
                if (target != null && result.result == XkeenOperationResult.SUCCESS) {
                    preferenceGateway.recordSelected(serverIdentity(target.host, target.port))
                }
                _state.value = _state.value.copy(
                    operation = result,
                    blocksMutation = result.result == XkeenOperationResult.UNCERTAIN,
                    message = resultMessage(result),
                )
                try {
                    applyStatus(repository.status(endpoint), preserveMessage = true)
                } catch (_: Exception) {
                    _state.value = _state.value.copy(
                        staleStatus = _state.value.status,
                        message = if (_state.value.message == null) LOAD_FAILURE else _state.value.message,
                    )
                }
            } catch (failure: Exception) {
                _state.value = _state.value.copy(message = failure.message ?: MUTATION_FAILURE)
            } finally {
                _state.value = _state.value.copy(busy = false, pendingNode = null, loading = false)
                mutationMutex.unlock()
            }
        }
    }

    private fun applyStatus(status: XkeenStatus, preserveMessage: Boolean = false) {
        _state.value = _state.value.copy(
            loading = false,
            needsSetup = false,
            status = status,
            staleStatus = null,
            operation = status.operation ?: _state.value.operation,
            message = if (preserveMessage) _state.value.message else null,
            blocksMutation = status.operation.blocksMutation(),
        )
    }

    private fun XkeenOperation?.blocksMutation(): Boolean = this != null &&
        (state != XkeenOperationState.TERMINAL || result == XkeenOperationResult.UNCERTAIN)

    private fun resultMessage(operation: XkeenOperation): String = when (operation.result) {
        XkeenOperationResult.SUCCESS -> if (operation.kind == "refresh") "Подписка обновлена" else "Узел переключён и проверен"
        XkeenOperationResult.FAILED_ROLLED_BACK -> "Переключение не удалось; прежний узел восстановлен"
        XkeenOperationResult.FAILED_NO_CHANGE -> "Изменения не применялись"
        XkeenOperationResult.UNCERTAIN -> "Состояние XKeen требует проверки"
        null -> MUTATION_FAILURE
    }

    private fun completedJob() = Job().apply { complete() }

    private companion object {
        const val LOAD_FAILURE = "Не удалось обновить статус XKeen"
        const val MUTATION_FAILURE = "Не удалось выполнить операцию XKeen"
        const val DIAGNOSTICS_FAILURE = "Не удалось проверить доступность серверов"
    }
}

private object EmptyPreferences : XkeenPreferenceGateway {
    override val preferences = flowOf(XkeenPreferences())
    override suspend fun toggleFavorite(identity: String) = Unit
    override suspend fun recordSelected(identity: String) = Unit
}
