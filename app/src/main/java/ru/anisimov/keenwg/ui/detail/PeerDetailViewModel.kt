package ru.anisimov.keenwg.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.anisimov.keenwg.data.AddResult
import ru.anisimov.keenwg.data.RouterMutationError
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.data.collector.HistoryRange
import ru.anisimov.keenwg.data.collector.PeerId
import ru.anisimov.keenwg.ui.util.startForegroundRefresh

interface PeerDetailPeerGateway {
    fun cached(publicKey: String): Peer? = null
    suspend fun list(settings: ServerSettings): List<Peer>
    suspend fun rename(settings: ServerSettings, publicKey: String, name: String) = Unit
    suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) = Unit
    suspend fun regenerate(settings: ServerSettings, publicKey: String): AddResult = error("Операция не поддерживается")
    suspend fun remove(settings: ServerSettings, publicKey: String) = Unit
    suspend fun confFor(publicKey: String): String? = null
    suspend fun accessPolicy(publicKey: String): AccessPolicy? = null
}

fun interface PeerDetailSettingsGateway {
    suspend fun settings(): ServerSettings
}

fun interface PeerDetailStatsGateway {
    suspend fun history(
        settings: ServerSettings,
        publicKey: String,
        range: PeerHistoryRange,
        now: Long,
    ): PeerStats
}

fun interface PeerDetailClock {
    fun now(): Long
}

enum class PeerHistoryRange { DAY, WEEK, MONTH }

data class PeerDetailUiState(
    val peer: Peer? = null,
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val notFound: Boolean = false,
    val loadError: String? = null,
    val refreshError: String? = null,
    val operation: String? = null,
    val conf: String? = null,
    val selectedRange: PeerHistoryRange = PeerHistoryRange.DAY,
    val stats: PeerStats? = null,
    val collectorLoading: Boolean = false,
    val collectorRefreshing: Boolean = false,
    val collectorError: String? = null,
    val collectorLastUpdated: Long? = null,
    val accessPolicy: AccessPolicy? = null,
    val historySuppressed: Boolean = false,
    val observedAtEpochSeconds: Long = 0,
)

sealed interface PeerDetailEffect {
    data class NavigateToPeer(val newPublicKey: String) : PeerDetailEffect
}

