package ru.anisimov.keenwg.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.data.installer.InstallPhase

class SetupPresentationTest {
    @Test fun `every installer phase maps to a stable product progress step`() {
        val expected = mapOf(
            InstallPhase.VERIFY_ASSET to SetupProgress.PREPARING_ACCESS,
            InstallPhase.CONNECT to SetupProgress.PREPARING_ACCESS,
            InstallPhase.PROBE to SetupProgress.PREPARING_ACCESS,
            InstallPhase.UPLOAD to SetupProgress.PREPARING_ACCESS,
            InstallPhase.INSTALL to SetupProgress.PREPARING_ACCESS,
            InstallPhase.PAIRING_OFFER to SetupProgress.PREPARING_ACCESS,
            InstallPhase.PAIRING_EXCHANGE to SetupProgress.VERIFYING_ACCESS,
            InstallPhase.HEALTH to SetupProgress.VERIFYING_ACCESS,
            InstallPhase.SAVE_PROFILE to SetupProgress.FINISHING,
            InstallPhase.CLEANUP to SetupProgress.FINISHING,
        )

        assertEquals(InstallPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, progress) -> assertEquals(progress, phase.toSetupProgress()) }
    }

    @Test fun `progress rows expose one active step and completed predecessors`() {
        assertEquals(
            listOf(SetupRowStatus.COMPLETE, SetupRowStatus.COMPLETE, SetupRowStatus.ACTIVE, SetupRowStatus.PENDING),
            (0..3).map { setupRowStatus(SetupProgress.PREPARING_ACCESS, it) },
        )
        assertEquals(
            listOf(SetupRowStatus.COMPLETE, SetupRowStatus.COMPLETE, SetupRowStatus.COMPLETE, SetupRowStatus.ACTIVE),
            (0..3).map { setupRowStatus(SetupProgress.FINISHING, it) },
        )
    }
}
