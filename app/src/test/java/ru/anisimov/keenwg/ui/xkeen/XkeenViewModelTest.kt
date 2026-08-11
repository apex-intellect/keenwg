package ru.anisimov.keenwg.ui.xkeen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenActiveNode
import ru.anisimov.keenwg.data.xkeen.XkeenDiagnosticReport
import ru.anisimov.keenwg.data.xkeen.XkeenDiagnosticStatus
import ru.anisimov.keenwg.data.xkeen.XkeenNodeDiagnostic
import ru.anisimov.keenwg.data.xkeen.XkeenNode
import ru.anisimov.keenwg.data.xkeen.XkeenOperation
import ru.anisimov.keenwg.data.xkeen.XkeenOperationResult
import ru.anisimov.keenwg.data.xkeen.XkeenOperationState
import ru.anisimov.keenwg.data.xkeen.XkeenRepositoryGateway
import ru.anisimov.keenwg.data.xkeen.XkeenStatus
import ru.anisimov.keenwg.data.xkeen.XkeenSubscription
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class XkeenViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `initialization loads status but never refreshes`() = runTest(dispatcher) {
        val repository = FakeRepository(statuses = ArrayDeque(listOf(status())))

        val viewModel = viewModel(repository)
        advanceUntilIdle()

        assertEquals(1, repository.statusCalls)
        assertEquals(0, repository.refreshCalls)
        assertEquals(7L, viewModel.state.value.status?.stateVersion)
    }

    @Test fun `missing companion shows setup without networking`() = runTest(dispatcher) {
        val repository = FakeRepository()

        val viewModel = viewModel(repository, configured = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.needsSetup)
        assertEquals(0, repository.statusCalls)
    }

    @Test fun `manual refresh preserves active node and receives new ordered list`() = runTest(dispatcher) {
        val repository = FakeRepository(
            statuses = ArrayDeque(listOf(status(listOf("nl1"), "nl1"), status(listOf("nl1", "de", "nl2"), "nl1"))),
            refreshed = operation(XkeenOperationResult.SUCCESS),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.refreshSubscription()
        advanceUntilIdle()

        assertEquals("nl1", viewModel.state.value.status?.active?.id)
        assertEquals(listOf("nl1", "de", "nl2"), viewModel.state.value.status?.subscription?.nodes?.map { it.id })
        assertEquals(1, repository.refreshCalls)
    }

    @Test fun `selection requires confirmation and submits once`() = runTest(dispatcher) {
        val repository = FakeRepository(
            statuses = ArrayDeque(listOf(status(listOf("nl1", "de"), "nl1"), status(listOf("nl1", "de"), "de"))),
            selected = operation(XkeenOperationResult.SUCCESS),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val target = viewModel.state.value.status!!.subscription.nodes.last()

        viewModel.requestSelect(target)
        assertEquals("de", viewModel.state.value.pendingNode?.id)
        assertEquals(0, repository.selectCalls)

        viewModel.confirmSelection()
        viewModel.confirmSelection()
        advanceUntilIdle()

        assertEquals(1, repository.selectCalls)
        assertEquals("de", viewModel.state.value.status?.active?.id)
        assertNull(viewModel.state.value.pendingNode)
    }

    @Test fun `uncertain result blocks mutations until later certain status`() = runTest(dispatcher) {
        val uncertain = operation(XkeenOperationResult.UNCERTAIN)
        val repository = FakeRepository(
            statuses = ArrayDeque(listOf(status(), status(operation = uncertain), status(operation = null))),
            refreshed = uncertain,
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.refreshSubscription()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.blocksMutation)
        assertEquals(1, repository.refreshCalls)

        viewModel.refreshSubscription()
        viewModel.requestSelect(node("de"))
        advanceUntilIdle()
        assertEquals(1, repository.refreshCalls)
        assertNull(viewModel.state.value.pendingNode)

        viewModel.loadStatus()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.blocksMutation)
    }

    @Test fun `status failure keeps previous content as stale`() = runTest(dispatcher) {
        val repository = FakeRepository(statuses = ArrayDeque(listOf(status(), IllegalStateException("offline"))))
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.loadStatus()
        advanceUntilIdle()

        assertEquals(7L, viewModel.state.value.status?.stateVersion)
        assertEquals(7L, viewModel.state.value.staleStatus?.stateVersion)
        assertTrue(viewModel.state.value.message != null)
    }

    @Test fun `diagnostics run only on explicit action and map by node id`() = runTest(dispatcher) {
        val repository = FakeRepository(
            statuses = ArrayDeque(listOf(status())),
            diagnosticReport = XkeenDiagnosticReport(1, 100, listOf(
                XkeenNodeDiagnostic("de", "de.example", 443, null, 2, 19, XkeenDiagnosticStatus.UNREACHABLE),
                XkeenNodeDiagnostic("nl1", "nl1.example", 443, "192.0.2.1", 1, 12, XkeenDiagnosticStatus.REACHABLE),
            )),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(0, repository.diagnosticCalls)

        viewModel.runDiagnostics()
        advanceUntilIdle()

        assertEquals(1, repository.diagnosticCalls)
        assertEquals(XkeenDiagnosticStatus.REACHABLE, viewModel.state.value.diagnostics["nl1"]?.status)
        assertEquals(100L, viewModel.state.value.diagnosticsCheckedAt)
    }

    private fun viewModel(repository: XkeenRepositoryGateway, configured: Boolean = true) =
        XkeenViewModel(flowOf(if (configured) activeProfile() else null), repository)

    private class FakeRepository(
        private val statuses: ArrayDeque<Any> = ArrayDeque(),
        private val refreshed: XkeenOperation = operation(XkeenOperationResult.SUCCESS),
        private val selected: XkeenOperation = operation(XkeenOperationResult.SUCCESS),
        private val diagnosticReport: XkeenDiagnosticReport = XkeenDiagnosticReport(1, 1, emptyList()),
    ) : XkeenRepositoryGateway {
        var statusCalls = 0
        var refreshCalls = 0
        var selectCalls = 0
        var diagnosticCalls = 0

        override suspend fun probe(endpoint: CompanionEndpoint) = nextStatus()
        override suspend fun status(endpoint: CompanionEndpoint): XkeenStatus { statusCalls++; return nextStatus() }
        override suspend fun refreshAndAwait(endpoint: CompanionEndpoint, stateVersion: Long): XkeenOperation { refreshCalls++; return refreshed }
        override suspend fun selectAndAwait(endpoint: CompanionEndpoint, nodeId: String, stateVersion: Long): XkeenOperation { selectCalls++; return selected }
        override suspend fun diagnostics(endpoint: CompanionEndpoint): XkeenDiagnosticReport { diagnosticCalls++; return diagnosticReport }

        private fun nextStatus(): XkeenStatus {
            val value = if (statuses.isEmpty()) status() else statuses.removeFirst()
            if (value is Throwable) throw value
            return value as XkeenStatus
        }
    }

    private companion object {
        fun activeProfile() = ActiveRouterProfile(
            RouterProfile(
                id = "home", displayName = "Home", host = "192.168.1.1", rciPort = 80,
                interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
                dns = "192.168.1.1", mtu = 1380, keepalive = 25,
                companionUrl = "https://192.168.1.1:18779",
                certificatePin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
            RouterSecrets(companionToken = "device-token"),
        )

        fun status(
            ids: List<String> = listOf("nl1", "de"),
            activeId: String = "nl1",
            operation: XkeenOperation? = null,
        ): XkeenStatus = XkeenStatus(
            version = "0.4.0",
            stateVersion = 7,
            active = active(ids.first { it == activeId }),
            subscription = XkeenSubscription(1, false, ids.map(::node)),
            operation = operation,
        )

        fun node(id: String) = XkeenNode(id, id.uppercase(), host = "$id.example", port = 443, fingerprint = "chrome", transport = "tcp", security = "reality", flow = "xtls-rprx-vision", active = id == "nl1")
        fun active(id: String) = XkeenActiveNode(id, id.uppercase(), host = "$id.example", port = 443, fingerprint = "chrome", transport = "tcp", security = "reality", flow = "xtls-rprx-vision", active = true, resolvedIp = "192.0.2.1", confirmedAt = 1, missingFromSubscription = false)
        fun operation(result: XkeenOperationResult) = XkeenOperation("11111111-1111-4111-8111-111111111111", "select", XkeenOperationState.TERMINAL, result, startedAt = 1, finishedAt = 2)
    }
}
