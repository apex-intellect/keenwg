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
import ru.anisimov.keenwg.data.catalog.CatalogErrorCode
import ru.anisimov.keenwg.data.catalog.CatalogException
import ru.anisimov.keenwg.data.catalog.CatalogGateway
import ru.anisimov.keenwg.data.catalog.CatalogGroup
import ru.anisimov.keenwg.data.catalog.CatalogNode
import ru.anisimov.keenwg.data.catalog.CatalogNodeTest
import ru.anisimov.keenwg.data.catalog.CatalogOperation
import ru.anisimov.keenwg.data.catalog.CatalogProtocol
import ru.anisimov.keenwg.data.catalog.CatalogSource
import ru.anisimov.keenwg.data.catalog.CatalogSourceDraft
import ru.anisimov.keenwg.data.catalog.CatalogSourceKind
import ru.anisimov.keenwg.data.catalog.ImportDraftGateway
import ru.anisimov.keenwg.data.catalog.ImportOrigin
import ru.anisimov.keenwg.data.catalog.SourceStatus
import ru.anisimov.keenwg.data.catalog.SourceConfigurationGateway
import ru.anisimov.keenwg.data.catalog.SourceConfigurationStatus
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `initial load reads catalog without refreshing sources`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val vm = ConnectionsViewModel(flowOf(activeProfile()), gateway, FakeImportDrafts(), { 1_000 }, { "operation-key-0001" })
        advanceUntilIdle()
        assertEquals(1, gateway.snapshotCalls)
        assertEquals(0, gateway.refreshCalls)
        assertEquals(listOf("node-nl-1", "node-nl-2"), vm.state.value.catalog!!.nodes.map { it.id })
    }

    @Test fun `catalog load exposes typed failure and retry recovers`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway().apply {
            snapshotFailure = CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        }
        val vm = ConnectionsViewModel(flowOf(activeProfile()), gateway, FakeImportDrafts(), { 1_000 }, { "operation-key-0001" })
        advanceUntilIdle()

        assertNull(vm.state.value.catalog)
        assertFalse(vm.state.value.loading)
        assertEquals(CatalogErrorCode.UNSUPPORTED_SCHEMA, vm.state.value.loadError)
        assertNull(vm.state.value.messageResource)

        gateway.snapshotFailure = null
        vm.loadCatalog()
        advanceUntilIdle()

        assertNotNull(vm.state.value.catalog)
        assertNull(vm.state.value.loadError)
        assertNull(vm.state.value.messageResource)
        assertEquals(2, gateway.snapshotCalls)
    }

    @Test fun `unconfigured XKeen source asks for link instead of refreshing`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val configuration = FakeSourceConfigurationGateway(configured = false)
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, FakeImportDrafts(),
            nowMillis = { 1_000 }, keyFactory = { "operation-key-0001" }, sourceConfigurations = configuration,
        )
        advanceUntilIdle()

        assertEquals(false, vm.state.value.sourceConfiguration["xkeen-subscription"]?.configured)
        vm.refreshSource("xkeen-subscription")

        assertEquals("xkeen-subscription", vm.state.value.editingSubscriptionSourceId)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test fun `saving subscription link refreshes catalog and clears caller bytes`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val configuration = FakeSourceConfigurationGateway(configured = false)
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, FakeImportDrafts(),
            nowMillis = { 1_000 }, keyFactory = { "operation-key-0001" }, sourceConfigurations = configuration,
        )
        advanceUntilIdle()
        vm.refreshSource("xkeen-subscription")
        val secret = "https://vpn.example.test/sub/private".toByteArray()

        vm.saveSubscriptionLink(secret)
        advanceUntilIdle()

        assertTrue(secret.all { it == 0.toByte() })
        assertEquals(1, configuration.replaceCalls)
        assertEquals(1, gateway.refreshCalls)
        assertNull(vm.state.value.editingSubscriptionSourceId)
        assertEquals(ConnectionNotice.SubscriptionUpdated(1), vm.state.value.notice)
    }

    @Test fun `backend missing link result opens editor without generic failure`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway().apply { refreshError = "subscription_not_configured" }
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, FakeImportDrafts(),
            nowMillis = { 1_000 }, keyFactory = { "operation-key-0001" },
        )
        advanceUntilIdle()

        vm.refreshSource("xkeen-subscription")
        advanceUntilIdle()

        assertEquals("xkeen-subscription", vm.state.value.editingSubscriptionSourceId)
        assertEquals(false, vm.state.value.sourceConfiguration["xkeen-subscription"]?.configured)
        assertNull(vm.state.value.notice)
    }

    @Test fun `activation requires fresh reachable test for exact node and version`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val vm = ConnectionsViewModel(flowOf(activeProfile()), gateway, FakeImportDrafts(), { 1_000 }, { "operation-key-0001" })
        advanceUntilIdle()
        vm.requestActivation("node-nl-2")
        assertNull(vm.state.value.pendingActivation)

        vm.testNode("node-nl-2")
        advanceUntilIdle()
        vm.requestActivation("node-nl-2")
        assertNotNull(vm.state.value.pendingActivation)
        assertEquals(0, gateway.activateCalls)

        vm.confirmActivation()
        advanceUntilIdle()
        assertEquals(1, gateway.activateCalls)
        assertFalse(vm.state.value.catalog!!.nodes.first { it.id == "node-nl-2" }.active.not())
    }

    @Test fun `presentation keeps duplicate country entries separate`() {
        val cards = connectionCards(document(), null)
        assertEquals(listOf("node-nl-1", "node-nl-2"), cards.map { it.id })
        assertEquals(listOf("Нидерланды 1", "Нидерланды 2"), cards.map { it.title })
    }

    @Test fun `import saves safely and immediately loads the new source without activating`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val drafts = FakeImportDrafts()
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, drafts,
            nowMillis = { 1_000 }, keyFactory = { "operation-key-0001" },
        )
        advanceUntilIdle()
        val secret = "vless://11111111-2222-4333-8444-555555555555@server.example:443?type=tcp&security=reality&sni=cdn.example&pbk=public-key".toByteArray()

        vm.previewImport(secret, ImportOrigin.CLIPBOARD)

        assertEquals("server.example", vm.state.value.pendingImport?.preview?.host)
        assertFalse(vm.state.value.pendingImport!!.duplicateWarning)
        assertFalse(vm.state.value.pendingImport.toString().contains("11111111-2222-4333-8444-555555555555"))
        assertEquals(true, secret.all { it == 0.toByte() })
        vm.saveImport("", "primary")
        advanceUntilIdle()

        assertEquals(1, gateway.saveCalls)
        assertEquals("server.example", gateway.lastSavedDraft?.label)
        assertEquals(1, gateway.refreshCalls)
        assertEquals(0, gateway.activateCalls)
        assertNull(vm.state.value.pendingImport)
        assertEquals(SourceStatus.READY, vm.state.value.catalog!!.sources.last().status)
        assertEquals("owned-node", vm.state.value.catalog!!.nodes.last().id)
        assertNull(vm.state.value.notice)
    }

    @Test fun `preview warns when normalized endpoint already exists`() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(flowOf(activeProfile()), FakeCatalogGateway(), FakeImportDrafts(), { 1_000 }, { "operation-key-0001" })
        advanceUntilIdle()
        vm.previewImport("vless://11111111-2222-4333-8444-555555555555@vpn1.example:443?type=tcp&security=reality&sni=cdn.example&pbk=public-key".toByteArray(), ImportOrigin.CLIPBOARD)
        assertEquals(true, vm.state.value.pendingImport?.duplicateWarning)
    }

    @Test fun `cancel and expired draft clear preview without saving`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway()
        val drafts = FakeImportDrafts()
        val vm = ConnectionsViewModel(flowOf(activeProfile()), gateway, drafts, { 1_000 }, { "operation-key-0001" })
        advanceUntilIdle()
        val source = "vless://11111111-2222-4333-8444-555555555555@new.example:443?type=tcp&security=reality&sni=cdn.example&pbk=public-key".toByteArray()
        vm.previewImport(source, ImportOrigin.CLIPBOARD)
        vm.cancelImport()
        assertNull(vm.state.value.pendingImport)
        assertEquals(1, drafts.clearCalls)

        vm.previewImport("vless://11111111-2222-4333-8444-555555555555@new.example:443?type=tcp&security=reality&sni=cdn.example&pbk=public-key".toByteArray(), ImportOrigin.CLIPBOARD)
        drafts.expired = true
        vm.saveImport("Expired", "primary")
        advanceUntilIdle()
        assertNull(vm.state.value.pendingImport)
        assertEquals(0, gateway.saveCalls)
    }

    @Test fun `failed automatic subscription load keeps a retryable source`() = runTest(dispatcher) {
        val gateway = FakeCatalogGateway().apply { refreshError = "subscription_download_failed" }
        val vm = ConnectionsViewModel(
            flowOf(activeProfile()), gateway, FakeImportDrafts(),
            nowMillis = { 1_000 }, keyFactory = { "operation-key-0001" },
        )
        advanceUntilIdle()
        vm.previewImport("https://provider.example/private-subscription".toByteArray(), ImportOrigin.CLIPBOARD)

        vm.saveImport("", "primary")
        advanceUntilIdle()

        val source = vm.state.value.catalog!!.sources.last()
        assertEquals(SourceStatus.STALE, source.status)
        assertEquals(ConnectionNotice.SubscriptionDownloadFailed, vm.state.value.notice)
        assertTrue(sourceCanRefresh(source))
    }
}

