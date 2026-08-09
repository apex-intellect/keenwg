package ru.anisimov.keenwg.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.model.RouterProfile

class CapabilityRegistryTest {
    @Test fun `legacy transports independently reveal only their own capabilities`() {
        val registry = CapabilityRegistry()

        assertEquals(setOf("access.wireguard"), registry.resolve(profile(host = "192.168.1.1")).availableIds())
        assertEquals(setOf("history.wireguard"), registry.resolve(profile(collector = "http://router:18777")).availableIds())
        assertEquals(
            setOf("connections.xkeen", "routes.domains", "routes.exclusions"),
            registry.resolve(profile(xkeen = "http://router:18778")).availableIds(),
        )
    }

    @Test fun `legacy-only configured profile retains every 0_6 module`() {
        val document = CapabilityRegistry().resolve(
            profile(host = "192.168.1.1", collector = "http://router:18777", xkeen = "http://router:18778"),
        )

        assertEquals(
            setOf("access.wireguard", "history.wireguard", "connections.xkeen", "routes.domains", "routes.exclusions"),
            document.availableIds(),
        )
    }

    @Test fun `companion declaration takes precedence over legacy fallback`() {
        val companion = CapabilityDocument(
            stateVersion = 9u,
            capabilities = listOf(
                Capability(
                    id = "connections.xkeen",
                    access = CapabilityAccess.NONE,
                    available = false,
                    transport = "companion",
                    reason = "disabled",
                ),
            ),
        )

        val resolved = CapabilityRegistry().resolve(profile(xkeen = "http://router:18778"), companion)

        assertFalse(resolved.capabilities.single { it.id == "connections.xkeen" }.available)
        assertTrue(resolved.capabilities.single { it.id == "routes.domains" }.available)
        assertEquals(9uL, resolved.stateVersion)
    }

    private fun CapabilityDocument.availableIds() = capabilities.filter { it.available }.map { it.id }.toSet()

    private fun profile(host: String = "", collector: String = "", xkeen: String = "") = RouterProfile(
        id = "router",
        displayName = "Router",
        host = host,
        rciPort = if (host.isBlank()) 0 else 80,
        interfaceId = "Wireguard0",
        serverPublicKey = "",
        endpoint = "",
        subnetBase = "10.8.0.",
        dns = "192.168.1.1",
        mtu = 1380,
        keepalive = 25,
        collectorUrl = collector,
        legacyXkeenUrl = xkeen,
    )
}
