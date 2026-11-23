package ru.anisimov.keenwg.ui.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.catalog.CatalogDocument
import ru.anisimov.keenwg.data.catalog.CatalogGateway
import ru.anisimov.keenwg.data.catalog.CatalogGroup
import ru.anisimov.keenwg.data.catalog.CatalogNode
import ru.anisimov.keenwg.data.catalog.CatalogNodeTest
import ru.anisimov.keenwg.data.catalog.CatalogOperation
import ru.anisimov.keenwg.data.catalog.CatalogProtocol
import ru.anisimov.keenwg.data.catalog.CatalogSource
import ru.anisimov.keenwg.data.catalog.CatalogSourceDraft
import ru.anisimov.keenwg.data.catalog.CatalogSourceKind
import ru.anisimov.keenwg.data.catalog.ImportDraftPersistence
import ru.anisimov.keenwg.data.catalog.ImportDraftStore
import ru.anisimov.keenwg.data.catalog.ImportOrigin
import ru.anisimov.keenwg.data.catalog.SourceKind
import ru.anisimov.keenwg.data.catalog.SourceStatus
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.store.SecretCipher
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsShareLinkImportTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `single gRPC reality link survives encrypted draft save refresh and explicit activation`() = runTest(dispatcher) {
        val credential = "11111111-2222-4333-8444-555555555555"
        val link = "vless://$credential@grpc.example:443?encryption=none&type=grpc&security=reality" +
            "&fp=chrome&sni=cdn.example&pbk=public-key&sid=0123456789abcdef" +
            "&serviceName=private%2Fservice&authority=&mode=gun&spx=%2F#Test"
        val persistence = MemoryDraftPersistence()
        val drafts = ImportDraftStore(persistence, TestCipher(), nowMillis = { 1_000 })
        val gateway = ShareLinkGateway()
        var operation = 0
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, drafts,
            nowMillis = { 1_000 }, keyFactory = { "share-link-operation-${++operation}" },
        )
        advanceUntilIdle()
        val input = link.toByteArray()

        vm.previewImport(input, ImportOrigin.CLIPBOARD)

        assertEquals(SourceKind.SHARE_LINK, vm.state.value.pendingImport?.preview?.sourceKind)
        assertEquals("grpc", vm.state.value.pendingImport?.preview?.transport)
        assertTrue(input.all { it == 0.toByte() })
        assertFalse(vm.state.value.toString().contains(credential))
        assertFalse(persistence.value.orEmpty().contains(credential))
        vm.saveImport("Personal gRPC", "primary")
        advanceUntilIdle()

        assertEquals(link, gateway.savedLink)
        assertEquals(SourceKind.SHARE_LINK, gateway.savedDraft?.kind)
        assertEquals("catalog", gateway.savedDraft?.adapterId)
        assertTrue(gateway.savedInput!!.all { it == 0.toByte() })
        assertNull(persistence.value)
        assertNull(vm.state.value.pendingImport)
        assertNull(vm.state.value.messageResource)
        assertEquals(listOf("save", "refresh"), gateway.calls)
        val node = vm.state.value.catalog!!.nodes.single()
        assertEquals("grpc", node.transport)
        assertFalse(node.active)

        vm.requestActivation(node.id)
        assertNull(vm.state.value.pendingActivation)
        vm.testNode(node.id)
        advanceUntilIdle()
        vm.requestActivation(node.id)
        assertNotNull(vm.state.value.pendingActivation)
        assertEquals(listOf("save", "refresh", "test"), gateway.calls)

        vm.confirmActivation()
        advanceUntilIdle()

        assertEquals(listOf("save", "refresh", "test", "activate"), gateway.calls)
        assertEquals(listOf(7uL, 8uL, 9uL, 9uL), gateway.reviewedVersions)
        assertEquals(4, gateway.keys.distinct().size)
        assertTrue(vm.state.value.catalog!!.nodes.single().active)
        assertFalse(vm.state.value.toString().contains(credential))
    }

    private class ShareLinkGateway : CatalogGateway {
        val calls = mutableListOf<String>()
        val reviewedVersions = mutableListOf<ULong>()
        val keys = mutableListOf<String>()
        var savedLink: String? = null
        var savedDraft: CatalogSourceDraft? = null
        var savedInput: ByteArray? = null
        private var catalog = CatalogDocument(1, 7u, listOf(CatalogGroup("primary", "Primary", 0)), emptyList(), emptyList())

        override suspend fun snapshot(profile: RouterProfile, token: String) = catalog
        override suspend fun createGroup(profile: RouterProfile, token: String, stateVersion: ULong, key: String, label: String) = error("unused")
        override suspend fun deleteSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String) = error("unused")

        override suspend fun saveSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, draft: CatalogSourceDraft, source: ByteArray): CatalogOperation {
            record("save", stateVersion, key)
            savedLink = source.toString(Charsets.UTF_8)
            savedDraft = draft
            savedInput = source
            catalog = catalog.copy(stateVersion = stateVersion + 1u, sources = listOf(
                CatalogSource("owned-source", draft.groupId, CatalogSourceKind.SHARE_LINK, draft.label, draft.adapterId,
                    SourceStatus.STALE, 0, warnings = emptyList(), foreign = false),
            ))
            return CatalogOperation(1, "committed", catalog)
        }

        override suspend fun refreshSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String): CatalogOperation {
            record("refresh", stateVersion, key)
            assertEquals("owned-source", sourceId)
            catalog = catalog.copy(stateVersion = stateVersion + 1u,
                sources = catalog.sources.map { it.copy(status = SourceStatus.READY, nodeCount = 1) },
                nodes = listOf(CatalogNode("owned-node", sourceId, "primary", "Personal gRPC", protocol = CatalogProtocol.VLESS,
                    host = "grpc.example", port = 443, transport = "grpc", security = "reality", flow = "",
                    active = false, testable = true, activatable = true, warnings = emptyList())),
            )
            return CatalogOperation(1, "committed", catalog)
        }

        override suspend fun testNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String): CatalogOperation {
            record("test", stateVersion, key)
            assertEquals("owned-node", nodeId)
            return CatalogOperation(1, "committed", catalog, CatalogNodeTest(nodeId, true, 42, observedAt = "2026-11-23T00:00:00Z"))
        }

        override suspend fun activateNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String): CatalogOperation {
            record("activate", stateVersion, key)
            assertEquals("owned-node", nodeId)
            catalog = catalog.copy(stateVersion = stateVersion + 1u, nodes = catalog.nodes.map { it.copy(active = it.id == nodeId) })
            return CatalogOperation(1, "committed", catalog)
        }

        private fun record(action: String, version: ULong, key: String) {
            assertEquals(catalog.stateVersion, version)
            calls += action
            reviewedVersions += version
            keys += key
        }
    }

    private class MemoryDraftPersistence : ImportDraftPersistence {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private class TestCipher : SecretCipher {
        override fun encrypt(plain: String) = "encrypted:${plain.reversed()}"
        override fun decrypt(blob: String) = blob.removePrefix("encrypted:").reversed()
    }

    private fun activeProfile() = ActiveRouterProfile(
        RouterProfile(id = "router", displayName = "Router", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "key", endpoint = "vpn.example:1", subnetBase = "10.0.0",
            dns = "1.1.1.1", mtu = 1420, keepalive = 25, companionUrl = "https://router.example:8443", certificatePin = "sha256/pin"),
        RouterSecrets(companionToken = "token"),
    )
}
