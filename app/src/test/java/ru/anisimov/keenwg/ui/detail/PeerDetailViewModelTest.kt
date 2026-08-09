package ru.anisimov.keenwg.ui.detail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.data.AddResult
import ru.anisimov.keenwg.data.RouterMutationError
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.PeerStats
import ru.anisimov.keenwg.data.collector.PeerId
import ru.anisimov.keenwg.domain.model.AccessPolicy
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PeerDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `missing peer finishes as not found instead of loading`() = runTest(dispatcher) {
        val vm = PeerDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            peerGateway = object : PeerDetailPeerGateway {
                override suspend fun list(settings: ServerSettings) = emptyList<ru.anisimov.keenwg.domain.model.Peer>()
            },
            settingsGateway = PeerDetailSettingsGateway { ServerSettings() },
        )

        vm.load("+/key=")
        advanceUntilIdle()

        assertFalse(vm.state.value.initialLoading)
        assertTrue(vm.state.value.notFound)
    }

    @Test fun `successful load transitions from loading to content`() = runTest(dispatcher) {
        val peer = peer("old-key")
        val vm = viewModel(gateway(listOf(peer)))

        vm.load(peer.publicKey)
        advanceUntilIdle()

        assertFalse(vm.state.value.initialLoading)
        assertEquals(peer, vm.state.value.peer)
        assertFalse(vm.state.value.notFound)
    }

    @Test fun `failed initial load transitions to explicit error`() = runTest(dispatcher) {
        val vm = viewModel(object : PeerDetailPeerGateway {
            override suspend fun list(settings: ServerSettings): List<Peer> = error("router offline")
        })

        vm.load("old-key")
        advanceUntilIdle()

        assertFalse(vm.state.value.initialLoading)
        assertEquals("router offline", vm.state.value.loadError)
    }

    @Test fun `cached peer remains visible when detail refresh is offline`() = runTest(dispatcher) {
        val cached = peer("cached-key")
        val vm = viewModel(object : PeerDetailPeerGateway {
            override fun cached(publicKey: String) = cached.takeIf { it.publicKey == publicKey }
            override suspend fun list(settings: ServerSettings): List<Peer> = throw IOException("router offline")
        })

        vm.load(cached.publicKey)
        advanceUntilIdle()

        assertEquals(cached, vm.state.value.peer)
        assertFalse(vm.state.value.initialLoading)
        assertNull(vm.state.value.loadError)
        assertEquals("router offline", vm.state.value.refreshError)
    }

    @Test fun `delayed effect collector receives regenerated key exactly once`() = runTest(dispatcher) {
        val old = peer("old-key")
        val fresh = peer("new-key")
        val vm = viewModel(object : PeerDetailPeerGateway {
            override suspend fun list(settings: ServerSettings) = listOf(old)
            override suspend fun regenerate(settings: ServerSettings, publicKey: String) = AddResult(fresh, "secret-conf")
        })

        vm.regenerate(old.publicKey)
        advanceUntilIdle()
        val first = withTimeout(100) { vm.effects.first() }

        assertEquals(PeerDetailEffect.NavigateToPeer(fresh.publicKey), first)
        assertEquals(fresh, vm.state.value.peer)
    }

    @Test fun `pending navigation survives view model recreation until acknowledged`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val old = peer("old-key-2")
        val fresh = peer("new-key-2")
        val gateway = object : PeerDetailPeerGateway {
            override suspend fun list(settings: ServerSettings) = listOf(old)
            override suspend fun regenerate(settings: ServerSettings, publicKey: String) = AddResult(fresh, "secret-conf-2")
        }
        val firstVm = viewModel(gateway, handle)
        firstVm.regenerate(old.publicKey)
        advanceUntilIdle()

        val recreated = viewModel(gateway, handle)
        assertEquals(PeerDetailEffect.NavigateToPeer(fresh.publicKey), withTimeout(100) { recreated.effects.first() })

        recreated.acknowledgeNavigation(fresh.publicKey)
        assertNull(recreated.pendingNavigation.value)
        assertNull(withTimeoutOrNull(100) { recreated.effects.first() })
    }

    @Test fun `router committed local finalization error still navigates to new key`() = runTest(dispatcher) {
        val vm = viewModel(object : PeerDetailPeerGateway {
            override suspend fun list(settings: ServerSettings) = listOf(peer("old-key-3"))
            override suspend fun regenerate(settings: ServerSettings, publicKey: String): AddResult {
                throw RouterMutationError.LocalFinalization("new-key-3", "local finalize failed")
            }
        })

        vm.regenerate("old-key-3")
        advanceUntilIdle()

        assertEquals(PeerDetailEffect.NavigateToPeer("new-key-3"), withTimeout(100) { vm.effects.first() })
        assertEquals("local finalize failed", vm.state.value.refreshError)
    }

    @Test fun `collector failure keeps previously loaded stats`() = runTest(dispatcher) {
        val expected = stats()
        var calls = 0
        val vm = PeerDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            peerGateway = gateway(listOf(peer("stats-key"))),
            settingsGateway = PeerDetailSettingsGateway { ServerSettings() },
            statsGateway = PeerDetailStatsGateway { _, _, _, _ ->
                if (calls++ == 0) expected else throw IOException("collector offline")
            },
            clock = PeerDetailClock { 1_000_000L },
        )

        vm.refreshStats("stats-key"); advanceUntilIdle()
        vm.refreshStats("stats-key"); advanceUntilIdle()

        assertEquals(expected, vm.state.value.stats)
        assertEquals("Не удалось обновить историю наблюдений.", vm.state.value.collectorError)
    }

    @Test fun `history ranges downsample seven and thirty days`() {
        val now = 4_000_000L

        assertEquals("raw", historyRange(PeerHistoryRange.DAY, now).resolution)
        assertEquals("1h", historyRange(PeerHistoryRange.WEEK, now).resolution)
        assertEquals(now - 30L * 86_400L, historyRange(PeerHistoryRange.MONTH, now).from)
    }

    @Test fun `optional history policy suppresses collector calls`() = runTest(dispatcher) {
        var calls = 0
        val vm = PeerDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            peerGateway = object : PeerDetailPeerGateway {
                override suspend fun list(settings: ServerSettings) = listOf(peer("private-key"))
                override suspend fun accessPolicy(publicKey: String) = AccessPolicy(historyEnabled = false)
            },
            settingsGateway = PeerDetailSettingsGateway { ServerSettings() },
            statsGateway = PeerDetailStatsGateway { _, _, _, _ -> calls++; stats() },
        )
        vm.load("private-key"); advanceUntilIdle()
        vm.refreshStats("private-key"); advanceUntilIdle()

        assertTrue(vm.state.value.historySuppressed)
        assertEquals(0, calls)
    }

    @Test fun `collector ids always include current key after rotation`() {
        val interfaceId = "Wireguard0"
        val currentKey = "current-key"
        val inherited = listOf("old-id")

        assertEquals(
            listOf("old-id", PeerId.compute(interfaceId, currentKey)),
            collectorPeerIds(interfaceId, currentKey, inherited),
        )
    }

    @Test fun `selecting another range does not present old range stats`() = runTest(dispatcher) {
        val expected = stats()
        val next = CompletableDeferred<PeerStats>()
        var calls = 0
        val vm = PeerDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            peerGateway = gateway(listOf(peer("range-key"))),
            settingsGateway = PeerDetailSettingsGateway { ServerSettings() },
            statsGateway = PeerDetailStatsGateway { _, _, _, _ ->
                if (calls++ == 0) expected else next.await()
            },
            clock = PeerDetailClock { 4_000_000L },
        )
        vm.refreshStats("range-key"); advanceUntilIdle()

        vm.selectRange("range-key", PeerHistoryRange.WEEK); runCurrent()

        assertEquals(PeerHistoryRange.WEEK, vm.state.value.selectedRange)
        assertNull(vm.state.value.stats)
        assertNull(vm.state.value.collectorLastUpdated)
        next.complete(expected); advanceUntilIdle()
    }

    private fun viewModel(peerGateway: PeerDetailPeerGateway, handle: SavedStateHandle = SavedStateHandle()) = PeerDetailViewModel(
        peerGateway = peerGateway,
        settingsGateway = PeerDetailSettingsGateway { ServerSettings() },
        savedStateHandle = handle,
    )

    private fun gateway(peers: List<Peer>) = object : PeerDetailPeerGateway {
        override suspend fun list(settings: ServerSettings) = peers
    }

    private fun peer(publicKey: String) = Peer(
        publicKey = publicKey,
        name = "phone",
        ip = "10.8.0.7",
        online = false,
        handshake = HandshakeStatus(HandshakeKind.NEVER),
        clientUploadBytes = 0,
        clientDownloadBytes = 0,
        enabled = true,
    )

    private fun stats() = PeerStats(
        from = 1,
        to = 2,
        observedSeconds = 1,
        onlineSeconds = 1,
        lastOnlineAt = 1,
        clientUploadBytes = 10,
        clientDownloadBytes = 20,
        counterResets = 0,
        coverageRatio = 1.0,
        points = emptyList(),
    )
}
