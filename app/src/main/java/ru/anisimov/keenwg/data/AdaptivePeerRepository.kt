package ru.anisimov.keenwg.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.anisimov.keenwg.data.network.isPaired
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.wireguard.CompanionPeerGateway
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings

class AdaptivePeerRepository(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val companion: CompanionPeerGateway,
    private val legacy: PeerRepositoryGateway,
) : PeerRepositoryGateway {
    private data class CacheScope(val profileId: String?, val settings: ServerSettings)

    private val _cachedPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val cachedPeers: StateFlow<List<Peer>> = _cachedPeers.asStateFlow()
    private val cacheMutex = Mutex()
    private var cacheScope: CacheScope? = null

    override suspend fun cached(settings: ServerSettings): List<Peer> {
        val active = activeProfile.first()
        return cacheMutex.withLock {
            _cachedPeers.value.takeIf {
                cacheScope == CacheScope(active?.profile?.id, settings)
            }.orEmpty()
        }
    }

    override suspend fun list(settings: ServerSettings): List<Peer> {
        val active = activeProfile.first()
        return route(
            active = active,
            paired = { companion.list(it, settings) },
            legacy = { legacy.list(settings) },
        ).also { storeCache(active, settings, it) }
    }

    override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy?): AddResult =
        route(
            paired = { companion.add(it, settings, name, ip, policy) },
            legacy = { legacy.add(settings, name, ip, policy) },
        ).also { refreshCache(settings) }

    override suspend fun regenerate(settings: ServerSettings, publicKey: String): AddResult =
        route(
            paired = { companion.regenerate(it, settings, publicKey) },
            legacy = { legacy.regenerate(settings, publicKey) },
        ).also { refreshCache(settings) }

    override suspend fun remove(settings: ServerSettings, publicKey: String) {
        route(
            paired = { companion.remove(it, settings, publicKey) },
            legacy = { legacy.remove(settings, publicKey) },
        )
        refreshCache(settings)
    }

    override suspend fun rename(settings: ServerSettings, publicKey: String, newName: String) {
        route(
            paired = { companion.rename(it, settings, publicKey, newName) },
            legacy = { legacy.rename(settings, publicKey, newName) },
        )
        refreshCache(settings)
    }

    override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) {
        route(
            paired = { companion.setEnabled(it, settings, publicKey, enabled) },
            legacy = { legacy.setEnabled(settings, publicKey, enabled) },
        )
        refreshCache(settings)
    }

    override suspend fun confFor(publicKey: String): String? = route(
        paired = { companion.confFor(publicKey) },
        legacy = { legacy.confFor(publicKey) },
    )

    override suspend fun accessPolicyFor(publicKey: String): AccessPolicy? = route(
        paired = { companion.accessPolicyFor(publicKey) },
        legacy = { legacy.accessPolicyFor(publicKey) },
    )

    private suspend fun refreshCache(settings: ServerSettings) {
        val active = activeProfile.first()
        val peers = route(
            active = active,
            paired = { companion.list(it, settings) },
            legacy = { legacy.list(settings) },
        )
        storeCache(active, settings, peers)
    }

    private suspend fun storeCache(
        active: ActiveRouterProfile?,
        settings: ServerSettings,
        peers: List<Peer>,
    ) = cacheMutex.withLock {
        cacheScope = CacheScope(active?.profile?.id, settings)
        _cachedPeers.value = peers
    }

    private suspend fun <T> route(
        paired: suspend (ActiveRouterProfile) -> T,
        legacy: suspend () -> T,
    ): T = route(activeProfile.first(), paired, legacy)

    private suspend fun <T> route(
        active: ActiveRouterProfile?,
        paired: suspend (ActiveRouterProfile) -> T,
        legacy: suspend () -> T,
    ): T {
        return if (active.isPaired()) paired(requireNotNull(active)) else legacy()
    }
}
