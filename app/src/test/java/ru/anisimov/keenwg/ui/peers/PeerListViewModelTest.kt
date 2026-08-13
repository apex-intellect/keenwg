package ru.anisimov.keenwg.ui.peers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PeerListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val peerA = peer("A", true)
    private val peerB = peer("B", true)

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `refresh keeps old peers visible while request is running`() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<Peer>>()
        val gateway = QueueGateway(mutableListOf({ listOf(peerA) }, { gate.await() }))
        val vm = viewModel(gateway)
        vm.refresh(); advanceUntilIdle()

        vm.refresh(); runCurrent()
        assertEquals(listOf(peerA), vm.state.value.peers)
        assertTrue(vm.state.value.refreshing)
        gate.complete(listOf(peerA, peerB)); advanceUntilIdle()
        assertEquals(listOf(peerA, peerB), vm.state.value.peers)
    }

    @Test fun `refresh failure keeps content and records freshness error`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf({ listOf(peerA) }, { throw IOException("offline") }))
        val vm = viewModel(gateway)

        vm.refresh(); advanceUntilIdle(); vm.refresh(); advanceUntilIdle()

        assertEquals(listOf(peerA), vm.state.value.peers)
        assertEquals(PeerListError.UNAVAILABLE, vm.state.value.refreshError)
    }

    @Test fun `cached peers are visible before the router answers`() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<Peer>>()
        val vm = viewModel(
            gateway = QueueGateway(
                results = mutableListOf({ gate.await() }),
                cached = listOf(peerA),
            ),
        )

        vm.refresh()
        runCurrent()

        assertEquals(listOf(peerA), vm.state.value.peers)
        assertFalse(vm.state.value.initialLoading)
        assertTrue(vm.state.value.refreshing)
        gate.complete(listOf(peerA, peerB))
        advanceUntilIdle()
    }

    @Test fun `single transient background failure keeps content without alarming the user`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf({ listOf(peerA) }, { throw IOException("temporary") }))
        val vm = viewModel(gateway)
        val refreshJob = vm.startForegroundRefresh()
        runCurrent()

        advanceTimeBy(15_000L)
        runCurrent()
        refreshJob.cancel()

        assertEquals(listOf(peerA), vm.state.value.peers)
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.refreshError)
    }

    @Test fun `repeated background failure reports stale content`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf(
            { listOf(peerA) },
            { throw IOException("temporary") },
            { throw IOException("still offline") },
        ))
        val vm = viewModel(gateway)
        val refreshJob = vm.startForegroundRefresh()
        runCurrent()

        advanceTimeBy(15_000L)
        runCurrent()
        assertNull(vm.state.value.refreshError)
        advanceTimeBy(15_000L)
        runCurrent()
        refreshJob.cancel()

        assertEquals(PeerListError.UNAVAILABLE, vm.state.value.refreshError)
    }

    @Test fun `reported background outage is not cleared while the next check is running`() = runTest(dispatcher) {
        val nextCheck = CompletableDeferred<List<Peer>>()
        val gateway = QueueGateway(mutableListOf(
            { listOf(peerA) },
            { throw IOException("temporary") },
            { throw IOException("still offline") },
            { nextCheck.await() },
        ))
        val vm = viewModel(gateway)
        val refreshJob = vm.startForegroundRefresh()
        try {
            runCurrent()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(PeerListError.UNAVAILABLE, vm.state.value.refreshError)

            advanceTimeBy(15_000L)
            runCurrent()

            assertTrue(vm.state.value.refreshing)
            assertEquals(PeerListError.UNAVAILABLE, vm.state.value.refreshError)
        } finally {
            refreshJob.cancel()
        }
    }

    @Test fun `background authorization failure asks to reconnect immediately`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf(
            { listOf(peerA) },
            { throw XkeenException(XkeenErrorCode.UNAUTHORIZED, "revoked") },
        ))
        val vm = viewModel(gateway)
        val refreshJob = vm.startForegroundRefresh()
        runCurrent()

        advanceTimeBy(15_000L)
        runCurrent()
        refreshJob.cancel()

        assertEquals(PeerListError.RECONNECT_REQUIRED, vm.state.value.refreshError)
    }

    @Test fun `unsupported companion schema asks for update`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf({
            throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "technical detail")
        }))
        val vm = viewModel(gateway)

        vm.refresh(); advanceUntilIdle()

        assertEquals(PeerListError.UPDATE_REQUIRED, vm.state.value.refreshError)
        assertTrue(vm.state.value.peers.isEmpty())
    }

    @Test fun `revoked token asks to reconnect protected access`() = runTest(dispatcher) {
        val gateway = QueueGateway(mutableListOf({
            throw XkeenException(XkeenErrorCode.UNAUTHORIZED, "secret response")
        }))
        val vm = viewModel(gateway)

        vm.refresh(); advanceUntilIdle()

        assertEquals(PeerListError.RECONNECT_REQUIRED, vm.state.value.refreshError)
    }

    @Test fun `duplicate refresh is coalesced`() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<Peer>>()
        val gateway = QueueGateway(mutableListOf({ gate.await() }))
        val vm = viewModel(gateway)

        vm.refresh(); vm.refresh(); runCurrent()

        assertEquals(1, gateway.calls)
        gate.complete(emptyList()); advanceUntilIdle()
    }

    @Test fun `manual refresh waits for an active background request instead of being dropped`() = runTest(dispatcher) {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<List<Peer>>()
        val gateway = QueueGateway(mutableListOf(
            {
                firstStarted.complete(Unit)
                firstResult.await()
            },
            { listOf(peerB) },
        ))
        val vm = viewModel(gateway)
        val foreground = vm.startForegroundRefresh()
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        val manual = vm.refresh()
        runCurrent()
        firstResult.complete(listOf(peerA))
        runCurrent()
        manual.join()
        runCurrent()
        foreground.cancel()

        assertEquals(2, gateway.calls)
        assertEquals(listOf(peerB), vm.state.value.peers)
    }

    @Test fun `toggle marks only target peer busy and applies server confirmed value`() = runTest(dispatcher) {
        val confirm = CompletableDeferred<Unit>()
        val peers = MutableStateFlow(listOf(peerA, peerB))
        val gateway = object : PeerListGateway {
            override suspend fun list(settings: ServerSettings) = peers.value
            override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) {
                confirm.await()
                peers.value = peers.value.map { if (it.publicKey == publicKey) it.copy(enabled = enabled) else it }
            }
        }
        val vm = viewModel(gateway)
        vm.refresh(); advanceUntilIdle()

        vm.setEnabled(peerA.publicKey, false); runCurrent()
        assertEquals(setOf(peerA.publicKey), vm.state.value.busyKeys)
        confirm.complete(Unit); advanceUntilIdle()

        assertFalse(vm.state.value.peers.first { it.publicKey == peerA.publicKey }.enabled)
        assertTrue(vm.state.value.busyKeys.isEmpty())
    }

    private fun viewModel(gateway: PeerListGateway) = PeerListViewModel(
        gateway,
        PeerListSettingsGateway { ServerSettings() },
        PeerListClock { 1234L },
    )

    private fun peer(key: String, enabled: Boolean) = Peer(
        key, key, "10.8.0.2", false, HandshakeStatus(HandshakeKind.NEVER), 0, 0, enabled,
    )
}

private class QueueGateway(
    private val results: MutableList<suspend () -> List<Peer>>,
    private val cached: List<Peer> = emptyList(),
) : PeerListGateway {
    var calls = 0
    override suspend fun cached(settings: ServerSettings) = cached
    override suspend fun list(settings: ServerSettings): List<Peer> { calls++; return results.removeAt(0).invoke() }
}
