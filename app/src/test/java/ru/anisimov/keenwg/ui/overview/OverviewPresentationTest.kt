package ru.anisimov.keenwg.ui.overview

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.R

class OverviewPresentationTest {
    @Test fun `home health describes the user connection`() {
        assertEquals(
            OverviewHealthCopy(R.string.home_router_available, R.string.home_phone_connected),
            overviewHealthCopy(OverviewHealth.HEALTHY),
        )
    }

    @Test fun `home modules use task names`() {
        assertEquals(R.string.home_vpn_servers, overviewModuleTitle("connections."))
        assertEquals(R.string.home_routing_rules, overviewModuleTitle("routes."))
        assertEquals(R.string.home_remote_access, overviewModuleTitle("access."))
    }
}
