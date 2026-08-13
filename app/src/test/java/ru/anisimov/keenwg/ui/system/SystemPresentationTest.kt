package ru.anisimov.keenwg.ui.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewState

class SystemPresentationTest {
    @Test fun `healthy router exposes the complete system menu`() {
        val state = OverviewState(
            loading = false,
            health = OverviewHealth.HEALTHY,
            selectedProfileName = "Keenetic",
            mutationsEnabled = true,
            capabilities = CapabilityDocument(
                capabilities = listOf(
                    Capability(
                        id = "system.devices",
                        access = CapabilityAccess.WRITE,
                        available = true,
                        transport = "companion",
                    ),
                ),
            ),
        )

        val model = systemPresentation(state)

        assertEquals(SystemConnectionStatus.CONNECTED, model.connectionStatus)
        assertEquals(
            listOf(
                SystemAction.CONNECTION,
                SystemAction.DEVICES,
                SystemAction.DIAGNOSTICS,
                SystemAction.BACKUP,
                SystemAction.LANGUAGE,
                SystemAction.ABOUT,
            ),
            model.rows.map { it.action },
        )
        assertTrue(model.rows.all { it.enabled })
    }

    @Test fun `setup required keeps recovery available and disables dependent tools`() {
        val model = systemPresentation(
            OverviewState(
                loading = false,
                health = OverviewHealth.SETUP_REQUIRED,
                mutationsEnabled = false,
            ),
        )

        assertEquals(SystemConnectionStatus.SETUP_REQUIRED, model.connectionStatus)
        assertTrue(model.rows.single { it.action == SystemAction.CONNECTION }.enabled)
        assertFalse(model.rows.single { it.action == SystemAction.DIAGNOSTICS }.enabled)
        assertFalse(model.rows.single { it.action == SystemAction.BACKUP }.enabled)
        assertTrue(model.rows.single { it.action == SystemAction.ABOUT }.enabled)
        assertFalse(model.rows.any { it.action == SystemAction.DEVICES })
    }
}
