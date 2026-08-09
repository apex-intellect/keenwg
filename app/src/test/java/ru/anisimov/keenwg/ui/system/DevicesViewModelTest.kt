package ru.anisimov.keenwg.ui.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionErrorCode
import ru.anisimov.keenwg.data.companion.CompanionException
import ru.anisimov.keenwg.data.companion.CompanionHealth
import ru.anisimov.keenwg.data.companion.DeviceScope
import ru.anisimov.keenwg.data.companion.PairedDevice
import ru.anisimov.keenwg.data.companion.PairingCredential
import ru.anisimov.keenwg.data.companion.PairingOffer
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `loads system state and marks the current device`() = runTest(dispatcher) {
        val client = FakeCompanion()
        val vm = viewModel(client)
        vm.refresh(); advanceUntilIdle()

        assertEquals("0.7.0", vm.state.value.companionVersion)
        assertEquals(CapabilityAccess.WRITE, vm.state.value.access)
        assertTrue(vm.state.value.devices.first { it.id == "phone-1" }.current)
    }

    @Test fun `offer is viewer-only compact QR and dismiss revokes it`() = runTest(dispatcher) {
        val client = FakeCompanion()
        val vm = viewModel(client)
        vm.refresh(); advanceUntilIdle()
        vm.createViewerOffer(); advanceUntilIdle()

        val qr = vm.state.value.offer!!.qrPayload
        assertTrue(qr.contains("\"scope\":\"viewer\""))
        assertFalse(qr.contains("owner-token"))
        vm.dismissOffer(); advanceUntilIdle()

        assertEquals(listOf("offer-1"), client.revokedOffers)
        assertNull(vm.state.value.offer)
    }

    @Test fun `expired QR is revoked and removed`() = runTest(dispatcher) {
        val client = FakeCompanion()
        var clock = Instant.parse("2026-08-09T12:00:00Z")
        val vm = viewModel(client, now = { clock })
        vm.refresh(); advanceUntilIdle()
        vm.createViewerOffer(); advanceUntilIdle()
        clock = Instant.parse("2026-08-09T12:06:00Z")
        vm.expireOfferIfNeeded(); advanceUntilIdle()

        assertEquals(listOf("offer-1"), client.revokedOffers)
        assertNull(vm.state.value.offer)
    }

    @Test fun `current phone needs two confirmations before revoke`() = runTest(dispatcher) {
        val client = FakeCompanion()
        val vm = viewModel(client)
        vm.refresh(); advanceUntilIdle()
        vm.requestRevoke("phone-1")
        vm.confirmRevoke(); advanceUntilIdle()
        assertTrue(client.revokedDevices.isEmpty())
        assertTrue(vm.state.value.revokeConfirmation?.finalWarning == true)

        vm.confirmRevoke(); advanceUntilIdle()
        assertEquals(listOf("phone-1"), client.revokedDevices)
    }

    @Test fun `last owner conflict is rendered without removing device`() = runTest(dispatcher) {
        val client = FakeCompanion(lastOwnerConflict = true)
        val vm = viewModel(client)
        vm.refresh(); advanceUntilIdle()
        vm.requestRevoke("phone-2")
        vm.confirmRevoke(); advanceUntilIdle()

        assertTrue(vm.state.value.error.orEmpty().contains("последнего владельца"))
        assertTrue(vm.state.value.devices.any { it.id == "phone-2" })
    }

    private fun viewModel(client: CompanionClient, now: () -> Instant = { Instant.parse("2026-08-09T12:00:00Z") }) =
        DevicesViewModel(
            activeProfileFlow = MutableStateFlow(ActiveRouterProfile(profile(), RouterSecrets(companionToken = "owner-token", companionDeviceId = "phone-1"))),
            companion = client,
            now = now,
            dispatcher = dispatcher,
        )

    private class FakeCompanion(private val lastOwnerConflict: Boolean = false) : CompanionClient {
        val revokedDevices = mutableListOf<String>()
        val revokedOffers = mutableListOf<String>()
        override suspend fun health(profile: RouterProfile) = CompanionHealth(version = "0.7.0")
        override suspend fun capabilities(profile: RouterProfile, deviceToken: String) = CapabilityDocument(
            capabilities = listOf(Capability(id = "system.devices", access = CapabilityAccess.WRITE, available = true, transport = "companion")),
        )
        override suspend fun devices(profile: RouterProfile, deviceToken: String) = listOf(
            PairedDevice("phone-1", "Pixel", DeviceScope.OWNER, "2026-08-09T10:00:00Z"),
            PairedDevice("phone-2", "Tablet", DeviceScope.OWNER, "2026-08-09T10:00:00Z"),
        )
        override suspend fun createOffer(profile: RouterProfile, deviceToken: String, scope: DeviceScope) = PairingOffer(
            offerId = "offer-1", secret = "one-use", scope = scope, expiresAt = "2026-08-09T12:05:00Z",
        )
        override suspend fun revokeOffer(profile: RouterProfile, deviceToken: String, offerId: String) { revokedOffers += offerId }
        override suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String) {
            if (lastOwnerConflict) throw CompanionException(CompanionErrorCode.CONFLICT)
            revokedDevices += deviceId
        }
        override suspend fun exchange(profile: RouterProfile, offerId: String, secret: String, label: String): PairingCredential = error("unused")
    }

    private fun profile() = RouterProfile(
        id = "home", displayName = "Home", host = "192.168.1.1", rciPort = 80,
        interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
        dns = "192.168.1.1", mtu = 1380, keepalive = 25,
        companionUrl = "https://192.168.1.1:18779",
        certificatePin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        legacyXkeenUrl = "http://192.168.1.1:18778",
    )
}
