package ru.anisimov.keenwg.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings

class RouterDiscoveryTest {
    private val key0 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val key1 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA="

    @Test fun `configured interface wins and manual endpoint is preserved`() {
        val json = """{
          "first":{"id":"Wireguard0","type":"Wireguard","address":"10.8.0.1","wireguard":{"public-key":"$key0"}},
          "later":{"id":"Wireguard1","type":"Wireguard","address":"10.9.0.1","wireguard":{"public-key":"$key1"}},
          "wan":{"id":"GigabitEthernet0","defaultgw":true,"address":"203.0.113.9"}
        }"""

        val preview = RouterDiscovery.discover(json, ServerSettings(interfaceId = "Wireguard0", endpoint = "vpn.example.net:51820"))

        assertEquals("Wireguard0", preview.interfaceId)
        assertEquals(key0, preview.serverPublicKey)
        assertEquals("vpn.example.net:51820", preview.reviewedEndpoint)
        assertNull(preview.endpointCandidate)
    }

    @Test fun `empty endpoint offers only public WAN for explicit review`() {
        val json = """{
          "wg":{"id":"OtherWg","type":"Wireguard","address":"10.8.0.1","wireguard":{"public-key":"$key0","listen-port":54321}},
          "private":{"defaultgw":true,"address":"192.168.1.1"},
          "documentation":{"defaultgw":true,"address":"198.51.100.8"},
          "public":{"defaultgw":true,"address":"8.8.4.4"}
        }"""

        val preview = RouterDiscovery.discover(json, ServerSettings(interfaceId = "Missing", endpoint = ""))

        assertEquals("OtherWg", preview.interfaceId)
        assertEquals("8.8.4.4:54321", preview.endpointCandidate)
        assertEquals("", preview.reviewedEndpoint)
    }

    @Test fun `missing WireGuard listener does not invent an endpoint`() {
        val json = """{
          "wg":{"id":"Wireguard0","type":"Wireguard","address":"10.8.0.1","wireguard":{"public-key":"$key0"}},
          "wan":{"id":"GigabitEthernet1","defaultgw":true,"address":"8.8.4.4"}
        }"""

        val preview = RouterDiscovery.discover(json, ServerSettings(interfaceId = "Wireguard0", endpoint = ""))

        assertNull(preview.endpointCandidate)
    }

    @Test fun `invalid server key is an error`() {
        val json = """{"wg":{"id":"Wireguard0","type":"Wireguard","address":"10.8.0.1","wireguard":{"public-key":"bad"}}}"""
        assertTrue(runCatching { RouterDiscovery.discover(json, ServerSettings()) }.isFailure)
    }
}
