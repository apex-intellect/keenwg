package ru.anisimov.keenwg.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.network.DomainRoutingGateway
import ru.anisimov.keenwg.data.network.DomainRoutingResult
import ru.anisimov.keenwg.data.network.DomainRoutingStatus
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.DomainRuleDraft
import ru.anisimov.keenwg.data.network.NetworkDevice
import ru.anisimov.keenwg.data.network.NetworkExclusionEntry
import ru.anisimov.keenwg.data.network.NetworkExclusionGateway
import ru.anisimov.keenwg.data.network.NetworkExclusionStatus
import ru.anisimov.keenwg.data.network.NetworkGateway
import ru.anisimov.keenwg.data.routes.RouteExplanation
import ru.anisimov.keenwg.data.routes.RouteExplainGateway
import ru.anisimov.keenwg.data.routes.RouteExplainRequest
import ru.anisimov.keenwg.data.routes.ScenarioApplyResult
import ru.anisimov.keenwg.data.routes.ScenarioCatalog
import ru.anisimov.keenwg.data.routes.ScenarioGateway
import ru.anisimov.keenwg.data.routes.ScenarioReview
import ru.anisimov.keenwg.data.routes.RecoveryState
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.model.ServerSettings

enum class NetworkSegment { DEVICES, IP_ADDRESSES, DOMAINS, EXPLAIN, SCENARIOS }

data class DomainEditorState(
    val original: DomainRule? = null,
    val draft: DomainRuleDraft = DomainRuleDraft(),
    val reviewing: Boolean = false,
)

data class NetworkUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val selectedSegment: NetworkSegment = NetworkSegment.DEVICES,
    val devices: List<NetworkDevice> = emptyList(),
    val deviceError: String? = null,
    val pendingDevice: NetworkDevice? = null,
    val busy: Boolean = false,
    val writesBlocked: Boolean = false,
    val message: String? = null,
    val exclusions: NetworkExclusionStatus? = null,
    val exclusionError: String? = null,
    val exclusionEditorOpen: Boolean = false,
    val pendingExclusionDelete: NetworkExclusionEntry? = null,
    val domains: DomainRoutingStatus? = null,
    val domainError: String? = null,
    val domainEditor: DomainEditorState? = null,
    val pendingDomainDelete: DomainRule? = null,
    val routeChecking: Boolean = false,
    val routeExplanation: RouteExplanation? = null,
    val routeError: String? = null,
    val scenarioBusy: Boolean = false,
    val scenarioCatalog: ScenarioCatalog? = null,
    val scenarioReview: ScenarioReview? = null,
    val scenarioResult: ScenarioApplyResult? = null,
    val scenarioError: String? = null,
    val recoveryState: RecoveryState? = null,
)

