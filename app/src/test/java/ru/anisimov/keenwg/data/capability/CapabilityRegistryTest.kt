package ru.anisimov.keenwg.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

class CapabilityRegistryTest {
    @Test fun `default endpoint values do not reveal unconfigured optional modules`() {
        val registry = CapabilityRegistry()

        assertEquals(
            emptySet<String>(),
            registry.resolve(
                profile(host = "192.168.1.1"),
                RouterSecrets(),
            ).availableIds(),
        )
    }

    @Test fun `legacy rci exposes only current WireGuard management and never history`() {
        val document = CapabilityRegistry().resolve(
            profile(
                host = "192.168.1.1",
                wireGuardConfigured = true,
            ),
            RouterSecrets(
                rciLogin = "admin",
                rciPassword = "router-password",
            ),
        )

        assertEquals(setOf("access.wireguard"), document.availableIds())
        assertEquals(setOf("rci"), document.capabilities.map { it.transport }.toSet())
    }

    @Test fun `companion declarations merge while direct module readiness remains local`() {
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

        val resolved = CapabilityRegistry().resolve(
            profile(host = "192.168.1.1", wireGuardConfigured = true),
            RouterSecrets(rciLogin = "admin", rciPassword = "router-password"),
            companion,
        )

        assertFalse(resolved.capabilities.single { it.id == "connections.xkeen" }.available)
        assertTrue(resolved.capabilities.single { it.id == "access.wireguard" }.available)
        assertEquals(9uL, resolved.stateVersion)
    }

    @Test fun `companion modules remain available without local rci credentials`() {
        val companion = CapabilityDocument(
            capabilities = listOf(
                Capability(
                    id = "access.wireguard",
                    access = CapabilityAccess.WRITE,
                    available = true,
                    transport = "companion",
                ),
                Capability(
                    id = "history.wireguard",
                    access = CapabilityAccess.READ,
                    available = true,
                    transport = "companion",
                ),
            ),
        )

        val resolved = CapabilityRegistry().resolve(
            profile(
                host = "192.168.1.1",
                wireGuardConfigured = true,
            ),
            RouterSecrets(),
            companion,
        )

        assertEquals(setOf("access.wireguard", "history.wireguard"), resolved.availableIds())
        assertEquals(setOf("companion"), resolved.capabilities.map { it.transport }.toSet())
    }

    private fun CapabilityDocument.availableIds() = capabilities.filter { it.available }.map { it.id }.toSet()

    private fun profile(
        host: String = "",
        wireGuardConfigured: Boolean = false,
    ) = RouterProfile(
        id = "router",
        displayName = "Router",
        host = host,
        rciPort = if (host.isBlank()) 0 else 80,
        interfaceId = "Wireguard0",
        serverPublicKey = if (wireGuardConfigured) "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" else "",
        endpoint = if (wireGuardConfigured) "vpn.example.com:51820" else "",
        subnetBase = "10.8.0.",
        dns = "192.168.1.1",
        mtu = 1380,
        keepalive = 25,
    )
}