class PeerDetailViewModel @JvmOverloads constructor(
    private val savedStateHandle: SavedStateHandle,
    private val peerGateway: PeerDetailPeerGateway = servicePeerGateway(),
    private val settingsGateway: PeerDetailSettingsGateway = PeerDetailSettingsGateway {
        ServiceLocator.settingsStore.settings.first()
    },
    private val statsGateway: PeerDetailStatsGateway = serviceStatsGateway(),
    private val clock: PeerDetailClock = PeerDetailClock { System.currentTimeMillis() / 1000L },
) : ViewModel() {
    private val peerRefreshMutex = Mutex()
    private val statsRefreshMutex = Mutex()
    private val _state = MutableStateFlow(PeerDetailUiState())
    val state: StateFlow<PeerDetailUiState> = _state.asStateFlow()

    val pendingNavigation: StateFlow<String?> = savedStateHandle.getStateFlow(PENDING_NAVIGATION_KEY, null)
    val effects: Flow<PeerDetailEffect> = pendingNavigation.filterNotNull().map(PeerDetailEffect::NavigateToPeer)

    fun load(publicKey: String): Job = viewModelScope.launch { performLoad(publicKey) }

    fun refreshStats(publicKey: String): Job = viewModelScope.launch { performStatsRefresh(publicKey) }

    fun selectRange(publicKey: String, range: PeerHistoryRange): Job {
        if (_state.value.selectedRange == range) return refreshStats(publicKey)
        _state.value = _state.value.copy(
            selectedRange = range,
            stats = null,
            collectorLoading = true,
            collectorRefreshing = false,
            collectorError = null,
            collectorLastUpdated = null,
        )
        return viewModelScope.launch { performStatsRefresh(publicKey, waitForPrevious = true) }
    }

    fun startForegroundRefresh(publicKey: String): Job = viewModelScope.startForegroundRefresh(
        rciRefresh = { performLoad(publicKey) },
        collectorRefresh = { performStatsRefresh(publicKey) },
    )

    private suspend fun performLoad(publicKey: String) {
        if (!peerRefreshMutex.tryLock()) return
        try {
        val cached = _state.value.peer ?: peerGateway.cached(publicKey)
        _state.value = _state.value.copy(
            peer = cached,
            initialLoading = cached == null,
            refreshing = cached != null,
            notFound = false,
            loadError = null,
            refreshError = null,
        )
        runCatching {
            val settings = settingsGateway.settings()
            val peer = peerGateway.list(settings).firstOrNull { it.publicKey == publicKey }
            val policy = peer?.let { peerGateway.accessPolicy(publicKey) }
            Triple(peer, policy, clock.now())
        }.onSuccess { (peer, policy, observedAt) ->
            _state.value = _state.value.copy(
                peer = peer,
                initialLoading = false,
                refreshing = false,
                notFound = peer == null,
                accessPolicy = policy,
                historySuppressed = policy?.historyEnabled == false,
                observedAtEpochSeconds = observedAt,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                initialLoading = false,
                refreshing = false,
                loadError = if (cached == null) error.safeMessage() else null,
                refreshError = if (cached != null) error.safeMessage() else null,
            )
        }
        } finally {
            peerRefreshMutex.unlock()
        }
    }

    private suspend fun performStatsRefresh(publicKey: String, waitForPrevious: Boolean = false) {
        if (_state.value.historySuppressed) {
            _state.value = _state.value.copy(collectorLoading = false, collectorRefreshing = false, collectorError = null)
            return
        }
        if (waitForPrevious) {
            statsRefreshMutex.lock()
        } else if (!statsRefreshMutex.tryLock()) {
            return
        }
        try {
            val cached = _state.value.stats
            val requestedRange = _state.value.selectedRange
            _state.value = _state.value.copy(
                collectorLoading = cached == null,
                collectorRefreshing = cached != null,
                collectorError = null,
            )
            runCatching {
                statsGateway.history(
                    settingsGateway.settings(),
                    publicKey,
                    requestedRange,
                    clock.now(),
                )
            }.onSuccess { stats ->
                if (_state.value.selectedRange == requestedRange) {
                    _state.value = _state.value.copy(
                        stats = stats,
                        collectorLoading = false,
                        collectorRefreshing = false,
                        collectorLastUpdated = clock.now(),
                    )
                }
            }.onFailure {
                if (_state.value.selectedRange == requestedRange) {
                    _state.value = _state.value.copy(
                        collectorLoading = false,
                        collectorRefreshing = false,
                        collectorError = COLLECTOR_REFRESH_ERROR,
                    )
                }
            }
        } finally {
            statsRefreshMutex.unlock()
        }
    }

    fun rename(publicKey: String, name: String) = act("rename") { settings ->
        peerGateway.rename(settings, publicKey, name)
        reloadPeer(settings, publicKey)
    }

    fun setEnabled(publicKey: String, enabled: Boolean) = act("set-enabled") { settings ->
        peerGateway.setEnabled(settings, publicKey, enabled)
        reloadPeer(settings, publicKey)
    }

    fun regenerate(publicKey: String) = viewModelScope.launch {
        _state.value = _state.value.copy(operation = "regenerate", refreshError = null)
        runCatching {
            val settings = settingsGateway.settings()
            val result = peerGateway.regenerate(settings, publicKey)
            _state.value = _state.value.copy(peer = result.peer, conf = result.conf)
            savedStateHandle[PENDING_NAVIGATION_KEY] = result.peer.publicKey
        }.onFailure { error ->
            if (error is RouterMutationError.LocalFinalization && error.newPublicKey != null) {
                savedStateHandle[PENDING_NAVIGATION_KEY] = error.newPublicKey
            }
            _state.value = _state.value.copy(refreshError = error.safeMessage())
        }
        _state.value = _state.value.copy(operation = null)
    }

    fun delete(publicKey: String, onDone: () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(operation = "delete", refreshError = null)
        runCatching {
            peerGateway.remove(settingsGateway.settings(), publicKey)
        }.onSuccess { onDone() }.onFailure { error ->
            _state.value = _state.value.copy(refreshError = error.safeMessage())
        }
        _state.value = _state.value.copy(operation = null)
    }

    fun showConf(publicKey: String) = viewModelScope.launch {
        val conf = peerGateway.confFor(publicKey)
        _state.value = _state.value.copy(
            conf = conf,
            refreshError = if (conf == null) "На этом телефоне нет сохранённой конфигурации." else null,
        )
    }

    fun clearConf() {
        _state.value = _state.value.copy(conf = null)
    }

    fun acknowledgeNavigation(publicKey: String) {
        if (pendingNavigation.value == publicKey) savedStateHandle[PENDING_NAVIGATION_KEY] = null
    }

    private suspend fun reloadPeer(settings: ServerSettings, publicKey: String) {
        _state.value = _state.value.copy(
            peer = peerGateway.list(settings).firstOrNull { it.publicKey == publicKey },
        )
    }

    private fun act(name: String, block: suspend (ServerSettings) -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(operation = name, refreshError = null)
        runCatching { block(settingsGateway.settings()) }
            .onFailure { error -> _state.value = _state.value.copy(refreshError = error.safeMessage()) }
        _state.value = _state.value.copy(operation = null)
    }
}

