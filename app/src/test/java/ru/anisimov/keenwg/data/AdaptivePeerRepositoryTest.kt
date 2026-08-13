package ru.anisimov.keenwg.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.wireguard.CompanionPeerGateway
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

class AdaptivePeerRepositoryTest {
    @Test fun pairedProfileLoadsCompanionPeersWithoutRciCredentials() = runTest {
        val companion = FakeCompanionPeers()
        val legacy = FakeLegacyPeers()
        val repository = AdaptivePeerRepository(flowOf(activeProfile(true)), companion, legacy)

        val peers = repository.list(ServerSettings(password = ""))
        assertEquals(6, peers.size)
        assertEquals(1, companion.loads)
        assertEquals(0, legacy.loads)
    }

    @Test fun companionErrorRemainsVisibleAndDoesNotCallRci() = runTest {
        val companion = FakeCompanionPeers(fail = true)
        val legacy = FakeLegacyPeers()
        val repository = AdaptivePeerRepository(flowOf(activeProfile(true)), companion, legacy)

        runCatching { repository.list(ServerSettings()) }
        assertEquals(0, legacy.loads)
    }

    @Test fun unpairedProfileUsesLegacyRepository() = runTest {
        val companion = FakeCompanionPeers()
        val legacy = FakeLegacyPeers()
        val repository = AdaptivePeerRepository(flowOf(activeProfile(false)), companion, legacy)

        repository.list(ServerSettings())
        assertEquals(0, companion.loads)
        assertEquals(1, legacy.loads)
    }

    @Test fun cachedPeersAreNeverReusedForAnotherRouterProfile() = runTest {
        val profiles = MutableStateFlow(activeProfile(true, id = "router-a"))
        val repository = AdaptivePeerRepository(profiles, FakeCompanionPeers(), FakeLegacyPeers())
        val settings = ServerSettings(host = "192.168.1.1", password = "")

        val loaded = repository.list(settings)
        assertEquals(6, repository.cached(settings).size)

        profiles.value = activeProfile(true, id = "router-b")

        assertEquals(emptyList<Peer>(), repository.cached(settings))
        assertEquals(6, loaded.size)
    }

    private class FakeCompanionPeers(private val fail: Boolean = false) : CompanionPeerGateway {
        override val cachedPeers = MutableStateFlow<List<Peer>>(emptyList())
        var loads = 0
        override suspend fun list(active: ActiveRouterProfile, settings: ServerSettings): List<Peer> {
            loads++
            if (fail) error("companion unavailable")
            return (1..6).map { testPeer("key-$it") }.also { cachedPeers.value = it }
        }
        override suspend fun add(active: ActiveRouterProfile, settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy?) = error("unused")
        override suspend fun regenerate(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String) = error("unused")
        override suspend fun remove(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String) = Unit
        override suspend fun rename(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, newName: String) = Unit
        override suspend fun setEnabled(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, enabled: Boolean) = Unit
        override suspend fun confFor(publicKey: String): String? = null
        override suspend fun accessPolicyFor(publicKey: String): AccessPolicy? = null
    }

    private class FakeLegacyPeers : PeerRepositoryGateway {
        override val cachedPeers: StateFlow<List<Peer>> = MutableStateFlow(emptyList())
        var loads = 0
        override suspend fun list(settings: ServerSettings): List<Peer> {
            loads++
            return emptyList()
        }
        override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy?) = error("unused")
        override suspend fun regenerate(settings: ServerSettings, publicKey: String) = error("unused")
        override suspend fun remove(settings: ServerSettings, publicKey: String) = Unit
        override suspend fun rename(settings: ServerSettings, publicKey: String, newName: String) = Unit
        override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) = Unit
        override suspend fun confFor(publicKey: String): String? = null
        override suspend fun accessPolicyFor(publicKey: String): AccessPolicy? = null
    }

    private fun activeProfile(paired: Boolean, id: String = "router") = ActiveRouterProfile(
        RouterProfile(
            id = id, displayName = "Router", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            companionUrl = if (paired) "https://192.168.1.1:18779" else "",
            certificatePin = if (paired) "sha256/test" else "",
        ),
        RouterSecrets(companionToken = if (paired) "device-token" else ""),
    )

    private companion object {
        fun testPeer(key: String) = Peer(key, key, "10.8.0.2", false, HandshakeStatus(HandshakeKind.NEVER), 0, 0, true)
    }
}
