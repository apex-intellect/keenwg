package ru.anisimov.keenwg.data.wireguard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.KeyGenerator
import ru.anisimov.keenwg.data.RouterMutationError
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.crypto.WgKeys
import ru.anisimov.keenwg.data.store.AccessPolicyStore
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.store.ConfStore
import ru.anisimov.keenwg.data.store.LineageStore
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

class CompanionPeerRepositoryTest {
    @Test fun createSendsOnlyPublicKeyAndReturnsPhonePrivateConfiguration() = runTest {
        val keys = WgKeys.generate()
        val serverKey = WgKeys.generate().publicKey
        val client = FakeWireGuard(serverKey)
        val configs = MemoryConfStore()
        val policies = MemoryPolicyStore()
        val repository = CompanionPeerRepository(
            client,
            configs,
            MemoryLineageStore(),
            KeyGenerator { keys },
            policies,
        )
        val policy = AccessPolicy(allowedNetworks = listOf("10.0.0.0/8"), dnsServers = listOf("1.1.1.1"))

        val result = repository.add(activeProfile(), settings(), "phone", "10.8.0.2", policy)

        assertEquals(keys.publicKey, client.reviewed!!.publicKey)
        assertNull(client.reviewed!!.newPublicKey)
        assertFalse(client.reviewed.toString().contains(keys.privateKey))
        assertTrue(result.conf.contains(keys.privateKey))
        assertTrue(result.conf.contains("AllowedIPs = 10.0.0.0/8"))
        assertEquals(policy, policies.get(keys.publicKey))
        assertNull(configs.get(keys.publicKey))
    }

    @Test fun committedResultUsesTheRequestedInterfaceWhenSeveralExist() = runTest {
        val keys = WgKeys.generate()
        val serverKey = WgKeys.generate().publicKey
        val client = FakeWireGuard(serverKey, includeUnrelatedInterface = true)
        val repository = CompanionPeerRepository(
            client,
            MemoryConfStore(),
            MemoryLineageStore(),
            KeyGenerator { keys },
            MemoryPolicyStore(),
        )

        val result = repository.add(activeProfile(), settings(), "phone", "10.8.0.2", null)

        assertEquals(keys.publicKey, result.peer.publicKey)
    }

    @Test fun rolledBackCreateRemovesStagedPrivateConfiguration() = runTest {
        val keys = WgKeys.generate()
        val configs = MemoryConfStore()
        val repository = CompanionPeerRepository(
            FakeWireGuard(WgKeys.generate().publicKey, terminalStatus = "rolled_back"),
            configs,
            MemoryLineageStore(),
            KeyGenerator { keys },
            MemoryPolicyStore(),
        )

        val failure = try {
            repository.add(activeProfile(), settings(), "phone", "10.8.0.2", null)
            error("Expected rollback")
        } catch (failure: RouterMutationError.RolledBack) {
            failure
        }

        assertTrue(failure.message.orEmpty().isNotBlank())
        assertNull(configs.get(keys.publicKey))
    }

    @Test fun uncertainCreateKeepsStagedPrivateConfigurationForRecovery() = runTest {
        val keys = WgKeys.generate()
        val configs = MemoryConfStore()
        val repository = CompanionPeerRepository(
            FakeWireGuard(WgKeys.generate().publicKey, terminalStatus = "uncertain"),
            configs,
            MemoryLineageStore(),
            KeyGenerator { keys },
            MemoryPolicyStore(),
        )

        try {
            repository.add(activeProfile(), settings(), "phone", "10.8.0.2", null)
            error("Expected uncertain result")
        } catch (_: RouterMutationError.Uncertain) {
            Unit
        }

        assertTrue(configs.get(keys.publicKey).orEmpty().contains(keys.privateKey))
    }

