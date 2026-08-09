package ru.anisimov.keenwg.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument

class TopLevelDestinationTest {
    @Test fun `five semantic destinations have fixed discoverable order`() {
        assertEquals(
            listOf("Обзор", "Связи", "Маршруты", "Доступ", "Система"),
            TopLevelDestination.entries.map { it.label },
        )
        assertEquals(5, TopLevelDestination.entries.map { it.routeKey }.toSet().size)
        assertTrue(TopLevelDestination.entries.all { it.contentDescription.isNotBlank() })
    }

    @Test fun `optional destinations follow independent available capabilities`() {
        val document = CapabilityDocument(capabilities = listOf(
            capability("connections.xkeen"),
            capability("access.wireguard"),
        ))

        val visible = visibleTopLevelDestinations(document, locked = false)

        assertEquals(
            listOf(TopLevelDestination.OVERVIEW, TopLevelDestination.CONNECTIONS, TopLevelDestination.ACCESS, TopLevelDestination.SYSTEM),
            visible,
        )
        assertFalse(visible.contains(TopLevelDestination.ROUTES))
    }

    @Test fun `locked profile exposes only safe destinations`() {
        assertEquals(
            listOf(TopLevelDestination.OVERVIEW, TopLevelDestination.SYSTEM),
            visibleTopLevelDestinations(null, locked = true),
        )
    }

    @Test fun `current destination survives refresh while still available`() {
        val available = listOf(TopLevelDestination.OVERVIEW, TopLevelDestination.CONNECTIONS, TopLevelDestination.SYSTEM)
        assertEquals(TopLevelDestination.CONNECTIONS, preserveTopLevelDestination(TopLevelDestination.CONNECTIONS, available))
        assertEquals(TopLevelDestination.OVERVIEW, preserveTopLevelDestination(TopLevelDestination.ACCESS, available))
    }

    private fun capability(id: String) = Capability(
        id = id,
        access = CapabilityAccess.READ,
        available = true,
        transport = "test",
    )
}
