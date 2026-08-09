package ru.anisimov.keenwg.domain

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings

class ServerSettingsValidatorTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test fun `rejects unsafe mutation settings`() {
        val issues = ServerSettingsValidator.validateForMutation(
            ServerSettings(
                host = "bad host",
                port = 0,
                interfaceId = "Wireguard0; reboot",
                serverPublicKey = "garbage",
                endpoint = "",
                mtu = 500,
                keepalive = 2,
            ),
        )

        assertTrue(issues.map { it.field }.containsAll(listOf("host", "port", "password", "interfaceId", "serverPublicKey", "endpoint", "mtu", "keepalive")))
    }

    @Test fun `accepts canonical key and reviewed endpoint`() {
        val issues = ServerSettingsValidator.validateForMutation(
            ServerSettings(password = "secret", serverPublicKey = key, endpoint = "vpn.example.net:51820"),
        )

        assertTrue(issues.isEmpty())
    }

    @Test fun `rejects duplicate or out of subnet peer ip`() {
        assertTrue(PeerInputValidator.validate("phone", "10.8.0.7", "10.8.0.", setOf("10.8.0.7")).isNotEmpty())
        assertTrue(PeerInputValidator.validate("unsafe name", "10.9.0.7", "10.8.0.", emptySet()).isNotEmpty())
        assertTrue(PeerInputValidator.validate("phone", "10.8.0.1", "10.8.0.", emptySet()).isNotEmpty())
        assertTrue(PeerInputValidator.validate("phone", "10.8.0.255", "10.8.0.", emptySet()).isNotEmpty())
        assertTrue(PeerInputValidator.validate("phone", "10.8.0.007", "10.8.0.", emptySet()).isNotEmpty())
    }

    @Test fun `collector is optional but cleartext public collector is rejected`() {
        assertTrue(ServerSettingsValidator.validateCollectorUrl("") == null)
        assertTrue(ServerSettingsValidator.validateCollectorUrl("http://8.8.8.8:18777") != null)
        assertTrue(ServerSettingsValidator.validateCollectorUrl("http://10.8.0.1:18777") == null)
        assertTrue(ServerSettingsValidator.validateCollectorUrl("http://100.64.0.1:18777") == null)
        assertTrue(ServerSettingsValidator.validateCollectorUrl("http://127.0.0.1:18777") != null)
        assertTrue(ServerSettingsValidator.validateCollectorUrl("http://169.254.1.1:18777") != null)
    }

    @Test fun `collector failure cannot block router peer mutations`() {
        val settings = ServerSettings(
            password = "secret",
            serverPublicKey = key,
            endpoint = "vpn.example.net:51820",
            collectorUrl = "http://8.8.8.8:18777",
        )

        assertFalse(ServerSettingsValidator.validateForMutation(settings).any { it.field == "collectorUrl" })
    }

    @Test fun `xkeen controller is optional and public cleartext is rejected`() {
        assertEquals(null, ServerSettingsValidator.validateXkeenControllerUrl(""))
        assertEquals(null, ServerSettingsValidator.validateXkeenControllerUrl("http://10.8.0.1:18778"))
        assertEquals(null, ServerSettingsValidator.validateXkeenControllerUrl("http://100.64.0.1:18778"))
        assertTrue(ServerSettingsValidator.validateXkeenControllerUrl("http://8.8.8.8:18778") != null)
        assertTrue(ServerSettingsValidator.validateXkeenControllerUrl("http://router.example.com:18778") != null)
        assertEquals(null, ServerSettingsValidator.validateXkeenControllerUrl("https://router.example.com"))
    }

    @Test fun `invalid controller cannot block peer mutations`() {
        val settings = ServerSettings(
            password = "secret",
            serverPublicKey = key,
            endpoint = "vpn.example.net:51820",
            xkeenControllerUrl = "http://8.8.8.8:18778",
        )

        assertFalse(ServerSettingsValidator.validateForMutation(settings).any { it.field == "xkeenControllerUrl" })
    }

    @Test fun `endpoint rejects paths and malformed bracketed ipv6`() {
        val base = ServerSettings(password = "secret", serverPublicKey = key)
        assertTrue(ServerSettingsValidator.validateForMutation(base.copy(endpoint = "vpn.example.net:51820/path")).any { it.field == "endpoint" })
        assertTrue(ServerSettingsValidator.validateForMutation(base.copy(endpoint = "[not-ipv6]:51820")).any { it.field == "endpoint" })
        assertTrue(ServerSettingsValidator.validateForMutation(base.copy(endpoint = "[1:2:3]:51820")).any { it.field == "endpoint" })
        assertTrue(ServerSettingsValidator.validateForMutation(base.copy(endpoint = "[1::2::3]:51820")).any { it.field == "endpoint" })
        assertFalse(ServerSettingsValidator.validateForMutation(base.copy(endpoint = "[2001:db8::1]:51820")).any { it.field == "endpoint" })
    }

    @Test fun `hostnames require valid nonempty dns labels`() {
        val base = ServerSettings(password = "secret", serverPublicKey = key, endpoint = "vpn.example.net:51820")

        for (host in listOf("bad..host", "-router.local", "router-.local", "router_local")) {
            assertTrue("host=$host", ServerSettingsValidator.validateForMutation(base.copy(host = host)).any { it.field == "host" })
        }
        assertFalse(ServerSettingsValidator.validateForMutation(base.copy(host = "router.home.arpa")).any { it.field == "host" })
    }
}
