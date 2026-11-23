package ru.anisimov.keenwg.ui.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeError
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeException
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeGateway
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeRepository
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeStatus
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class XkeenRuntimeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `stop needs explicit confirmation and cancelling sends no mutation`() = runTest(dispatcher) {
        val gateway = RuntimeFake()
        val vm = model(gateway)
        assertNull(vm.state.value.running)
        advanceUntilIdle()
        vm.requestEnabled(false)
        vm.requestEnabled(false)
        assertTrue(vm.state.value.confirmStop)
        assertEquals(true, vm.state.value.running)
        assertEquals(0, gateway.postCount)
        vm.cancelStop()
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmStop)
        assertEquals(0, gateway.postCount)
    }

    @Test fun `switch stays unchanged while request pending and double taps are blocked`() = runTest(dispatcher) {
        val gateway = RuntimeFake().apply { postGate = CompletableDeferred() }
        val vm = model(gateway)
        advanceUntilIdle()
        vm.requestEnabled(false)
        vm.confirmStop()
        vm.confirmStop()
        vm.requestEnabled(false)
        runCurrent()
        assertTrue(vm.state.value.changing)
        assertEquals(true, vm.state.value.running)
        assertFalse(vm.state.value.controlEnabled)
        assertEquals(1, gateway.postCount)
        gateway.postGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.running)
        assertFalse(vm.state.value.changing)
        assertNull(vm.state.value.error)
        assertEquals(listOf("GET", "POST:false", "GET"), gateway.calls)
    }

    @Test fun `start is verified and does not need stop confirmation`() = runTest(dispatcher) {
        val gateway = RuntimeFake().apply { running = false }
        val vm = model(gateway)
        advanceUntilIdle()
        vm.requestEnabled(true)
        assertFalse(vm.state.value.confirmStop)
        assertEquals(false, vm.state.value.running)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.running)
        assertEquals(listOf("GET", "POST:true", "GET"), gateway.calls)
    }

    @Test fun `old Companion 404 disables control and retry can recover`() = runTest(dispatcher) {
        val gateway = RuntimeFake().apply { getFailure = XkeenRuntimeException(XkeenRuntimeError.UNSUPPORTED) }
        val vm = model(gateway)
        advanceUntilIdle()
        assertEquals(XkeenRuntimeError.UNSUPPORTED, vm.state.value.error)
        assertNull(vm.state.value.running)
        vm.requestEnabled(true)
        assertEquals(0, gateway.postCount)
        gateway.getFailure = null
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.controlEnabled)
    }

    @Test fun `viewer sees state but cannot request any change`() = runTest(dispatcher) {
        val gateway = RuntimeFake().apply { access = CapabilityAccess.READ }
        val vm = model(gateway)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.running)
        assertFalse(vm.state.value.controlEnabled)
        vm.requestEnabled(false)
        vm.confirmStop()
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmStop)
        assertEquals(0, gateway.postCount)
    }

    @Test fun `read back failure clears status and refresh recovers`() = runTest(dispatcher) {
        val gateway = RuntimeFake()
        val vm = model(gateway)
        advanceUntilIdle()
        gateway.getFailure = XkeenRuntimeException(XkeenRuntimeError.UNAVAILABLE)
        vm.requestEnabled(false)
        vm.confirmStop()
        advanceUntilIdle()
        assertNull(vm.state.value.running)
        assertFalse(vm.state.value.controlEnabled)
        assertEquals(XkeenRuntimeError.UNAVAILABLE, vm.state.value.error)
        gateway.getFailure = null
        vm.refresh()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.running)
        assertTrue(vm.state.value.controlEnabled)
    }

    @Test fun `profile change clears confirmation and cannot stop newly selected router`() = runTest(dispatcher) {
        val profiles = MutableStateFlow<ActiveRouterProfile?>(profile("first"))
        val gateway = RuntimeFake()
        val vm = XkeenRuntimeViewModel(profiles, XkeenRuntimeRepository(gateway))
        runCurrent()
        vm.requestEnabled(false)
        assertTrue(vm.state.value.confirmStop)
        profiles.value = profile("second")
        runCurrent()
        vm.confirmStop()
        runCurrent()
        assertFalse(vm.state.value.confirmStop)
        assertEquals(0, gateway.postCount)
    }

    @Test fun `missing profile hides control without network requests`() = runTest(dispatcher) {
        val gateway = RuntimeFake()
        val vm = XkeenRuntimeViewModel(flowOf(null), XkeenRuntimeRepository(gateway))
        advanceUntilIdle()
        assertTrue(vm.state.value.setupRequired)
        assertFalse(vm.state.value.controlEnabled)
        assertTrue(gateway.calls.isEmpty())
    }

    private fun model(gateway: RuntimeFake) = XkeenRuntimeViewModel(flowOf(profile()), XkeenRuntimeRepository(gateway))

    private class RuntimeFake : XkeenRuntimeGateway {
        var running = true
        var access = CapabilityAccess.WRITE
        var postCount = 0
        var postGate: CompletableDeferred<Unit>? = null
        var getFailure: Exception? = null
        val calls = mutableListOf<String>()
        override suspend fun status(endpoint: CompanionEndpoint): XkeenRuntimeStatus {
            calls += "GET"
            getFailure?.let { throw it }
            return XkeenRuntimeStatus(1, running, true)
        }
        override suspend fun access(endpoint: CompanionEndpoint) = access
        override suspend fun setEnabled(endpoint: CompanionEndpoint, enabled: Boolean): XkeenRuntimeStatus {
            postCount++
            calls += "POST:$enabled"
            postGate?.await()
            running = enabled
            return XkeenRuntimeStatus(1, enabled, true)
        }
    }

    private fun profile(id: String = "router") = ActiveRouterProfile(
        RouterProfile(id = id, displayName = "Router", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "key", endpoint = "vpn.example:1", subnetBase = "10.0.0",
            dns = "1.1.1.1", mtu = 1420, keepalive = 25,
            companionUrl = "https://$id.example:8443", certificatePin = "sha256/pin"),
        RouterSecrets(companionToken = "test-token"),
    )
}
