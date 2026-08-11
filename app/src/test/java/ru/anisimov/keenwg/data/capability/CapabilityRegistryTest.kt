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
    @Test fun `direct transports independently reveal only their own capabilities`() {
        val registry = CapabilityRegistry()

        assertEquals(setOf("access.wireguard"), registry.resolve(profile(host = "192.168.1.1")).availableIds())
        assertEquals(setOf("history.wireguard"), registry.resolve(profile(collector = "http://router:18777")).availableIds())
    }

    @Test fun `rci and collector capabilities remain available without companion`() {
        val document = CapabilityRegistry().resolve(
            profile(host = "192.168.1.1", collector = "http://router:18777"),
        )

        assertEquals(
            setOf("access.wireguard", "history.wireguard"),
            document.availableIds(),
        )
        assertEquals(setOf("collector", "rci"), document.capabilities.map { it.transport }.toSet())
    }

    @Test fun `companion declarations merge with direct transports and stay authoritative`() {
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

        val resolved = CapabilityRegistry().resolve(profile(host = "192.168.1.1"), companion)

        assertFalse(resolved.capabilities.single { it.id == "connections.xkeen" }.available)
        assertTrue(resolved.capabilities.single { it.id == "access.wireguard" }.available)
        assertEquals(9uL, resolved.stateVersion)
    }

    private fun CapabilityDocument.availableIds() = capabilities.filter { it.available }.map { it.id }.toSet()

    private fun profile(host: String = "", collector: String = "") = RouterProfile(
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
    )
}
