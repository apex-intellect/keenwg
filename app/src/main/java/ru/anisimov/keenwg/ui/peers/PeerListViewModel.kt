package ru.anisimov.keenwg.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.ui.util.startForegroundRefresh

interface PeerListGateway {
    suspend fun cached(settings: ServerSettings): List<Peer> = emptyList()
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
    val refreshError: PeerListError? = null,
    val lastUpdated: Long? = null,
)

enum class PeerListError {
    UNAVAILABLE,
    UPDATE_REQUIRED,
    RECONNECT_REQUIRED,
}

class PeerListViewModel @JvmOverloads constructor(
    private val gateway: PeerListGateway = servicePeerListGateway(),
    private val settingsGateway: PeerListSettingsGateway = PeerListSettingsGateway {
        ServiceLocator.settingsStore.settings.first()
    },
    private val clock: PeerListClock = PeerListClock(System::currentTimeMillis),
) : ViewModel() {
    private val refreshMutex = Mutex()
    private var manualRefresh: Job? = null
    private var consecutiveBackgroundFailures = 0
    private val _state = MutableStateFlow(PeerListUiState())
    val state: StateFlow<PeerListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                gateway.cached(settingsGateway.settings())
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { peers ->
                val current = _state.value
                if (current.peers.isEmpty()) {
                    _state.value = current.copy(
                        peers = peers,
                        initialLoading = false,
                        refreshing = current.refreshing || current.initialLoading,
                    )
                }
            }
        }
    }

    fun refresh(): Job {
        manualRefresh?.takeIf(Job::isActive)?.let { return it }
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            refreshMutex.withLock { refreshLocked(reportFailure = true) }
        }.also { job ->
            manualRefresh = job
            job.start()
        }
    }

    fun startForegroundRefresh(): Job = viewModelScope.startForegroundRefresh(
        rciRefresh = { performRefresh(reportFailure = false) },
        historyRefresh = {},
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
        }.onFailure { failure ->
            _state.value = _state.value.copy(refreshError = failure.peerListError())
        }
        _state.value = _state.value.copy(busyKeys = _state.value.busyKeys - publicKey)
    }

    private suspend fun performRefresh(reportFailure: Boolean) {
        if (!refreshMutex.tryLock()) return
        try {
            refreshLocked(reportFailure)
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshLocked(reportFailure: Boolean) {
        val hadContent = _state.value.peers.isNotEmpty()
        _state.value = _state.value.copy(
            initialLoading = !hadContent,
            refreshing = hadContent,
            refreshError = if (reportFailure || !hadContent) null else _state.value.refreshError,
        )
        runCatching {
            gateway.list(settingsGateway.settings())
        }.onSuccess { peers ->
            consecutiveBackgroundFailures = 0
            _state.value = _state.value.copy(
                peers = peers,
                initialLoading = false,
                refreshing = false,
                refreshError = null,
                lastUpdated = clock.now(),
            )
        }.onFailure { failure ->
            val error = failure.peerListError()
            val visibleError = when {
                reportFailure || !hadContent -> error
                error != PeerListError.UNAVAILABLE -> error
                ++consecutiveBackgroundFailures >= BACKGROUND_FAILURE_THRESHOLD -> error
                else -> null
            }
            _state.value = _state.value.copy(
                initialLoading = false,
                refreshing = false,
                refreshError = visibleError,
            )
        }
    }

    private companion object {
        const val BACKGROUND_FAILURE_THRESHOLD = 2
    }
}

private fun servicePeerListGateway(): PeerListGateway = object : PeerListGateway {
    override suspend fun cached(settings: ServerSettings) = ServiceLocator.repository.cached(settings)
    override suspend fun list(settings: ServerSettings) = ServiceLocator.repository.list(settings)
    override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) =
        ServiceLocator.repository.setEnabled(settings, publicKey, enabled)
}

private fun Throwable.peerListError(): PeerListError = when ((this as? XkeenException)?.code) {
    XkeenErrorCode.UNSUPPORTED_SCHEMA -> PeerListError.UPDATE_REQUIRED
    XkeenErrorCode.UNAUTHORIZED -> PeerListError.RECONNECT_REQUIRED
    else -> PeerListError.UNAVAILABLE
}
