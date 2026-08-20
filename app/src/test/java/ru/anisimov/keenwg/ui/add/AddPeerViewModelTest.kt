package ru.anisimov.keenwg.ui.add

import ru.anisimov.keenwg.R
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
        assertEquals(R.string.add_error_create_failed, vm.state.value.errorResource)
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

    @Test fun `missing public endpoint stops before the creation review`() = runTest(dispatcher) {
        val settings = FakeSettingsGateway(ServerSettings(subnetBase = "10.8.0.", endpoint = ""))
        val vm = viewModel(
            gateway = FakeGateway(),
            settingsGateway = settings,
        )
        vm.onNameChange("phone")
        vm.onIpChange("10.8.0.9")
        vm.prepare()
        advanceUntilIdle()

        vm.review()
        advanceUntilIdle()

        assertEquals(AddPeerStage.FORM, vm.state.value.stage)
        assertTrue(vm.state.value.endpointDialogVisible)
        assertEquals(0, settings.saveCalls)
    }

    @Test fun `valid public endpoint is saved once before creation reaches the gateway`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val settings = FakeSettingsGateway(ServerSettings(subnetBase = "10.8.0.", endpoint = ""))
        val vm = viewModel(gateway, settings)
        vm.onNameChange("phone")
        vm.onIpChange("10.8.0.9")
        vm.prepare()
        advanceUntilIdle()
        vm.review()

        vm.onEndpointChange("home.example.test:51820")
        vm.saveEndpointAndContinue()
        advanceUntilIdle()
        vm.create()
        advanceUntilIdle()

        assertEquals(1, settings.saveCalls)
        assertEquals("home.example.test:51820", settings.value.endpoint)
        assertEquals("home.example.test:51820", gateway.addedEndpoint)
        assertEquals(AddPeerStage.SUCCESS, vm.state.value.stage)
    }

    @Test fun `invalid public endpoint stays in the focused editor without saving`() = runTest(dispatcher) {
        val settings = FakeSettingsGateway(ServerSettings(subnetBase = "10.8.0.", endpoint = ""))
        val vm = viewModel(FakeGateway(), settings)
        vm.onNameChange("phone")
        vm.prepare()
        advanceUntilIdle()
        vm.review()
        vm.onEndpointChange("https://home.example.test/path")

        vm.saveEndpointAndContinue()
        advanceUntilIdle()

        assertTrue(vm.state.value.endpointDialogVisible)
        assertEquals(R.string.add_error_endpoint_invalid, vm.state.value.endpointErrorResource)
        assertEquals(0, settings.saveCalls)
    }

    private fun viewModel(
        gateway: AddPeerGateway,
        settingsGateway: AddPeerSettingsGateway = FakeSettingsGateway(
            ServerSettings(subnetBase = "10.8.0.", endpoint = "vpn.example.test:51820"),
        ),
    ) = AddPeerViewModel(
        gateway = gateway,
        settingsGateway = settingsGateway,
    )

    private class FakeGateway(
        private val peers: List<Peer> = emptyList(),
        private val addError: Throwable? = null,
    ) : AddPeerGateway {
        var addedName: String? = null
        var addedEndpoint: String? = null
        var policy: AccessPolicy? = null

        override suspend fun list(settings: ServerSettings) = peers

        override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy): AddResult {
            addError?.let { throw it }
            addedName = name
            addedEndpoint = settings.endpoint
            this.policy = policy
            return AddResult(peer(ip ?: "10.8.0.2", name), "[Interface]\nPrivateKey = verified")
        }
    }

    private class FakeSettingsGateway(var value: ServerSettings) : AddPeerSettingsGateway {
        var saveCalls = 0

        override suspend fun settings() = value

        override suspend fun saveEndpoint(endpoint: String) {
            saveCalls++
            value = value.copy(endpoint = endpoint)
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
