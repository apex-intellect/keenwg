package ru.anisimov.keenwg.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.discovery.DiscoveryPreview
import ru.anisimov.keenwg.data.discovery.RouterDiscovery
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.R

interface SettingsStoreGateway {
    val settings: Flow<ServerSettings>
    suspend fun save(settings: ServerSettings)
}

interface SettingsRciGateway {
    suspend fun authenticate(settings: ServerSettings)
    suspend fun get(settings: ServerSettings, path: String): String
}

class SettingsViewModel(
    private val store: SettingsStoreGateway = serviceSettingsStore(),
    private val rci: SettingsRciGateway = serviceSettingsRci(),
) : ViewModel() {
    val settings: StateFlow<ServerSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerSettings())
    private val operationMutex = Mutex()
    private val _msg = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()
    val preview = MutableStateFlow<DiscoveryPreview?>(null)

    fun saveAndTest(draft: ServerSettings) = guarded {
        ServerSettingsValidator.requireForSave(draft)
        rci.authenticate(draft)
        rci.get(draft, "show/version")
        store.save(draft)
        _msg.emit(R.string.settings_message_saved)
    }

    fun discover(draft: ServerSettings) = guarded {
        require(draft.host.isNotBlank() && draft.port in 1..65535 && draft.login.isNotBlank())
        rci.authenticate(draft)
        preview.value = RouterDiscovery.discover(rci.get(draft, "show/interface"), draft)
        _msg.emit(R.string.settings_message_review_discovered)
    }

    fun applyPreviewAndSave(draft: ServerSettings, found: DiscoveryPreview, acceptEndpointCandidate: Boolean) = guarded {
        val reviewed = found.applyTo(draft, acceptEndpointCandidate)
        ServerSettingsValidator.requireForSave(reviewed)
        rci.authenticate(reviewed)
        rci.get(reviewed, "show/version")
        store.save(reviewed)
        preview.value = null
        _msg.emit(R.string.settings_message_saved)
    }

    fun save(draft: ServerSettings) = saveAndTest(draft)
    fun testConnection(draft: ServerSettings) = saveAndTest(draft)
    fun rediscover(draft: ServerSettings) = discover(draft)

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        if (!operationMutex.tryLock()) return@launch
        try {
            runCatching { block() }.onFailure {
                _msg.emit(R.string.settings_error_operation_failed)
            }
        } finally {
            operationMutex.unlock()
        }
    }
}

private fun serviceSettingsStore(): SettingsStoreGateway = object : SettingsStoreGateway {
    override val settings get() = ServiceLocator.settingsStore.settings
    override suspend fun save(settings: ServerSettings) = ServiceLocator.settingsStore.save(settings)
}

private fun serviceSettingsRci(): SettingsRciGateway = object : SettingsRciGateway {
    private val client = RciClient()
    override suspend fun authenticate(settings: ServerSettings) = client.authenticate(settings)
    override suspend fun get(settings: ServerSettings, path: String) = client.get(settings, path)
}
