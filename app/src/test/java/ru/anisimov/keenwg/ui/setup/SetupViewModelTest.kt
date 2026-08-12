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
import ru.anisimov.keenwg.data.installer.InstallMode
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

    @Test fun `one submitted password completes setup and is erased from caller`() = runTest(dispatcher) {
        val workflow = FakeWorkflow()
        val vm = viewModel(workflow)
        val password = "secret".toByteArray()

        vm.connect(ENDPOINT, password, "Pixel")
        advanceUntilIdle()

        assertTrue(vm.state.value is SetupState.Completed)
        assertEquals(listOf("observe", "prepare", "install"), workflow.events)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test fun `changed pinned key blocks before password authentication`() = runTest(dispatcher) {
        val changed = HostKeyObservation(
            "ssh-ed25519",
            "SHA256:1OMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg",
        )
        val workflow = FakeWorkflow(observedKey = changed)
        val vm = viewModel(workflow, profile = profile(pinnedKey = HOST_KEY))

        vm.connect(ENDPOINT, "secret".toByteArray(), "Pixel")
        advanceUntilIdle()

        val state = vm.state.value as SetupState.HostKeyChanged
        assertEquals(HOST_KEY.sha256, state.expected.sha256)
        assertEquals(changed.sha256, state.observed.sha256)
        assertEquals(listOf("observe"), workflow.events)
    }

    @Test fun `explicitly accepting changed key continues same setup session`() = runTest(dispatcher) {
        val changed = HostKeyObservation(
            "ssh-ed25519",
            "SHA256:1OMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg",
        )
        val workflow = FakeWorkflow(observedKey = changed)
        val vm = viewModel(workflow, profile(pinnedKey = HOST_KEY))
        vm.connect(ENDPOINT, "secret".toByteArray(), "Pixel")
        advanceUntilIdle()

        vm.acceptChangedHostKey()
        advanceUntilIdle()

        assertTrue(vm.state.value is SetupState.Completed)
        assertEquals(listOf("observe", "prepare", "install"), workflow.events)
    }

    @Test fun `missing prerequisites become dedicated state before install`() = runTest(dispatcher) {
        val workflow = FakeWorkflow(
            probe = probe(entwarePresent = false, xkeenVersion = null),
        )
        val vm = viewModel(workflow)

        vm.connect(ENDPOINT, "secret".toByteArray(), "Pixel")
        advanceUntilIdle()

        val state = vm.state.value as SetupState.PrerequisiteMissing
        assertEquals(setOf(SetupPrerequisite.ENTWARE), state.missing)
        assertEquals(listOf("observe", "prepare"), workflow.events)
        assertFalse(workflow.events.contains("install"))
    }

    @Test fun `optional xkeen absence does not block protected access setup`() = runTest(dispatcher) {
        val workflow = FakeWorkflow(probe = probe(xkeenVersion = null))
        val vm = viewModel(workflow)

        vm.connect(ENDPOINT, "secret".toByteArray(), "Pixel")
        advanceUntilIdle()

        assertTrue(vm.state.value is SetupState.Completed)
        assertEquals(listOf("observe", "prepare", "install"), workflow.events)
    }

    @Test fun `failure exposes sanitized recovery state and erases submitted password`() = runTest(dispatcher) {
        val workflow = FakeWorkflow(failInstall = true)
        val vm = viewModel(workflow)
        val password = "install-secret".toByteArray()

        vm.connect(ENDPOINT, password, "Pixel")
        advanceUntilIdle()

        val failed = vm.state.value as SetupState.Failed
        assertEquals(InstallPhase.INSTALL, failed.phase)
        assertTrue(failed.rollbackVerified)
        assertFalse(failed.safeMessage.contains("install-secret"))
        assertTrue(password.all { it == 0.toByte() })
    }

    private fun viewModel(
        workflow: InstallerWorkflow,
        profile: RouterProfile = profile(),
    ) = SetupViewModel(
        activeProfileFlow = MutableStateFlow(ActiveRouterProfile(profile, RouterSecrets())),
        workflow = workflow,
        dispatcher = dispatcher,
    )

    private class FakeWorkflow(
        private val observedKey: HostKeyObservation = HOST_KEY,
        probe: InstallProbe = probe(),
        private val failInstall: Boolean = false,
    ) : InstallerWorkflow {
        val events = mutableListOf<String>()
        private val preparation = InstallPreparation(
            profileId = "home",
            endpoint = ENDPOINT,
            hostKey = observedKey,
            probe = probe,
            plan = InstallPlan(
                "2.0.0",
                InstallMode.CLEAN_INSTALL,
                "https://192.168.1.1:18779",
                20_000_000,
                listOf("effect"),
            ),
        )

        override suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation {
            events += "observe"
            return observedKey
        }

        override suspend fun prepare(
            profileId: String,
            endpoint: SshEndpoint,
            hostKey: HostKeyObservation,
            password: ByteArray,
        ): InstallPreparation {
            events += "prepare"
            password.fill(0)
            return preparation
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
            return InstallReport("2.0.0", requireNotNull(preparation.plan.secureBaseUrl), "phone-1", true)
        }
    }

    companion object {
        private val ENDPOINT = SshEndpoint("192.168.1.1", 222, "root")
        private val HOST_KEY = HostKeyObservation(
            "ssh-ed25519",
            "SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg",
        )

        private fun profile(pinnedKey: HostKeyObservation? = null) = RouterProfile(
            id = "home",
            displayName = "Home",
            host = "192.168.1.1",
            rciPort = 80,
            sshPort = 222,
            sshUsername = "root",
            sshHostKeyAlgorithm = pinnedKey?.algorithm.orEmpty(),
            sshHostKeySha256 = pinnedKey?.sha256.orEmpty(),
            interfaceId = "Wireguard0",
            serverPublicKey = "",
            endpoint = "",
            subnetBase = "10.8.0.",
            dns = "192.168.1.1",
            mtu = 1380,
            keepalive = 25,
        )

        private fun probe(
            entwarePresent: Boolean = true,
            xkeenVersion: String? = "2.4.1",
        ) = InstallProbe(
            architecture = "aarch64",
            firmware = "4.3.1",
            optFreeBytes = 100_000_000,
            entwarePresent = entwarePresent,
            companionConfigPresent = false,
            xkeenVersion = xkeenVersion,
            ascPresent = true,
            xrayPresent = true,
            companionVersion = null,
        )
    }
}
