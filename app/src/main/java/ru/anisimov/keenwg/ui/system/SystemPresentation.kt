package ru.anisimov.keenwg.ui.system

import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewState

enum class SystemConnectionStatus {
    CHECKING,
    CONNECTED,
    DEGRADED,
    LOCKED,
    SETUP_REQUIRED,
}

enum class SystemAction {
    CONNECTION,
    DEVICES,
    DIAGNOSTICS,
    BACKUP,
    ADVANCED,
}

data class SystemRow(
    val action: SystemAction,
    val enabled: Boolean,
)

data class SystemPresentation(
    val profileName: String?,
    val connectionStatus: SystemConnectionStatus,
    val availableModuleCount: Int,
    val rows: List<SystemRow>,
)

fun systemPresentation(state: OverviewState): SystemPresentation {
    val capabilities = state.capabilities?.capabilities.orEmpty()
    val status = when (state.health) {
        OverviewHealth.LOADING -> SystemConnectionStatus.CHECKING
        OverviewHealth.HEALTHY -> SystemConnectionStatus.CONNECTED
        OverviewHealth.DEGRADED -> SystemConnectionStatus.DEGRADED
        OverviewHealth.LOCKED -> SystemConnectionStatus.LOCKED
        OverviewHealth.SETUP_REQUIRED -> SystemConnectionStatus.SETUP_REQUIRED
    }
    val routerDependentToolsAvailable = state.health != OverviewHealth.SETUP_REQUIRED &&
        state.health != OverviewHealth.LOADING

    return SystemPresentation(
        profileName = state.selectedProfileName,
        connectionStatus = status,
        availableModuleCount = capabilities.count { it.available },
        rows = buildList {
            add(SystemRow(SystemAction.CONNECTION, enabled = true))
            if (capabilities.any { it.id == "system.devices" && it.available }) {
                add(SystemRow(SystemAction.DEVICES, enabled = true))
            }
            add(SystemRow(SystemAction.DIAGNOSTICS, enabled = routerDependentToolsAvailable))
            add(SystemRow(SystemAction.BACKUP, enabled = routerDependentToolsAvailable))
            add(SystemRow(SystemAction.ADVANCED, enabled = true))
        },
    )
}
