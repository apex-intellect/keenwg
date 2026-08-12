package ru.anisimov.keenwg.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.ServiceLocator

class AboutViewModel(
    enabled: Flow<Boolean>,
    private val persist: suspend (Boolean) -> Unit,
) : ViewModel() {
    constructor() : this(
        enabled = ServiceLocator.expertModeStore.enabled,
        persist = ServiceLocator.expertModeStore::setEnabled,
    )

    val expertMode: StateFlow<Boolean> = enabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    fun setExpertMode(enabled: Boolean) {
        viewModelScope.launch { persist(enabled) }
    }
}
