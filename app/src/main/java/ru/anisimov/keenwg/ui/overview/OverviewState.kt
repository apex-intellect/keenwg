package ru.anisimov.keenwg.ui.overview

import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination

enum class OverviewHealth {
    LOADING,
    HEALTHY,
    LEGACY,
    DEGRADED,
    SETUP_REQUIRED,
    LOCKED,
}

data class OverviewState(
    val loading: Boolean = true,
    val health: OverviewHealth = OverviewHealth.LOADING,
    val profiles: List<RouterProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedProfileName: String? = null,
    val showProfileSelector: Boolean = false,
    val capabilities: CapabilityDocument? = null,
    val destinations: List<TopLevelDestination> = listOf(
        TopLevelDestination.OVERVIEW,
        TopLevelDestination.SYSTEM,
    ),
    val activeXkeenNode: String? = null,
    val message: String? = null,
    val mutationsEnabled: Boolean = false,
)
