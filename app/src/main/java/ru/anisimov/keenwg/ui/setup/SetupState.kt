package ru.anisimov.keenwg.ui.setup

import ru.anisimov.keenwg.data.installer.HostKeyObservation
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallPlan
import ru.anisimov.keenwg.data.installer.InstallProbe
import ru.anisimov.keenwg.data.installer.InstallReport

sealed interface SetupState {
    data object Idle : SetupState
    data class HostKeyApproval(val key: HostKeyObservation) : SetupState
    data class Probing(val step: String) : SetupState
    data class Review(val probe: InstallProbe, val plan: InstallPlan) : SetupState
    data class Installing(val phase: InstallPhase, val progress: Float) : SetupState
    data class Completed(val profileId: String, val report: InstallReport) : SetupState
    data class Failed(
        val phase: InstallPhase,
        val safeMessage: String,
        val rollbackVerified: Boolean,
    ) : SetupState
}
