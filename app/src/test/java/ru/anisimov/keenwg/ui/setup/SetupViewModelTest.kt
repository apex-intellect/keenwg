package ru.anisimov.keenwg.ui.setup

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.anisimov.keenwg.data.installer.HostKeyObservation
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallPlan
import ru.anisimov.keenwg.data.installer.InstallPreparation
import ru.anisimov.keenwg.data.installer.InstallProbe
import ru.anisimov.keenwg.data.installer.InstallReport
import ru.anisimov.keenwg.data.installer.InstallerException
import ru.anisimov.keenwg.data.installer.InstallerWorkflow
import ru.anisimov.keenwg.data.installer.SshEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `host key must be observed and explicitly approved before authentication`() = runTest(dispatcher) {
        val workflow = FakeWorkflow()
        val vm = viewModel(workflow)
        val endpoint = SshEndpoint("192.168.1.1", 222, "root")

        vm.observeHostKey(endpoint)
        advanceUntilIdle()

        assertTrue(vm.state.value is SetupState.HostKeyApproval)
        assertEquals(listOf("observe"), workflow.events)
        val password = "secret".toByteArray()
        vm.approveHostKey(password)
        advanceUntilIdle()

        assertTrue(vm.state.value is SetupState.Review)
        assertEquals(listOf("observe", "prepare"), workflow.events)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `review confirmation drives phases then completion and zeros password`() = runTest(dispatcher) {
        val workflow = FakeWorkflow()
        val vm = viewModel(workflow)
        vm.observeHostKey(SshEndpoint("192.168.1.1", 222, "root"))
        advanceUntilIdle()
        vm.approveHostKey("probe".toByteArray())
        advanceUntilIdle()
        val password = "install".toByteArray()

        vm.confirmInstall(password, "Pixel")
        advanceUntilIdle()

        val completed = vm.state.value as SetupState.Completed
        assertEquals("home", completed.profileId)
        assertEquals(listOf("observe", "prepare", "install"), workflow.events)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `failure exposes only sanitized report and rollback truth`() = runTest(dispatcher) {
        val workflow = FakeWorkflow(failInstall = true)
        val vm = viewModel(workflow)
        vm.observeHostKey(SshEndpoint("192.168.1.1", 222, "root")); advanceUntilIdle()
        vm.approveHostKey("probe".toByteArray()); advanceUntilIdle()

        vm.confirmInstall("install-secret".toByteArray(), "Pixel")
        advanceUntilIdle()

        val failed = vm.state.value as SetupState.Failed
        assertEquals(InstallPhase.INSTALL, failed.phase)
        assertTrue(failed.rollbackVerified)
        assertFalse(failed.safeMessage.contains("install-secret"))
    }

    private fun viewModel(workflow: InstallerWorkflow) = SetupViewModel(
        activeProfileFlow = MutableStateFlow(ActiveRouterProfile(profile(), RouterSecrets())),
        workflow = workflow,
        dispatcher = dispatcher,
    )

    private class FakeWorkflow(private val failInstall: Boolean = false) : InstallerWorkflow {
        val events = mutableListOf<String>()
        private val key = HostKeyObservation("ssh-ed25519", "SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg")
        private val preparation = InstallPreparation(
            profileId = "home",
            endpoint = SshEndpoint("192.168.1.1", 222, "root"),
            hostKey = key,
            probe = InstallProbe("aarch64", "4.3.1", 100_000_000, true, true, true, "2.4.1", true, true, null),
            plan = InstallPlan("0.7.0", "https://192.168.1.1:18779", 20_000_000, listOf("effect")),
        )
        override suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation { events += "observe"; return key }
        override suspend fun prepare(profileId: String, endpoint: SshEndpoint, hostKey: HostKeyObservation, password: ByteArray): InstallPreparation {
            events += "prepare"; password.fill(0); return preparation
        }
        override suspend fun install(
            preparation: InstallPreparation,
            password: ByteArray,
            deviceLabel: String,
            onPhase: suspend (InstallPhase) -> Unit,
        ): InstallReport {
            events += "install"
            password.fill(0)
            onPhase(InstallPhase.INSTALL)
            if (failInstall) throw InstallerException(InstallPhase.INSTALL, "Установка не завершена", true)
            onPhase(InstallPhase.CLEANUP)
            return InstallReport("0.7.0", preparation.plan.secureBaseUrl, "phone-1", true)
        }
    }

    private fun profile() = RouterProfile(
        id = "home", displayName = "Home", host = "192.168.1.1", rciPort = 80,
        interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
        dns = "192.168.1.1", mtu = 1380, keepalive = 25,
    )
}
