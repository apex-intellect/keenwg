package ru.anisimov.keenwg.ui.system

import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewState

enum class RouterConnectionPrimaryAction {
    CHECK,
    RECOVER,
}

data class RouterConnectionPresentation(
    val connectionStatus: SystemConnectionStatus,
    val primaryAction: RouterConnectionPrimaryAction,
    val explainCredentials: Boolean,
    val canRecover: Boolean,
    val canChangeRouter: Boolean,
    val checking: Boolean,
)

fun routerConnectionPresentation(state: OverviewState): RouterConnectionPresentation {
    val status = when (state.health) {
        OverviewHealth.LOADING -> SystemConnectionStatus.CHECKING
        OverviewHealth.HEALTHY -> SystemConnectionStatus.CONNECTED
        OverviewHealth.DEGRADED -> SystemConnectionStatus.DEGRADED
        OverviewHealth.LOCKED -> SystemConnectionStatus.LOCKED
        OverviewHealth.SETUP_REQUIRED -> SystemConnectionStatus.SETUP_REQUIRED
    }
    val setupRequired = state.health == OverviewHealth.SETUP_REQUIRED || state.health == OverviewHealth.LOCKED
    return RouterConnectionPresentation(
        connectionStatus = status,
        primaryAction = if (setupRequired) RouterConnectionPrimaryAction.RECOVER else RouterConnectionPrimaryAction.CHECK,
        explainCredentials = setupRequired,
        canRecover = state.health == OverviewHealth.DEGRADED,
        canChangeRouter = state.health == OverviewHealth.HEALTHY || state.health == OverviewHealth.DEGRADED,
        checking = state.loading || state.health == OverviewHealth.LOADING,
    )
}