private class FakeCatalogGateway : CatalogGateway {
    var snapshotCalls = 0
    var snapshotFailure: RuntimeException? = null
    var refreshCalls = 0
    var activateCalls = 0
    var saveCalls = 0
    var lastSavedDraft: CatalogSourceDraft? = null
    var refreshError: String? = null
    private var catalog = document()
    override suspend fun snapshot(profile: RouterProfile, token: String): CatalogDocument {
        snapshotCalls++
        snapshotFailure?.let { throw it }
        return catalog
    }

    override suspend fun refreshSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String): CatalogOperation {
        refreshCalls++
        refreshError?.let { return CatalogOperation(1, "rejected", catalog, error = it) }
        return CatalogOperation(1, "committed", catalog.copy(
            stateVersion = catalog.stateVersion + 1u,
            sources = catalog.sources.map { if (it.id == sourceId) it.copy(status = SourceStatus.READY, nodeCount = 1) else it },
            nodes = catalog.nodes + CatalogNode("owned-node", sourceId, "primary", "Личный VPN", "NL", CatalogProtocol.VLESS, "owned.example", 443, active = false, testable = true, activatable = true, warnings = emptyList()),
        )).also { catalog = it.catalog!! }
    }
    override suspend fun testNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String) =
        CatalogOperation(1, "committed", catalog, CatalogNodeTest(nodeId, true, 42, observedAt = "2026-08-09T00:00:00Z"))
    override suspend fun activateNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String): CatalogOperation {
        activateCalls++
        catalog = catalog.copy(stateVersion = catalog.stateVersion + 1u, nodes = catalog.nodes.map { it.copy(active = it.id == nodeId) })
        return CatalogOperation(1, "committed", catalog)
    }
    override suspend fun createGroup(profile: RouterProfile, token: String, stateVersion: ULong, key: String, label: String) = error("unused")
    override suspend fun saveSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, draft: CatalogSourceDraft, source: ByteArray): CatalogOperation {
        saveCalls++
        lastSavedDraft = draft
        catalog = catalog.copy(
            stateVersion = catalog.stateVersion + 1u,
            sources = catalog.sources + CatalogSource("owned-source", draft.groupId, when (draft.kind) {
                ru.anisimov.keenwg.data.catalog.SourceKind.SUBSCRIPTION -> CatalogSourceKind.SUBSCRIPTION
                else -> CatalogSourceKind.SHARE_LINK
            }, draft.label, "catalog", SourceStatus.STALE, 0, warnings = emptyList(), foreign = false),
        )
        return CatalogOperation(1, "committed", catalog)
    }
    override suspend fun deleteSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String): CatalogOperation {
        catalog = catalog.copy(
            stateVersion = catalog.stateVersion + 1u,
            sources = catalog.sources.filterNot { it.id == sourceId },
            nodes = catalog.nodes.filterNot { it.sourceId == sourceId },
        )
        return CatalogOperation(1, "committed", catalog)
    }
}