private fun servicePeerGateway(): PeerDetailPeerGateway = object : PeerDetailPeerGateway {
    override fun cached(publicKey: String) =
        ServiceLocator.repository.cachedPeers.value.firstOrNull { it.publicKey == publicKey }
    override suspend fun list(settings: ServerSettings) = ServiceLocator.repository.list(settings)
    override suspend fun rename(settings: ServerSettings, publicKey: String, name: String) =
        ServiceLocator.repository.rename(settings, publicKey, name)
    override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) =
        ServiceLocator.repository.setEnabled(settings, publicKey, enabled)
    override suspend fun regenerate(settings: ServerSettings, publicKey: String) =
        ServiceLocator.repository.regenerate(settings, publicKey)
    override suspend fun remove(settings: ServerSettings, publicKey: String) =
        ServiceLocator.repository.remove(settings, publicKey)
    override suspend fun confFor(publicKey: String) = ServiceLocator.repository.confFor(publicKey)
    override suspend fun accessPolicy(publicKey: String) = ServiceLocator.repository.accessPolicyFor(publicKey)
}

private fun serviceStatsGateway() = PeerDetailStatsGateway { settings, publicKey, range, now ->
    val ids = collectorPeerIds(
        settings.interfaceId,
        publicKey,
        ServiceLocator.lineageStore.idsFor(publicKey),
    )
    ServiceLocator.statsGateway.history(settings, ids, historyRange(range, now), now)
}

internal fun collectorPeerIds(interfaceId: String, publicKey: String, lineageIds: List<String>): List<String> =
    (lineageIds + PeerId.compute(interfaceId, publicKey)).distinct()

internal fun historyRange(range: PeerHistoryRange, now: Long): HistoryRange {
    val safeTo = now.coerceAtLeast(1L)
    val seconds = when (range) {
        PeerHistoryRange.DAY -> 86_400L
        PeerHistoryRange.WEEK -> 7L * 86_400L
        PeerHistoryRange.MONTH -> 30L * 86_400L
    }
    return HistoryRange(
        from = (safeTo - seconds).coerceAtLeast(0),
        to = safeTo,
        resolution = if (range == PeerHistoryRange.DAY) "raw" else "1h",
    )
}

private fun Throwable.safeMessage(): String = message ?: "Не удалось выполнить операцию"

private const val PENDING_NAVIGATION_KEY = "pending_peer_navigation_public_key"
private const val COLLECTOR_REFRESH_ERROR = "Не удалось обновить историю наблюдений."
