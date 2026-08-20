package ru.anisimov.keenwg.ui.update

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionErrorCode
import ru.anisimov.keenwg.data.companion.CompanionException
import ru.anisimov.keenwg.data.companion.CompanionHealth
import ru.anisimov.keenwg.data.companion.PairedDevice
import ru.anisimov.keenwg.data.companion.PairingCredential
import ru.anisimov.keenwg.data.installer.CompanionAssetManifest
import ru.anisimov.keenwg.data.installer.VerifiedCompanionAsset
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.update.CompanionUpdateAccepted
import ru.anisimov.keenwg.data.update.CompanionUpdateError
import ru.anisimov.keenwg.data.update.CompanionUpdateException
import ru.anisimov.keenwg.data.update.CompanionUpdateGateway
import ru.anisimov.keenwg.data.update.CompanionUpdateStatus
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun `newer official component is offered and check bytes are cleared`() = runTest(dispatcher) {
        val asset = asset()
        val gateway = FakeUpdateGateway(mutableListOf(status("2.1.2")))
        val vm = CompanionUpdateViewModel(flowOf(active()), gateway, { asset }, healthyCompanion("2.1.2"), delayMillis = {})
        advanceUntilIdle()

        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertEquals("2.1.2", vm.state.value.currentVersion)
        assertEquals("2.2.0", vm.state.value.targetVersion)
        assertTrue(vm.state.value.checks.single { it.id == CompanionCheckId.UPDATE }.state == CompanionCheckState.ATTENTION)
        assertTrue(asset.bytes.all { it == 0.toByte() })
    }

    @Test fun `accepted update reconnects and requires expected version before success`() = runTest(dispatcher) {
        val gateway = FakeUpdateGateway(mutableListOf(
            status("2.1.2"),
            status("2.1.2", phase = "installing", result = "running", target = "2.2.0"),
            status("2.2.0", phase = "complete", result = "installed", target = "2.2.0"),
        ))
        val vm = CompanionUpdateViewModel(flowOf(active()), gateway, ::asset, healthyCompanion("2.1.2"), delayMillis = {})
        advanceUntilIdle()

        vm.install()
        advanceUntilIdle()

        assertEquals(UpdatePhase.SUCCESS, vm.state.value.phase)
        assertEquals(1, gateway.installCalls)
    }

    @Test fun `old component explains one final credential upgrade`() = runTest(dispatcher) {
        val gateway = FakeUpdateGateway(mutableListOf(), statusFailure = CompanionUpdateException(CompanionUpdateError.UNSUPPORTED))
        val vm = CompanionUpdateViewModel(flowOf(active()), gateway, ::asset, healthyCompanion("2.1.2"), delayMillis = {})
        advanceUntilIdle()

        assertEquals(UpdatePhase.NEEDS_PASSWORD, vm.state.value.phase)
        assertEquals(
            CompanionCheckState.ATTENTION,
            vm.state.value.checks.single { it.id == CompanionCheckId.UPDATE }.state,
        )
    }

    @Test fun `failed updater status reports restored previous version`() = runTest(dispatcher) {
        val gateway = FakeUpdateGateway(mutableListOf(
            status("2.1.2"),
            status("2.1.2", phase = "complete", result = "failed", target = "2.2.0", error = "install_failed"),
        ))
        val vm = CompanionUpdateViewModel(flowOf(active()), gateway, ::asset, healthyCompanion("2.1.2"), delayMillis = {})
        advanceUntilIdle()
        vm.install()
        advanceUntilIdle()

        assertEquals(UpdatePhase.ROLLED_BACK, vm.state.value.phase)
    }

    @Test fun `missing endpoint invites setup instead of showing generic error`() = runTest(dispatcher) {
        val vm = CompanionUpdateViewModel(
            flowOf(active(configured = false)),
            FakeUpdateGateway(mutableListOf(status("2.2.0"))),
            ::asset,
            healthyCompanion("2.2.0"),
            delayMillis = {},
        )
        advanceUntilIdle()

        assertEquals(UpdatePhase.NOT_CONFIGURED, vm.state.value.phase)
        assertEquals(CompanionCheckState.ERROR, vm.state.value.checks.single { it.id == CompanionCheckId.CONFIGURATION }.state)
    }

    @Test fun `revoked phone access is distinguished from unavailable service`() = runTest(dispatcher) {
        val companion = FakeCompanionClient(
            health = CompanionHealth(version = "2.2.0"),
            capabilitiesFailure = CompanionException(CompanionErrorCode.UNAUTHORIZED),
        )
        val vm = CompanionUpdateViewModel(
            flowOf(active()),
            FakeUpdateGateway(mutableListOf(status("2.2.0"))),
            ::asset,
            companion,
            delayMillis = {},
        )
        advanceUntilIdle()

        assertEquals(UpdatePhase.PAIRING_REQUIRED, vm.state.value.phase)
        assertEquals("2.2.0", vm.state.value.currentVersion)
        assertEquals(CompanionCheckState.ERROR, vm.state.value.checks.single { it.id == CompanionCheckId.PHONE_ACCESS }.state)
    }

    @Test fun `unreachable service keeps setup and later checks distinct`() = runTest(dispatcher) {
        val companion = FakeCompanionClient(
            health = CompanionHealth(version = "2.2.0"),
            healthFailure = CompanionException(CompanionErrorCode.UNAVAILABLE),
        )
        val vm = CompanionUpdateViewModel(
            flowOf(active()),
            FakeUpdateGateway(mutableListOf(status("2.2.0"))),
            ::asset,
            companion,
            delayMillis = {},
        )
        advanceUntilIdle()

        assertEquals(UpdatePhase.UNREACHABLE, vm.state.value.phase)
        assertEquals(CompanionCheckState.OK, vm.state.value.checks.single { it.id == CompanionCheckId.CONFIGURATION }.state)
        assertEquals(CompanionCheckState.ERROR, vm.state.value.checks.single { it.id == CompanionCheckId.SERVICE }.state)
        assertEquals(CompanionCheckState.NOT_CHECKED, vm.state.value.checks.single { it.id == CompanionCheckId.API }.state)
    }

    @Test fun `healthy component reports updater check failure without pretending service is down`() = runTest(dispatcher) {
        val vm = CompanionUpdateViewModel(
            flowOf(active()),
            FakeUpdateGateway(mutableListOf(), statusFailure = CompanionUpdateException(CompanionUpdateError.UNAVAILABLE)),
            ::asset,
            healthyCompanion("2.2.0"),
            delayMillis = {},
        )
        advanceUntilIdle()

        assertEquals(UpdatePhase.CHECK_FAILED, vm.state.value.phase)
        assertEquals(CompanionCheckState.OK, vm.state.value.checks.single { it.id == CompanionCheckId.SERVICE }.state)
        assertEquals(CompanionCheckState.ATTENTION, vm.state.value.checks.single { it.id == CompanionCheckId.UPDATE }.state)
    }

    @Test fun `unsupported api is reported separately from connectivity`() = runTest(dispatcher) {
        val companion = FakeCompanionClient(
            health = CompanionHealth(version = "2.2.0"),
            capabilitiesFailure = CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA),
        )
        val vm = CompanionUpdateViewModel(
            flowOf(active()),
            FakeUpdateGateway(mutableListOf(status("2.2.0"))),
            ::asset,
            companion,
            delayMillis = {},
        )
        advanceUntilIdle()

        assertEquals(UpdatePhase.INCOMPATIBLE, vm.state.value.phase)
        assertEquals(CompanionCheckState.OK, vm.state.value.checks.single { it.id == CompanionCheckId.SERVICE }.state)
        assertEquals(CompanionCheckState.ERROR, vm.state.value.checks.single { it.id == CompanionCheckId.API }.state)
    }

    private class FakeUpdateGateway(
        private val statuses: MutableList<CompanionUpdateStatus>,
        private val statusFailure: Exception? = null,
    ) : CompanionUpdateGateway {
        var installCalls = 0
        override suspend fun status(endpoint: CompanionEndpoint): CompanionUpdateStatus {
            statusFailure?.let { throw it }
            return if (statuses.size > 1) statuses.removeAt(0) else statuses.first()
        }
        override suspend fun install(endpoint: CompanionEndpoint, asset: VerifiedCompanionAsset): CompanionUpdateAccepted {
            installCalls++
            asset.bytes.fill(0)
            return CompanionUpdateAccepted("2.2.0")
        }
    }

    private fun status(version: String, phase: String = "idle", result: String = "idle", target: String? = null, error: String? = null) =
        CompanionUpdateStatus(version, true, phase, result, target, error)

    private fun asset() = VerifiedCompanionAsset(
        CompanionAssetManifest(1, "2.2.0", "arm64", "keenwg-companion-arm64.tgz", "a".repeat(64), "b".repeat(64), 7, "release-test", "A".repeat(86)),
        ByteArray(7) { 1 },
    )

    private fun healthyCompanion(version: String) = FakeCompanionClient(CompanionHealth(version = version))

    private class FakeCompanionClient(
        private val health: CompanionHealth,
        private val healthFailure: Exception? = null,
        private val capabilitiesFailure: Exception? = null,
    ) : CompanionClient {
        override suspend fun health(profile: RouterProfile): CompanionHealth {
            healthFailure?.let { throw it }
            return health
        }
        override suspend fun capabilities(profile: RouterProfile, deviceToken: String): CapabilityDocument {
            capabilitiesFailure?.let { throw it }
            return CapabilityDocument(
                capabilities = listOf(Capability(id = "system.devices", access = CapabilityAccess.WRITE, available = true, transport = "companion")),
            )
        }
        override suspend fun exchange(profile: RouterProfile, offerId: String, secret: String, label: String): PairingCredential = error("unused")
        override suspend fun devices(profile: RouterProfile, deviceToken: String): List<PairedDevice> = emptyList()
        override suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String) = Unit
    }

    private fun active(configured: Boolean = true) = ActiveRouterProfile(
        RouterProfile(id = "router", displayName = "Home", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            companionUrl = if (configured) "https://192.168.1.1:18779" else "",
            certificatePin = if (configured) "sha256/" + "a".repeat(43) else ""),
        RouterSecrets(companionToken = if (configured) "owner-token" else ""),
    )
}
