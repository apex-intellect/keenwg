package ru.anisimov.keenwg.ui.setup

import ru.anisimov.keenwg.data.installer.InstallPhase

enum class SetupRowStatus {
    COMPLETE,
    ACTIVE,
    PENDING,
}

fun InstallPhase.toSetupProgress(): SetupProgress = when (this) {
    InstallPhase.VERIFY_ASSET,
    InstallPhase.CONNECT,
    InstallPhase.PROBE,
    InstallPhase.UPLOAD,
    InstallPhase.INSTALL,
    InstallPhase.PAIRING_OFFER,
    -> SetupProgress.PREPARING_ACCESS

    InstallPhase.PAIRING_EXCHANGE,
    InstallPhase.HEALTH,
    -> SetupProgress.VERIFYING_ACCESS

    InstallPhase.SAVE_PROFILE,
    InstallPhase.CLEANUP,
    -> SetupProgress.FINISHING
}

fun setupRowStatus(progress: SetupProgress, row: Int): SetupRowStatus {
    require(row in 0..3)
    val active = when (progress) {
        SetupProgress.CONNECTING -> 0
        SetupProgress.CHECKING_ROUTER -> 1
        SetupProgress.PREPARING_ACCESS -> 2
        SetupProgress.VERIFYING_ACCESS,
        SetupProgress.FINISHING,
        -> 3
    }
    return when {
        row < active -> SetupRowStatus.COMPLETE
        row == active -> SetupRowStatus.ACTIVE
        else -> SetupRowStatus.PENDING
    }
}
