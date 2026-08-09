package ru.anisimov.keenwg.data.rci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import ru.anisimov.keenwg.data.crypto.WgKeys
import org.junit.Test

class RciResponseTest {
    private val configuredKey = WgKeys.generate().publicKey
    private val otherConfiguredKey = WgKeys.generate().publicKey
    // Real success envelope captured from the live router (2026-06-23).
    private val addOk =
        """[{"parse":{"prompt":"(config-wg-peer)","status":[{"status":"message","code":"75500972","ident":"Wireguard::Interface","message":"\"Wireguard0\": created peer \"X\"."}]}}]"""
    private val errResp =
        """[{"parse":{"prompt":"(config)","status":[{"status":"error","code":"1","ident":"X","message":"bad key"}]}}]"""

    @Test fun success_has_no_error() {
        assertNull(RciResponse.firstError(addOk))
    }

    @Test fun error_detected() {
        assertEquals("bad key", RciResponse.firstError(errResp)?.message)
    }

    @Test fun parse_peers() {
        val j = """{"id":"Wireguard0","wireguard":{"public-key":"S","peer":[
            {"public-key":"AAA","description":"larisa-iphone","rxbytes":1,"txbytes":2,"last-handshake":117,"online":true,"enabled":true}]}}"""
        val p = RciResponse.peers(j).single()
        assertEquals("larisa-iphone", p.description)
        assertTrue(p.online)
        assertEquals(117L, p.lastHandshakeSec)
        assertEquals(1L, p.rxBytes)
        assertEquals(2L, p.txBytes)
    }

    @Test fun allow_ips_from_runningconfig() {
        val j = """{"message":["interface Wireguard0","    wireguard peer AAA !larisa-iphone","        allow-ips 10.8.0.5 255.255.255.255","        connect","    !","    up","!"]}"""
        assertEquals("10.8.0.5", RciResponse.allowIpsByPubkey(j)["AAA"])
    }

    @Test fun configured_peers_are_scoped_to_requested_interface() {
        val j = """{"message":[
          "interface Wireguard0",
          "    wireguard peer $configuredKey !anna-phone",
          "        allow-ips 10.8.0.5 255.255.255.255",
          "        keepalive-interval 25",
          "        connect",
          "    !",
          "!",
          "interface Wireguard1",
          "    wireguard peer $otherConfiguredKey !other",
          "        allow-ips 10.9.0.5 255.255.255.255",
          "        no connect",
          "    !"
        ]}"""

        val peer = RciResponse.configuredPeers(j, "Wireguard0").single()

        assertEquals(configuredKey, peer.publicKey)
        assertEquals("anna-phone", peer.name)
        assertEquals("10.8.0.5", peer.allowIp)
        assertEquals(25, peer.keepalive)
        assertTrue(peer.enabled)
        assertFalse(peer.restoreSuffixes.any { it.contains(otherConfiguredKey) })
    }

    @Test fun unknown_peer_subcommand_blocks_unsafe_restore() {
        val j = """{"message":["interface Wireguard0"," wireguard peer $configuredKey !phone","  mysterious-option value"," !"]}"""
        assertThrows(UnsupportedPeerConfigException::class.java) {
            RciResponse.configuredPeers(j, "Wireguard0")
        }
    }

    @Test fun configured_peers_fail_closed_when_snapshot_shape_or_header_is_unsafe() {
        assertThrows(UnsupportedPeerConfigException::class.java) {
            RciResponse.configuredPeers("{}", "Wireguard0")
        }
        val injected = """{"message":["interface Wireguard0"," wireguard peer $configuredKey !phone extra"," !"]}"""
        assertThrows(UnsupportedPeerConfigException::class.java) {
            RciResponse.configuredPeers(injected, "Wireguard0")
        }
    }

    @Test fun nested_error_is_detected_recursively() {
        val j = """{"outer":{"items":[{"system":{"configuration":{"save":{"status":[{"status":"error","message":"save failed"}]}}}}]}}"""
        assertEquals("save failed", RciResponse.firstError(j)?.message)
    }
}