    @Test fun staleApplyIsKnownToBeUncommittedAndRemovesStagedConfiguration() = runTest {
        val keys = WgKeys.generate()
        val configs = MemoryConfStore()
        val stale = XkeenException(XkeenErrorCode.STALE_STATE, "stale")
        val repository = CompanionPeerRepository(
            FakeWireGuard(WgKeys.generate().publicKey, applyFailure = stale),
            configs,
            MemoryLineageStore(),
            KeyGenerator { keys },
            MemoryPolicyStore(),
        )

        val failure = try {
            repository.add(activeProfile(), settings(), "phone", "10.8.0.2", null)
            error("Expected stale state")
        } catch (failure: XkeenException) {
            failure
        }

        assertEquals(XkeenErrorCode.STALE_STATE, failure.code)
        assertNull(configs.get(keys.publicKey))
    }

    private class FakeWireGuard(
        private val serverKey: String,
        private val includeUnrelatedInterface: Boolean = false,
        private val terminalStatus: String = "committed",
        private val applyFailure: Exception? = null,
    ) : CompanionWireGuardGateway {
        var reviewed: CompanionPeerMutation? = null
        private var document = CompanionWireGuardDocument(
            1,
            "wg-v1",
            buildList {
                if (includeUnrelatedInterface) {
                    add(CompanionWireGuardInterface("Wireguard9", WgKeys.generate().publicKey, listOf("10.9.0.1/24"), 51829, 1380, emptyList()))
                }
                add(CompanionWireGuardInterface("Wireguard0", serverKey, listOf("10.8.0.1/24"), 51820, 1380, emptyList()))
            },
        )
        override suspend fun load(endpoint: CompanionEndpoint) = document
        override suspend fun review(endpoint: CompanionEndpoint, request: CompanionPeerMutation): CompanionPeerPlan {
            reviewed = request
            return CompanionPeerPlan(1, "plan-1", request = request)
        }
        override suspend fun apply(endpoint: CompanionEndpoint, request: CompanionPeerMutation, planId: String): CompanionWireGuardMutationResult {
            applyFailure?.let { throw it }
            if (terminalStatus != "committed") {
                return CompanionWireGuardMutationResult(1, terminalStatus, wireguard = document)
            }
            val peer = CompanionWireGuardPeer(
                requireNotNull(request.publicKey),
                requireNotNull(request.name),
                request.allowedIp,
                request.keepalive ?: 0,
                request.enabled == true,
                false,
                rxBytes = 0,
                txBytes = 0,
            )
            document = document.copy(
                stateVersion = "wg-v2",
                interfaces = document.interfaces.map { iface ->
                    if (iface.id == request.interfaceId) iface.copy(peers = listOf(peer)) else iface
                },
            )
            return CompanionWireGuardMutationResult(1, "committed", wireguard = document)
        }
    }

    private class MemoryConfStore : ConfStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun put(pubkey: String, conf: String) { values[pubkey] = conf }
        override suspend fun get(pubkey: String) = values[pubkey]
        override suspend fun remove(pubkey: String) { values.remove(pubkey) }
    }

    private class MemoryPolicyStore : AccessPolicyStore {
        private val values = mutableMapOf<String, AccessPolicy>()
        override suspend fun put(publicKey: String, policy: AccessPolicy) { values[publicKey] = policy }
        override suspend fun get(publicKey: String) = values[publicKey]
        override suspend fun rotate(oldPublicKey: String, newPublicKey: String, policy: AccessPolicy) { values.remove(oldPublicKey); values[newPublicKey] = policy }
        override suspend fun remove(publicKey: String) { values.remove(publicKey) }
    }

    private class MemoryLineageStore : LineageStore {
        override suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String) = Unit
        override suspend fun idsFor(publicKey: String) = emptyList<String>()
        override suspend fun remove(publicKey: String) = Unit
    }

    private fun activeProfile() = ActiveRouterProfile(
        RouterProfile(
            id = "router", displayName = "Router", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "vpn.example.com:51820",
            subnetBase = "10.8.0.", dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            companionUrl = "https://192.168.1.1:18779", certificatePin = "sha256/test",
        ),
        RouterSecrets(companionToken = "device-token"),
    )

    private fun settings() = ServerSettings(
        password = "",
        interfaceId = "Wireguard0",
        endpoint = "vpn.example.com:51820",
        subnetBase = "10.8.0.",
        dns = "192.168.1.1",
        mtu = 1380,
        keepalive = 25,
    )
}