private class FakeSourceConfigurationGateway(configured: Boolean) : SourceConfigurationGateway {
    var current = SourceConfigurationStatus(configured)
    var replaceCalls = 0

    override suspend fun status(profile: RouterProfile, token: String, sourceId: String) = current

    override suspend fun replace(
        profile: RouterProfile,
        token: String,
        sourceId: String,
        subscriptionUrl: ByteArray,
    ): SourceConfigurationStatus {
        replaceCalls++
        subscriptionUrl.fill(0)
        current = SourceConfigurationStatus(true)
        return current
    }
}

private class FakeImportDrafts : ImportDraftGateway {
    private var value: ByteArray? = null
    var expired = false
    var clearCalls = 0
    override fun put(source: ByteArray) { value = source.copyOf(); source.fill(0) }
    override fun take(): ByteArray? {
        if (expired) { clear(); return null }
        return value?.also { value = null }
    }
    override fun clear() { clearCalls++; value?.fill(0); value = null }
}

private fun activeProfile() = ActiveRouterProfile(
    RouterProfile(id = "router", displayName = "Router", host = "192.168.1.1", rciPort = 80, interfaceId = "Wireguard0", serverPublicKey = "key", endpoint = "vpn.example:1", subnetBase = "10.0.0", dns = "1.1.1.1", mtu = 1420, keepalive = 25, companionUrl = "https://router.example:8443", certificatePin = "sha256/pin"),
    RouterSecrets(companionToken = "token"),
)

private fun document() = CatalogDocument(
    1, 7u, listOf(CatalogGroup("primary", "Основные", 0)),
    listOf(CatalogSource("xkeen-subscription", "primary", CatalogSourceKind.FOREIGN, "XKeen", "xkeen", SourceStatus.READY, 2, warnings = emptyList(), foreign = true, adapterStateVersion = 7u)),
    listOf("node-nl-1", "node-nl-2").mapIndexed { index, id -> CatalogNode(id, "xkeen-subscription", "primary", "Нидерланды ${index + 1}", "NL", CatalogProtocol.VLESS, "vpn${index + 1}.example", 443, active = index == 0, testable = true, activatable = true, warnings = emptyList()) },
)
