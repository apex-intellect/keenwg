package ru.anisimov.keenwg.ui

import kotlinx.coroutines.flow.first
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.domain.LocalAddressProvider
import ru.anisimov.keenwg.domain.NetworkInterfaceLocalAddressProvider
import ru.anisimov.keenwg.domain.NetworkRouterAddressProvider
import ru.anisimov.keenwg.domain.RouterAddressProvider
import ru.anisimov.keenwg.domain.SelfPeerDetector
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings

internal class SelfPeerGuard(
    private val localAddresses: LocalAddressProvider = NetworkInterfaceLocalAddressProvider(),
    private val routerAddresses: RouterAddressProvider = NetworkRouterAddressProvider(),
    private val currentSettings: suspend () -> ServerSettings = {
        ServiceLocator.settingsStore.settings.first()
    },
) {
    /** Re-reads both settings and interfaces at the destructive tap boundary. */
    suspend fun blocks(peer: Peer): Boolean {
        val settings = currentSettings()
        val addresses = localAddresses.ipv4Addresses()
        val router = runCatching { routerAddresses.ipv4Addresses(settings.host) }.getOrNull()
        return blocks(peer, settings, addresses, router)
    }

    /** Uses one current snapshot for non-destructive list decoration. */
    suspend fun unsafeKeys(peers: List<Peer>): Set<String> {
        val settings = currentSettings()
        val addresses = localAddresses.ipv4Addresses()
        val router = runCatching { routerAddresses.ipv4Addresses(settings.host) }.getOrNull()
        return peers.asSequence()
            .filter { blocks(it, settings, addresses, router) }
            .map(Peer::publicKey)
            .toSet()
    }

    private fun blocks(
        peer: Peer,
        settings: ServerSettings,
        addresses: Set<String>,
        router: Set<String>?,
    ): Boolean {
        return SelfPeerDetector.isUnsafe(
            peerIp = peer.ip,
            routerAddresses = router,
            subnetBase = settings.subnetBase,
            localAddresses = addresses,
        )
    }
}

internal const val SELF_PEER_BLOCK_MESSAGE =
    "Это подключение используется сейчас. Переключитесь на домашний Wi‑Fi и укажите LAN-адрес роутера, затем повторите действие."
