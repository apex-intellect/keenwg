package ru.anisimov.keenwg.ui.add

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.AddResult
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.domain.model.AccessPolicy

@OptIn(ExperimentalCoroutinesApi::class)
class AddPeerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `prepare fills only an untouched address`() = runTest(dispatcher) {
        val vm = viewModel(gateway = FakeGateway(peers = listOf(peer("10.8.0.2"))))

        vm.prepare()
        advanceUntilIdle()

        assertEquals("10.8.0.3", vm.state.value.ip)
        vm.onIpChange("10.8.0.17")
        vm.prepare()
        advanceUntilIdle()
        assertEquals("10.8.0.17", vm.state.value.ip)
    }

    @Test fun `failed creation preserves the entered name and address`() = runTest(dispatcher) {
        val vm = viewModel(gateway = FakeGateway(addError = IllegalStateException("роутер недоступен")))
        vm.onNameChange("Телефон Анны")
        vm.onIpChange("10.8.0.9")

        vm.review()
        vm.create()
        advanceUntilIdle()

        assertEquals("Телефон Анны", vm.state.value.name)
        assertEquals("10.8.0.9", vm.state.value.ip)
        assertEquals("роутер недоступен", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.result)
    }

    @Test fun `human name is normalized only for the router mutation`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.onNameChange("Телефон Анны 2")
        vm.onIpChange("10.8.0.9")

        vm.review()
        vm.create()
        advanceUntilIdle()

        assertEquals("telefon-anny-2", gateway.addedName)
        assertEquals("Телефон Анны 2", vm.state.value.name)
        assertTrue(vm.state.value.result != null)
        assertEquals(AddPeerStage.SUCCESS, vm.state.value.stage)
    }

    @Test fun `review is non mutating and confirm sends exact optional policy`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.onNameChange("travel")
        vm.onIpChange("10.8.0.9")
        vm.onAllowedNetworksChange("10.0.0.0/8, 192.168.0.0/16")
        vm.onDnsServersChange("1.1.1.1")
        vm.onExpiryDaysChange("30")
        vm.onHistoryEnabledChange(false)

        vm.review(nowEpochSeconds = 1_000)
        assertEquals(AddPeerStage.REVIEW, vm.state.value.stage)
        assertNull(gateway.addedName)
        vm.create()
        advanceUntilIdle()

        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), gateway.policy?.allowedNetworks)
        assertEquals(listOf("1.1.1.1"), gateway.policy?.dnsServers)
        assertEquals(1_000 + 30 * 86_400L, gateway.policy?.expiresAtEpochSeconds)
        assertFalse(gateway.policy?.historyEnabled ?: true)
    }

    private fun viewModel(gateway: AddPeerGateway) = AddPeerViewModel(
        gateway = gateway,
        settingsGateway = AddPeerSettingsGateway { ServerSettings(subnetBase = "10.8.0.") },
    )

    private class FakeGateway(
        private val peers: List<Peer> = emptyList(),
        private val addError: Throwable? = null,
    ) : AddPeerGateway {
        var addedName: String? = null
        var policy: AccessPolicy? = null

        override suspend fun list(settings: ServerSettings) = peers

        override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy): AddResult {
            addError?.let { throw it }
            addedName = name
            this.policy = policy
            return AddResult(peer(ip ?: "10.8.0.2", name), "[Interface]\nPrivateKey = verified")
        }
    }

    private companion object {
        fun peer(ip: String, name: String = "peer") = Peer(
            publicKey = "key-$ip",
            name = name,
            ip = ip,
            online = false,
            handshake = HandshakeStatus(HandshakeKind.NEVER),
            clientUploadBytes = 0,
            clientDownloadBytes = 0,
            enabled = true,
        )
    }
}
