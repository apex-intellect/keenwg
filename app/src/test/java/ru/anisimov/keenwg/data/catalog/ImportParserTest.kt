package ru.anisimov.keenwg.data.catalog

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test fun `vless reality preview exposes compatibility fields but not credential`() {
        val uuid = "11111111-2222-4333-8444-555555555555"
        val bytes = "vless://$uuid@server.example:443?type=tcp&security=reality&sni=cdn.example&fp=chrome&flow=xtls-rprx-vision&pbk=public-key#NL".toByteArray()

        val preview = ImportParser.preview(bytes, ImportOrigin.CLIPBOARD)

        assertEquals(Protocol.VLESS, preview.protocol)
        assertEquals(SourceKind.SHARE_LINK, preview.sourceKind)
        assertEquals("server.example", preview.host)
        assertEquals(443, preview.port)
        assertEquals("tcp", preview.transport)
        assertEquals("reality", preview.security)
        assertEquals("cdn.example", preview.serverName)
        assertFalse(preview.toString().contains(uuid))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `vmess and hysteria links decode strictly without preserving passwords`() {
        val vmessSecret = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val vmessJson = """{"v":"2","ps":"DE","add":"vm.example","port":"8443","id":"$vmessSecret","aid":"0","net":"ws","type":"none","host":"edge.example","path":"/ws","tls":"tls","sni":"edge.example","fp":"chrome"}"""
        val vmess = "vmess://${Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())}".toByteArray()
        val vmessPreview = ImportParser.preview(vmess, ImportOrigin.QR)
        assertEquals(Protocol.VMESS, vmessPreview.protocol)
        assertEquals("ws", vmessPreview.transport)
        assertFalse(vmessPreview.toString().contains(vmessSecret))

        val password = "hy2-password-private"
        val hy2 = "hysteria2://$password@hy.example:443?sni=cdn.example&obfs=salamander&obfs-password=another-secret".toByteArray()
        val hy2Preview = ImportParser.preview(hy2, ImportOrigin.CLIPBOARD)
        assertEquals(Protocol.HYSTERIA2, hy2Preview.protocol)
        assertEquals("tls", hy2Preview.security)
        assertFalse(hy2Preview.toString().contains(password))
        assertTrue(hy2.all { it == 0.toByte() })
    }

    @Test fun `wireguard and amnezia configs are distinguished and private keys are erased`() {
        val wg = """
            [Interface]
            PrivateKey = private-wireguard-key
            Address = 10.8.0.2/32
            DNS = 192.168.1.1
            [Peer]
            PublicKey = server-public-key
            Endpoint = vpn.example:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent().toByteArray()
        val preview = ImportParser.preview(wg, ImportOrigin.FILE)
        assertEquals(Protocol.WIREGUARD, preview.protocol)
        assertEquals("vpn.example", preview.host)
        assertFalse(preview.toString().contains("private-wireguard-key"))
        assertTrue(wg.all { it == 0.toByte() })

        val awg = """
            [Interface]
            PrivateKey = private-amnezia-key
            Address = 10.9.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            [Peer]
            PublicKey = server-public-key
            Endpoint = [2001:db8::1]:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent().toByteArray()
        val awgPreview = ImportParser.preview(awg, ImportOrigin.FILE)
        assertEquals(Protocol.AMNEZIAWG, awgPreview.protocol)
        assertEquals("2001:db8::1", awgPreview.host)
    }

    @Test fun `subscription preview validates URL and never returns its path`() {
        val secretPath = "private-subscription-id"
        val input = "https://provider.example/sub/$secretPath".toByteArray()
        val preview = ImportParser.preview(input, ImportOrigin.CLIPBOARD)
        assertEquals(SourceKind.SUBSCRIPTION, preview.sourceKind)
        assertEquals(null, preview.protocol)
        assertEquals("provider.example", preview.host)
        assertFalse(preview.toString().contains(secretPath))
    }

    @Test fun `unknown fields malformed endpoint and oversized input fail with fixed codes and erase bytes`() {
        val unknown = "vless://11111111-2222-4333-8444-555555555555@server.example:443?shell=reboot".toByteArray()
        assertEquals(ImportErrorCode.UNSUPPORTED_FIELD, failure(unknown).code)
        assertTrue(unknown.all { it == 0.toByte() })

        val malformed = "trojan://password@server.example:99999?security=tls".toByteArray()
        assertEquals(ImportErrorCode.INVALID_ENDPOINT, failure(malformed).code)

        val oversized = ByteArray(1_048_577) { 'a'.code.toByte() }
        assertEquals(ImportErrorCode.TOO_LARGE, failure(oversized).code)
        assertTrue(oversized.all { it == 0.toByte() })
    }

    private fun failure(bytes: ByteArray): ImportException = try {
        ImportParser.preview(bytes, ImportOrigin.CLIPBOARD)
        error("Expected ImportException")
    } catch (failure: ImportException) {
        failure
    }
}
