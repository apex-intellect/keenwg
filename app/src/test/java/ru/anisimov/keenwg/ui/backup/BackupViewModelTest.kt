package ru.anisimov.keenwg.ui.backup

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
import ru.anisimov.keenwg.data.backup.BackupApplyResult
import ru.anisimov.keenwg.data.backup.BackupExport
import ru.anisimov.keenwg.data.backup.BackupGateway
import ru.anisimov.keenwg.data.backup.BackupPreview
import ru.anisimov.keenwg.data.backup.BackupPreviewEntry
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `backup and restore never run automatically and importing clears stale review`() = runTest(dispatcher) {
        val gateway = FakeBackupGateway()
        val vm = BackupViewModel(flowOf(active()), gateway)
        advanceUntilIdle()

        assertEquals(0, gateway.createCalls + gateway.previewCalls + gateway.applyCalls)

        vm.create("correct horse".toCharArray())
        advanceUntilIdle()
        assertEquals(1, gateway.createCalls)
        assertEquals(PLAN_ID, vm.state.value.preview?.planId)

        vm.loadArchive("other archive".toByteArray())
        assertNull(vm.state.value.preview)
        assertNull(vm.state.value.result)
        assertTrue(vm.state.value.archive!!.contentEquals("other archive".toByteArray()))
    }

    @Test fun `preview does not apply and apply requires exact reviewed plan`() = runTest(dispatcher) {
        val gateway = FakeBackupGateway()
        val vm = BackupViewModel(flowOf(active()), gateway)
        vm.loadArchive("encrypted".toByteArray())

        vm.preview("correct horse".toCharArray())
        advanceUntilIdle()
        assertEquals(1, gateway.previewCalls)
        assertEquals(0, gateway.applyCalls)

        vm.apply("correct horse".toCharArray(), "backup-ffffffffffffffffffffffff")
        advanceUntilIdle()
        assertEquals(0, gateway.applyCalls)
        assertEquals(BackupUiError.REVIEW_REQUIRED, vm.state.value.error)

        vm.apply("correct horse".toCharArray(), PLAN_ID)
        advanceUntilIdle()
        assertEquals(1, gateway.applyCalls)
        assertEquals(listOf("controller-state"), vm.state.value.result?.applied)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `missing companion fails without invoking gateway and passphrase is wiped`() = runTest(dispatcher) {
        val gateway = FakeBackupGateway()
        val vm = BackupViewModel(flowOf(null), gateway)
        val passphrase = "correct horse".toCharArray()

        vm.create(passphrase)
        advanceUntilIdle()

        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(0, gateway.createCalls)
        assertEquals(BackupUiError.COMPANION_REQUIRED, vm.state.value.error)
    }

    private class FakeBackupGateway : BackupGateway {
        var createCalls = 0
        var previewCalls = 0
        var applyCalls = 0

        override suspend fun create(profile: RouterProfile, token: String, passphrase: String): BackupExport {
            createCalls++
            return BackupExport("encrypted".toByteArray(), previewValue())
        }

        override suspend fun preview(
            profile: RouterProfile,
            token: String,
            archive: ByteArray,
            passphrase: String,
        ): BackupPreview {
            previewCalls++
            return previewValue()
        }

        override suspend fun apply(
            profile: RouterProfile,
            token: String,
            archive: ByteArray,
            passphrase: String,
            reviewedPlanId: String,
        ): BackupApplyResult {
            applyCalls++
            return BackupApplyResult(listOf("controller-state"), emptyList())
        }
    }

    private fun active() = ActiveRouterProfile(
        RouterProfile(
            id = "router", displayName = "Home", host = "192.0.2.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.0.2.1", mtu = 1380, keepalive = 25,
            companionUrl = "https://192.0.2.1:18779", certificatePin = "sha256/test",
        ),
        RouterSecrets(companionToken = "owner-token"),
    )

    private companion object {
        const val PLAN_ID = "backup-0123456789abcdef01234567"
        fun previewValue() = BackupPreview(1, PLAN_ID, "0.9.0", listOf(BackupPreviewEntry("controller-state", 9, true)))
    }
}
