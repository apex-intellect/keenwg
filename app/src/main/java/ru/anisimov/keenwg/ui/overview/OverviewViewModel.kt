package ru.anisimov.keenwg.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.capability.CapabilityRegistry
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.ui.navigation.visibleTopLevelDestinations

class OverviewViewModel(
    private val profilesFlow: Flow<RouterProfilesState>,
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val companion: CompanionClient,
    private val registry: CapabilityRegistry,
    private val xkeenNode: suspend (CompanionEndpoint) -> String?,
    private val selectProfile: suspend (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    constructor() : this(
        profilesFlow = ServiceLocator.routerProfileStore.state,
        activeProfileFlow = ServiceLocator.routerProfileStore.activeProfile,
        companion = ServiceLocator.companionClient,
        registry = ServiceLocator.capabilityRegistry,
        xkeenNode = { endpoint -> ServiceLocator.xkeenRepository.status(endpoint).active?.displayName },
        selectProfile = ServiceLocator.routerProfileStore::select,
    )

    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()
    private var current: Pair<RouterProfilesState, ActiveRouterProfile?>? = null

    init {
        viewModelScope.launch {
            combine(profilesFlow, activeProfileFlow, ::Pair).collectLatest { snapshot ->
                current = snapshot
                load(snapshot.first, snapshot.second)
            }
        }
    }

    fun refresh(): Job = viewModelScope.launch {
        current?.let { (profiles, active) -> load(profiles, active) }
    }

    fun selectProfile(id: String): Job = viewModelScope.launch(dispatcher) {
        val ready = current?.first as? RouterProfilesState.Ready ?: return@launch
        if (ready.profiles.none { it.id == id } || ready.selectedId == id) return@launch
        selectProfile.invoke(id)
    }

    private suspend fun load(profilesState: RouterProfilesState, active: ActiveRouterProfile?) = withContext(dispatcher) {
        when (profilesState) {
            RouterProfilesState.Loading -> _state.value = OverviewState()
            is RouterProfilesState.Locked -> _state.value = OverviewState(
                loading = false,
                health = OverviewHealth.LOCKED,
                message = "Защищённое хранилище профилей заблокировано",
                mutationsEnabled = false,
                destinations = visibleTopLevelDestinations(null, locked = true),
            )
            is RouterProfilesState.Ready -> loadReady(profilesState, active)
        }
    }

    private suspend fun loadReady(state: RouterProfilesState.Ready, active: ActiveRouterProfile?) {
        val selected = state.profiles.firstOrNull { it.id == state.selectedId }
        if (selected == null || active?.profile?.id != selected.id) {
            _state.value = OverviewState(
                loading = false,
                health = OverviewHealth.SETUP_REQUIRED,
                profiles = state.profiles,
                selectedProfileId = state.selectedId,
                selectedProfileName = selected?.displayName,
                showProfileSelector = state.profiles.size > 1,
                message = "Профиль роутера требует повторной настройки",
                mutationsEnabled = false,
            )
            return
        }

        val optionalModules = registry.resolve(selected)
        val companionFields = listOf(selected.companionUrl, selected.certificatePin, active.secrets.companionToken)
        val companionComplete = companionFields.all { it.isNotBlank() }
        val companionPartial = companionFields.any { it.isNotBlank() } && !companionComplete
        _state.value = OverviewState(
            loading = companionComplete,
            health = when {
                companionComplete -> OverviewHealth.LOADING
                companionPartial -> OverviewHealth.SETUP_REQUIRED
                else -> OverviewHealth.SETUP_REQUIRED
            },
            profiles = state.profiles,
            selectedProfileId = selected.id,
            selectedProfileName = selected.displayName,
            showProfileSelector = state.profiles.size > 1,
            capabilities = optionalModules,
            destinations = visibleTopLevelDestinations(optionalModules, locked = false),
            message = if (companionPartial) "Настройка защищённого companion не завершена" else null,
            mutationsEnabled = true,
        )

        var activeNode: String? = null
        var nodeUnavailable = false
        if (companionComplete) {
            activeNode = runCatching { xkeenNode(active.requireCompanionEndpoint()) }.getOrElse {
                nodeUnavailable = true
                null
            }
        }

        if (!companionComplete) {
            _state.value = _state.value.copy(
                loading = false,
                activeXkeenNode = activeNode,
                message = _state.value.message ?: if (nodeUnavailable) "Статус XKeen временно недоступен" else null,
            )
            return
        }

        try {
            val companionDocument = companion.capabilities(selected, active.secrets.companionToken)
            val merged = registry.resolve(selected, companionDocument)
            _state.value = _state.value.copy(
                loading = false,
                health = OverviewHealth.HEALTHY,
                capabilities = merged,
                destinations = visibleTopLevelDestinations(merged, locked = false),
                activeXkeenNode = activeNode,
                message = if (nodeUnavailable) "Статус XKeen временно недоступен" else null,
            )
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                health = OverviewHealth.DEGRADED,
                activeXkeenNode = activeNode,
                message = "Защищённый канал с роутером временно недоступен",
            )
        }
    }
}
