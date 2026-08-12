package ru.anisimov.keenwg.ui.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewState

class RouterConnectionPresentationTest {
    @Test fun `healthy connection checks without asking for router credentials`() {
        val model = routerConnectionPresentation(
            OverviewState(loading = false, health = OverviewHealth.HEALTHY),
        )

        assertEquals(RouterConnectionPrimaryAction.CHECK, model.primaryAction)
        assertFalse(model.explainCredentials)
        assertTrue(model.canChangeRouter)
        assertFalse(model.canRecover)
    }

    @Test fun `temporary outage can be checked before explicit recovery`() {
        val model = routerConnectionPresentation(
            OverviewState(loading = false, health = OverviewHealth.DEGRADED),
        )

        assertEquals(RouterConnectionPrimaryAction.CHECK, model.primaryAction)
        assertFalse(model.explainCredentials)
        assertTrue(model.canRecover)
    }

    @Test fun `missing protected access offers explicit credential recovery`() {
        val model = routerConnectionPresentation(
            OverviewState(loading = false, health = OverviewHealth.SETUP_REQUIRED),
        )

        assertEquals(RouterConnectionPrimaryAction.RECOVER, model.primaryAction)
        assertTrue(model.explainCredentials)
        assertFalse(model.canRecover)
    }
}
