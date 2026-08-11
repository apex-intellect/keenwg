package ru.anisimov.keenwg.ui.support

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.support.SupportBundle
import ru.anisimov.keenwg.data.support.SupportExport
import ru.anisimov.keenwg.data.support.SupportGateway
import ru.anisimov.keenwg.data.support.SupportReport
import ru.anisimov.keenwg.data.support.SupportSummary
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class SupportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `report is generated only explicitly and remains available for reviewed export`() = runTest(dispatcher) {
        val gateway = FakeSupportGateway()
        val vm = SupportViewModel(flowOf(active()), gateway)
        advanceUntilIdle()

        assertEquals(0, gateway.calls)
        assertNull(vm.state.value.export)

        vm.generate()
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
        assertFalse(vm.state.value.busy)
        assertEquals("KeenWG support report", vm.state.value.export!!.text)
        assertTrue(vm.state.value.export!!.json.contains("schema_version"))
        assertNull(vm.state.value.error)
    }

    @Test fun `missing pairing becomes an actionable requirement without invoking gateway`() = runTest(dispatcher) {
        val incompleteProfiles = listOf(
            null,
            active(companionUrl = ""),
            active(certificatePin = ""),
            active(companionToken = ""),
        )
        incompleteProfiles.forEach { active ->
            val gateway = FakeSupportGateway()
            val vm = SupportViewModel(flowOf(active), gateway)

            vm.generate()
            advanceUntilIdle()

            assertEquals(0, gateway.calls)
            assertEquals(SupportRequirement.COMPANION_PAIRING, vm.state.value.requirement)
            assertNull(vm.state.value.error)
            assertNull(vm.state.value.export)
        }
    }

    private class FakeSupportGateway : SupportGateway {
        var calls = 0
        override suspend fun generate(profile: RouterProfile, token: String): SupportExport {
            calls++
            val at = "2026-08-09T05:30:00Z"
            val report = SupportReport(1, at, SupportSummary("0.9.0", 9u, true, 3, "domain", "tcp"), emptyList(), emptyList())
            val bundle = SupportBundle(1, at, report, "KeenWG support report")
            return SupportExport(bundle, "{\"schema_version\":1}", bundle.reviewText)
        }
    }

    private fun active(
        companionUrl: String = "https://192.0.2.1:18779",
        certificatePin: String = "sha256/test",
        companionToken: String = "viewer-token",
    ) = ActiveRouterProfile(
        RouterProfile(
            id = "router", displayName = "Home", host = "192.0.2.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.0.2.1", mtu = 1380, keepalive = 25,
            companionUrl = companionUrl, certificatePin = certificatePin,
        ),
        RouterSecrets(companionToken = companionToken),
    )
}
