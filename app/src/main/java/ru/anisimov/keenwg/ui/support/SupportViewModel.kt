package ru.anisimov.keenwg.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.support.SupportExport
import ru.anisimov.keenwg.data.support.SupportGateway

data class SupportUiState(
    val busy: Boolean = false,
    val export: SupportExport? = null,
    val error: String? = null,
)

class SupportViewModel(
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val gateway: SupportGateway,
) : ViewModel() {
    constructor() : this(ServiceLocator.routerProfileStore.activeProfile, ServiceLocator.supportGateway)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SupportUiState())
    val state: StateFlow<SupportUiState> = _state.asStateFlow()

    fun generate(): Job = viewModelScope.launch {
        if (!mutex.tryLock()) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val active = activeProfileFlow.first()
            if (active == null || active.profile.companionUrl.isBlank() || active.secrets.companionToken.isBlank()) {
                _state.value = _state.value.copy(error = "Companion не настроен")
                return@launch
            }
            val export = gateway.generate(active.profile, active.secrets.companionToken)
            _state.value = _state.value.copy(export = export, error = null)
        } catch (_: Exception) {
            _state.value = _state.value.copy(error = "Не удалось сформировать безопасный отчёт")
        } finally {
            _state.value = _state.value.copy(busy = false)
            mutex.unlock()
        }
    }
}
