package ru.anisimov.keenwg.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.ui.util.startForegroundRefresh

interface PeerListGateway {
    suspend fun list(settings: ServerSettings): List<Peer>
    suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) = Unit
}

fun interface PeerListSettingsGateway {
    suspend fun settings(): ServerSettings
}

fun interface PeerListClock {
    fun now(): Long
}

data class PeerListUiState(
    val peers: List<Peer> = emptyList(),
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val busyKeys: Set<String> = emptySet(),
    val refreshError: String? = null,
    val lastUpdated: Long? = null,
)

class PeerListViewModel @JvmOverloads constructor(
    private val gateway: PeerListGateway = servicePeerListGateway(),
    private val settingsGateway: PeerListSettingsGateway = PeerListSettingsGateway {
        ServiceLocator.settingsStore.settings.first()
    },
    private val clock: PeerListClock = PeerListClock(System::currentTimeMillis),
) : ViewModel() {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(PeerListUiState())
    val state: StateFlow<PeerListUiState> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch { performRefresh() }

    fun startForegroundRefresh(): Job = viewModelScope.startForegroundRefresh(
        rciRefresh = ::performRefresh,
        collectorRefresh = {},
    )

    fun setEnabled(publicKey: String, enabled: Boolean): Job = viewModelScope.launch {
        if (publicKey in _state.value.busyKeys) return@launch
        _state.value = _state.value.copy(
            busyKeys = _state.value.busyKeys + publicKey,
            refreshError = null,
        )
        runCatching {
            val settings = settingsGateway.settings()
            gateway.setEnabled(settings, publicKey, enabled)
            gateway.list(settings)
        }.onSuccess { confirmed ->
            _state.value = _state.value.copy(
                peers = confirmed,
                initialLoading = false,
                lastUpdated = clock.now(),
            )
        }.onFailure {
            _state.value = _state.value.copy(refreshError = REFRESH_ERROR)
        }
        _state.value = _state.value.copy(busyKeys = _state.value.busyKeys - publicKey)
    }

    private suspend fun performRefresh() {
        if (!refreshMutex.tryLock()) return
        try {
            val hadContent = _state.value.peers.isNotEmpty()
            _state.value = _state.value.copy(
                initialLoading = !hadContent,
                refreshing = hadContent,
                refreshError = null,
            )
            runCatching {
                gateway.list(settingsGateway.settings())
            }.onSuccess { peers ->
                _state.value = _state.value.copy(
                    peers = peers,
                    initialLoading = false,
                    refreshing = false,
                    lastUpdated = clock.now(),
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    initialLoading = false,
                    refreshing = false,
                    refreshError = REFRESH_ERROR,
                )
            }
        } finally {
            refreshMutex.unlock()
        }
    }
}

private fun servicePeerListGateway(): PeerListGateway = object : PeerListGateway {
    override suspend fun list(settings: ServerSettings) = ServiceLocator.repository.list(settings)
    override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) =
        ServiceLocator.repository.setEnabled(settings, publicKey, enabled)
}

private const val REFRESH_ERROR = "Не удалось обновить данные роутера."
