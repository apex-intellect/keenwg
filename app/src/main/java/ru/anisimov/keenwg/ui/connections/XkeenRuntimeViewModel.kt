package ru.anisimov.keenwg.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeError
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeRepository
import ru.anisimov.keenwg.data.xkeen.runtimeError

data class XkeenRuntimeUiState(
    val running: Boolean? = null,
    val canControl: Boolean = false,
    val loading: Boolean = true,
    val changing: Boolean = false,
    val setupRequired: Boolean = false,
    val error: XkeenRuntimeError? = null,
    val confirmStop: Boolean = false,
) {
    val busy: Boolean get() = loading || changing
    val controlEnabled: Boolean get() = canControl && running != null && !busy &&
        error != XkeenRuntimeError.UNSUPPORTED && !confirmStop
}

class XkeenRuntimeViewModel(
    profiles: Flow<ActiveRouterProfile?>,
    private val repository: XkeenRuntimeRepository,
) : ViewModel() {
    constructor() : this(ServiceLocator.routerProfileStore.activeProfile, ServiceLocator.xkeenRuntimeRepository)

    private val mutableState = MutableStateFlow(XkeenRuntimeUiState())
    val state = mutableState.asStateFlow()
    private var endpoint: CompanionEndpoint? = null
    private var generation = 0
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            profiles.distinctUntilChanged().collect { active ->
                generation++
                operation?.cancel()
                endpoint = active?.let { runCatching { it.requireCompanionEndpoint() }.getOrNull() }
                mutableState.value = XkeenRuntimeUiState(loading = false, setupRequired = endpoint == null)
                refresh()
            }
        }
    }

    fun refresh() {
        val target = endpoint ?: return
        if (mutableState.value.busy || mutableState.value.confirmStop) return
        val currentGeneration = generation
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val result = repository.load(target)
                if (generation == currentGeneration) mutableState.value = XkeenRuntimeUiState(
                    running = result.running, canControl = result.canControl, loading = false,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generation == currentGeneration) mutableState.value = XkeenRuntimeUiState(
                    loading = false, error = failure.runtimeError(),
                )
            }
        }
    }

    fun requestEnabled(enabled: Boolean) {
        val current = mutableState.value
        if (!current.controlEnabled || current.running == enabled) return
        if (!enabled) mutableState.value = current.copy(confirmStop = true)
        else change(enabled)
    }

    fun cancelStop() {
        mutableState.value = mutableState.value.copy(confirmStop = false)
    }

    fun confirmStop() {
        val current = mutableState.value
        if (!current.confirmStop || current.busy || !current.canControl || current.running != true) return
        mutableState.value = current.copy(confirmStop = false)
        change(false)
    }

    private fun change(enabled: Boolean) {
        val target = endpoint ?: return
        val currentGeneration = generation
        mutableState.value = mutableState.value.copy(changing = true, error = null)
        operation = viewModelScope.launch {
            val result = repository.setEnabled(target, enabled)
            if (generation == currentGeneration) mutableState.value = mutableState.value.copy(
                running = result.running,
                changing = false,
                canControl = mutableState.value.canControl && result.error != XkeenRuntimeError.FORBIDDEN &&
                    result.error != XkeenRuntimeError.UNAUTHORIZED,
                error = result.error,
            )
        }
    }
}
