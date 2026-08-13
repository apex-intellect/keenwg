package ru.anisimov.keenwg.ui.setup

import ru.anisimov.keenwg.data.installer.HostKeyObservation
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallProbe
import ru.anisimov.keenwg.data.installer.InstallReport

enum class SetupProgress {
    CONNECTING,
    CHECKING_ROUTER,
    PREPARING_ACCESS,
    VERIFYING_ACCESS,
    FINISHING,
}

enum class SetupPrerequisite {
    ENTWARE,
    STORAGE,
}

sealed interface SetupState {
    data object Credentials : SetupState

    data class Checking(
        val progress: SetupProgress,
        val phase: InstallPhase? = null,
    ) : SetupState

    data class PrerequisiteMissing(
        val probe: InstallProbe,
        val missing: Set<SetupPrerequisite>,
    ) : SetupState

    data class HostKeyChanged(
        val expected: HostKeyObservation,
        val observed: HostKeyObservation,
    ) : SetupState

    data class Completed(
        val profileId: String,
        val report: InstallReport,
    ) : SetupState

    data class Failed(
        val phase: InstallPhase,
        val rollbackVerified: Boolean,
    ) : SetupState
}