class NetworkViewModel(
    private val settingsFlow: Flow<ServerSettings>,
    private val gateway: NetworkGateway,
    private val exclusionGateway: NetworkExclusionGateway? = null,
    private val domainGateway: DomainRoutingGateway? = null,
    private val activeProfileFlow: Flow<ActiveRouterProfile?>? = null,
    private val routeGateway: RouteExplainGateway? = null,
    private val scenarioGateway: ScenarioGateway? = null,
) : ViewModel() {
    constructor() : this(
        ServiceLocator.settingsStore.settings,
        ServiceLocator.networkRepository,
        ServiceLocator.networkExclusionClient,
        ServiceLocator.domainRoutingClient,
        ServiceLocator.routerProfileStore.activeProfile,
        ServiceLocator.routeExplainGateway,
        ServiceLocator.scenarioGateway,
    )

    private val loadMutex = Mutex()
    private val mutationMutex = Mutex()
    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state.asStateFlow()

    init { refresh() }

    fun selectSegment(segment: NetworkSegment) {
        _state.value = _state.value.copy(selectedSegment = segment)
        if (segment == NetworkSegment.SCENARIOS && _state.value.scenarioCatalog == null) refreshScenarios()
    }

    fun refresh(): Job = viewModelScope.launch {
        if (!loadMutex.tryLock()) return@launch
        try {
            val current = _state.value
            _state.value = current.copy(
                loading = current.devices.isEmpty() && current.exclusions == null && current.domains == null,
                refreshing = current.devices.isNotEmpty() || current.exclusions != null || current.domains != null,
                message = null,
            )
            val settings = settingsFlow.first()
            val companionEndpoint = runCatching { activeProfileFlow?.first()?.requireCompanionEndpoint() }.getOrNull()
            val devicesTask = async { runCatching { gateway.load(settings) } }
            val exclusionsTask = async { runCatching { companionEndpoint?.let { exclusionGateway?.load(it) } } }
            val domainsTask = async { runCatching { companionEndpoint?.let { domainGateway?.load(it) } } }
            val devicesResult = devicesTask.await()
            val exclusionsResult = exclusionsTask.await()
            val domainsResult = domainsTask.await()
            val before = _state.value
            val loadedDomains = domainsResult.getOrNull()
            _state.value = before.copy(
                loading = false,
                refreshing = false,
                devices = devicesResult.getOrNull() ?: before.devices,
                deviceError = devicesResult.exceptionOrNull()?.safeMessage("Не удалось получить устройства"),
                exclusions = exclusionsResult.getOrNull() ?: before.exclusions,
                exclusionError = exclusionsResult.exceptionOrNull()?.safeMessage("Не удалось получить IP-исключения"),
                domains = loadedDomains ?: before.domains,
                domainError = domainsResult.exceptionOrNull()?.domainMessage(),
                writesBlocked = if (before.recoveryState?.pending == true) true
                    else if (loadedDomains != null && loadedDomains.warnings.isEmpty()) false
                    else before.writesBlocked,
            )
        } finally {
            loadMutex.unlock()
        }
    }

    fun requestStaticEdit(device: NetworkDevice) {
        if (!_state.value.busy && !_state.value.writesBlocked) _state.value = _state.value.copy(pendingDevice = device, message = null)
    }

    fun dismissEdit() { if (!_state.value.busy) _state.value = _state.value.copy(pendingDevice = null) }

    fun openExclusionEditor() {
        if (!_state.value.busy && !_state.value.writesBlocked) _state.value = _state.value.copy(exclusionEditorOpen = true)
    }
    fun dismissExclusionEditor() {
        if (!_state.value.busy) _state.value = _state.value.copy(exclusionEditorOpen = false, pendingExclusionDelete = null)
    }
    fun requestDeleteExclusion(entry: NetworkExclusionEntry) {
        if (!_state.value.busy && !_state.value.writesBlocked && !entry.isProtected) _state.value = _state.value.copy(pendingExclusionDelete = entry)
    }
    fun addExclusion(value: String): Job = mutateExclusion("add", value.trim())
    fun confirmDeleteExclusion(): Job {
        val entry = _state.value.pendingExclusionDelete ?: return completedJob()
        return mutateExclusion("delete", entry.value)
    }

    fun openDomainCreate() {
        if (!_state.value.busy && !_state.value.writesBlocked && domainGateway != null) {
            _state.value = _state.value.copy(domainEditor = DomainEditorState(), message = null)
        }
    }

    fun openDomainEdit(rule: DomainRule) {
        if (!_state.value.busy && !_state.value.writesBlocked && !rule.isProtected && rule.source != "system") {
            _state.value = _state.value.copy(
                domainEditor = DomainEditorState(rule, DomainRuleDraft(rule.kind, rule.value, rule.effect, rule.label, rule.enabled)),
                message = null,
            )
        }
    }

    fun updateDomainDraft(draft: DomainRuleDraft) {
        val editor = _state.value.domainEditor ?: return
        if (!_state.value.busy) _state.value = _state.value.copy(domainEditor = editor.copy(draft = draft, reviewing = false))
    }

    fun reviewDomainDraft() {
        val editor = _state.value.domainEditor ?: return
        if (!_state.value.busy && validDraft(editor.draft)) _state.value = _state.value.copy(domainEditor = editor.copy(reviewing = true))
    }

    fun dismissDomainEditor() {
        if (!_state.value.busy) _state.value = _state.value.copy(domainEditor = null, pendingDomainDelete = null)
    }

    fun confirmDomainMutation(): Job {
        val editor = _state.value.domainEditor ?: return completedJob()
        if (!editor.reviewing || !validDraft(editor.draft)) return completedJob()
        return mutateDomain { client, endpoint, status ->
            editor.original?.let { client.update(endpoint, status, it.id, editor.draft) }
                ?: client.create(endpoint, status, editor.draft)
        }
    }

    fun requestDomainDelete(rule: DomainRule) {
        if (!_state.value.busy && !_state.value.writesBlocked && !rule.isProtected && rule.source != "system") {
            _state.value = _state.value.copy(pendingDomainDelete = rule)
        }
    }

    fun confirmDomainDelete(): Job {
        val rule = _state.value.pendingDomainDelete ?: return completedJob()
        return mutateDomain { client, endpoint, status -> client.delete(endpoint, status, rule.id) }
    }

    fun confirmStaticIp(ip: String): Job = mutateDevice { settings, device -> gateway.setStaticReservation(settings, device.mac, ip.trim()) }
    fun removeStaticIp(): Job = mutateDevice { settings, device -> gateway.removeStaticReservation(settings, device.mac) }

    fun explainRoute(target: String, protocol: String, port: Int, deviceId: String): Job {
        val gateway = routeGateway ?: return completedJob()
        val profiles = activeProfileFlow ?: return completedJob()
        val value = target.trim().lowercase()
        if (value.isBlank() || protocol !in setOf("tcp", "udp") || port !in 0..65535 || _state.value.routeChecking) return completedJob()
        _state.value = _state.value.copy(routeChecking = true, routeError = null, routeExplanation = null)
        return viewModelScope.launch {
            try {
                val active = profiles.first() ?: error("Companion не настроен")
                if (active.profile.companionUrl.isBlank() || active.secrets.companionToken.isBlank()) error("Companion не настроен")
                val looksLikeIp = value.contains(':') || value.matches(Regex("^[0-9.]+$"))
                val request = RouteExplainRequest(
                    domain = value.takeUnless { looksLikeIp }, ip = value.takeIf { looksLikeIp },
                    deviceId = deviceId.trim().takeIf { it.isNotEmpty() }, protocol = protocol, port = port,
                )
                val explanation = gateway.explain(active.profile, active.secrets.companionToken, request)
                _state.value = _state.value.copy(routeExplanation = explanation)
            } catch (failure: Exception) {
                _state.value = _state.value.copy(routeError = failure.safeMessage("Не удалось объяснить маршрут"))
            } finally {
                _state.value = _state.value.copy(routeChecking = false)
            }
        }
    }

    fun refreshScenarios(): Job {
        val gateway = scenarioGateway ?: return completedJob(); val profiles = activeProfileFlow ?: return completedJob()
        if (_state.value.scenarioBusy) return completedJob()
        _state.value = _state.value.copy(scenarioBusy = true, scenarioError = null)
        return viewModelScope.launch {
            try {
                val active = profiles.first() ?: error("Companion не настроен")
                val before = _state.value
                val recovery = gateway.recovery(active.profile, active.secrets.companionToken)
                val catalog = if (recovery.pending) before.scenarioCatalog else gateway.catalog(active.profile, active.secrets.companionToken)
                val unrelatedBlock = before.writesBlocked && before.recoveryState?.pending != true
                _state.value = before.copy(
                    scenarioCatalog = catalog,
                    scenarioReview = null,
                    recoveryState = recovery,
                    writesBlocked = recovery.pending || unrelatedBlock,
                )
            }
            catch (failure: Exception) { _state.value = _state.value.copy(scenarioError = failure.safeMessage("Не удалось получить сценарии")) }
            finally { _state.value = _state.value.copy(scenarioBusy = false) }
        }
    }

    fun reviewScenario(presetId: String): Job {
        val gateway = scenarioGateway ?: return completedJob(); val profiles = activeProfileFlow ?: return completedJob(); val catalog = _state.value.scenarioCatalog ?: return completedJob()
        if (_state.value.scenarioBusy || _state.value.writesBlocked) return completedJob()
        _state.value = _state.value.copy(scenarioBusy = true, scenarioError = null, scenarioResult = null)
        return viewModelScope.launch {
            try { val active = profiles.first() ?: error("Companion не настроен"); _state.value = _state.value.copy(scenarioReview = gateway.review(active.profile, active.secrets.companionToken, presetId, catalog.stateVersion)) }
            catch (failure: Exception) { _state.value = _state.value.copy(scenarioError = failure.safeMessage("Не удалось подготовить сценарий")) }
            finally { _state.value = _state.value.copy(scenarioBusy = false) }
        }
    }

    fun dismissScenarioReview() { if (!_state.value.scenarioBusy) _state.value = _state.value.copy(scenarioReview = null) }

    fun applyReviewedScenario(): Job {
        val gateway = scenarioGateway ?: return completedJob(); val profiles = activeProfileFlow ?: return completedJob(); val review = _state.value.scenarioReview ?: return completedJob()
        if (_state.value.scenarioBusy || _state.value.writesBlocked) return completedJob()
        _state.value = _state.value.copy(scenarioBusy = true, scenarioError = null)
        return viewModelScope.launch {
            try {
                val active = profiles.first() ?: error("Companion не настроен")
                val result = gateway.apply(active.profile, active.secrets.companionToken, review.plan.presetId, review.plan.stateVersion, review.planId)
                val recovery = if (result.status == "uncertain") gateway.recovery(active.profile, active.secrets.companionToken) else null
                _state.value = _state.value.copy(
                    scenarioResult = result,
                    scenarioReview = null,
                    recoveryState = recovery ?: _state.value.recoveryState,
                    writesBlocked = result.status == "uncertain",
                )
                if (result.status == "committed") _state.value = _state.value.copy(scenarioCatalog = gateway.catalog(active.profile, active.secrets.companionToken))
            } catch (failure: Exception) { _state.value = _state.value.copy(scenarioError = failure.safeMessage("Не удалось применить сценарий")) }
            finally { _state.value = _state.value.copy(scenarioBusy = false) }
        }
    }

    fun confirmRecovery(): Job {
        val gateway = scenarioGateway ?: return completedJob(); val profiles = activeProfileFlow ?: return completedJob(); val recovery = _state.value.recoveryState ?: return completedJob(); val planId = recovery.planId ?: return completedJob()
        if (!recovery.pending || _state.value.scenarioBusy) return completedJob()
        _state.value = _state.value.copy(scenarioBusy = true, scenarioError = null)
        return viewModelScope.launch {
            try {
                val active = profiles.first() ?: error("Companion не настроен")
                val result = gateway.rollback(active.profile, active.secrets.companionToken, planId)
                val current = gateway.recovery(active.profile, active.secrets.companionToken)
                val catalog = if (current.pending) _state.value.scenarioCatalog else gateway.catalog(active.profile, active.secrets.companionToken)
                _state.value = _state.value.copy(scenarioResult = result, recoveryState = current, scenarioCatalog = catalog, writesBlocked = current.pending)
            } catch (failure: Exception) { _state.value = _state.value.copy(scenarioError = failure.safeMessage("Не удалось восстановить маршруты")) }
            finally { _state.value = _state.value.copy(scenarioBusy = false) }
        }
    }

    private fun mutateDevice(operation: suspend (ServerSettings, NetworkDevice) -> Unit): Job {
        val device = _state.value.pendingDevice ?: return completedJob()
        if (!_state.value.busy && !_state.value.writesBlocked && mutationMutex.tryLock()) {
            _state.value = _state.value.copy(busy = true, message = null)
            return viewModelScope.launch {
                try {
                    val settings = settingsFlow.first()
                    operation(settings, device)
                    val devices = gateway.load(settings)
                    _state.value = _state.value.copy(devices = devices, pendingDevice = null, message = "Изменение сохранено и проверено")
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(message = failure.safeMessage("Не удалось изменить статический адрес"))
                } finally {
                    _state.value = _state.value.copy(busy = false, loading = false, refreshing = false)
                    mutationMutex.unlock()
                }
            }
        }
        return completedJob()
    }

    private fun mutateExclusion(action: String, value: String): Job {
        val client = exclusionGateway ?: return completedJob()
        val current = _state.value.exclusions ?: return completedJob()
        if (!_state.value.busy && !_state.value.writesBlocked && mutationMutex.tryLock()) {
            _state.value = _state.value.copy(busy = true, message = null)
            return viewModelScope.launch {
                try {
                    val endpoint = activeProfileFlow?.first()?.requireCompanionEndpoint() ?: error("Companion не настроен")
                    val result = client.mutate(endpoint, current.stateVersion, action, value)
                    _state.value = _state.value.copy(
                        exclusions = result.status,
                        exclusionEditorOpen = false,
                        pendingExclusionDelete = null,
                        writesBlocked = result.result == "uncertain",
                        message = transactionMessage(result.result, "IP-исключения применены и XKeen перезапущен"),
                    )
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(message = failure.safeMessage("Не удалось изменить исключения"))
                } finally {
                    _state.value = _state.value.copy(busy = false)
                    mutationMutex.unlock()
                }
            }
        }
        return completedJob()
    }

    private fun mutateDomain(operation: suspend (DomainRoutingGateway, CompanionEndpoint, DomainRoutingStatus) -> DomainRoutingResult): Job {
        val client = domainGateway ?: return completedJob()
        val current = _state.value.domains ?: return completedJob()
        if (!_state.value.busy && !_state.value.writesBlocked && mutationMutex.tryLock()) {
            _state.value = _state.value.copy(busy = true, message = null)
            return viewModelScope.launch {
                try {
                    val endpoint = activeProfileFlow?.first()?.requireCompanionEndpoint() ?: error("Companion не настроен")
                    val result = operation(client, endpoint, current)
                    _state.value = _state.value.copy(
                        domains = result.status,
                        domainEditor = null,
                        pendingDomainDelete = null,
                        writesBlocked = result.result == "uncertain",
                        message = transactionMessage(result.result, "Доменные маршруты применены и проверены"),
                    )
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(message = failure.safeMessage("Не удалось изменить доменные маршруты"))
                } finally {
                    _state.value = _state.value.copy(busy = false)
                    mutationMutex.unlock()
                }
            }
        }
        return completedJob()
    }

    private fun validDraft(draft: DomainRuleDraft) = draft.kind in setOf("domain", "suffix", "geosite") &&
        draft.effect in setOf("direct", "vpn") && draft.value.trim().isNotEmpty()

    private fun transactionMessage(result: String, committed: String) = when (result) {
        "committed" -> committed
        "rolled_back" -> "Изменение отменено; прежние правила восстановлены"
        "rejected" -> "Правила изменились; обновите список и повторите"
        else -> "Состояние маршрутов требует проверки; новые изменения временно заблокированы"
    }

    private fun Throwable.domainMessage(): String = if (this is XkeenException && code in setOf(XkeenErrorCode.NOT_FOUND, XkeenErrorCode.UNSUPPORTED_SCHEMA)) {
        "Обновите Companion, чтобы управлять доменами"
    } else safeMessage("Не удалось получить доменные правила")

    private fun Throwable.safeMessage(fallback: String) = message?.takeIf { it.isNotBlank() } ?: fallback
    private fun completedJob() = Job().apply { complete() }
}
