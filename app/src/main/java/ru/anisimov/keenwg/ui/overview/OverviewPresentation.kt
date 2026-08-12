package ru.anisimov.keenwg.ui.overview

import ru.anisimov.keenwg.R

internal data class OverviewHealthCopy(val titleResource: Int, val bodyResource: Int)

internal fun overviewHealthCopy(health: OverviewHealth): OverviewHealthCopy = when (health) {
    OverviewHealth.LOADING -> OverviewHealthCopy(R.string.home_checking_router, R.string.home_loading_capabilities)
    OverviewHealth.HEALTHY -> OverviewHealthCopy(R.string.home_router_available, R.string.home_phone_connected)
    OverviewHealth.DEGRADED -> OverviewHealthCopy(R.string.home_connection_limited, R.string.home_check_connection)
    OverviewHealth.SETUP_REQUIRED -> OverviewHealthCopy(R.string.home_setup_required, R.string.home_connect_router)
    OverviewHealth.LOCKED -> OverviewHealthCopy(R.string.home_profiles_locked, R.string.home_recovery_required)
}

internal fun overviewModuleTitle(prefix: String): Int = when (prefix) {
    "connections." -> R.string.home_vpn_servers
    "routes." -> R.string.home_routing_rules
    "access." -> R.string.home_remote_access
    else -> R.string.home_router_settings
}
