package ru.anisimov.keenwg.data.support

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile
import java.net.InetAddress

class SupportClientTest {
    @Test fun `support bundle uses pinned viewer request and preserves reviewable json and text`() = runTest {
        val fixture = tlsServer(MockResponse().setBody(bundleJson()))

        val export = SupportClient().generate(fixture.profile, "viewer-token")

        assertEquals("2026-08-09T05:30:00Z", export.bundle.generatedAt)
        assertEquals(listOf("dns", "ipv4", "ipv6", "tcp", "quic"), export.bundle.report.checks.map { it.layer })
        assertTrue(export.text.contains("KeenWG support report"))
        assertTrue(export.json.contains("\"observation\""))
        fixture.server.takeRequest().also {
            assertEquals("GET", it.method)
            assertEquals("/v1/support/report", it.path)
            assertEquals("Bearer viewer-token", it.getHeader("Authorization"))
            assertEquals("no-store", it.getHeader("Cache-Control"))
        }
        fixture.close()
    }

    @Test fun `oversized unknown or error responses fail without leaking body`() = runTest {
        val secret = "private-token-at-hidden.example"
        val oversized = tlsServer(MockResponse().setBody("x".repeat(70_000)))
        val largeFailure = runCatching { SupportClient().generate(oversized.profile, "viewer-token") }.exceptionOrNull()!!
        assertFalse(largeFailure.message.orEmpty().contains(secret))
        oversized.close()

        val unknown = tlsServer(MockResponse().setBody(bundleJson().dropLast(1) + ",\"secret\":\"$secret\"}"))
        val schemaFailure = runCatching { SupportClient().generate(unknown.profile, "viewer-token") }.exceptionOrNull()!!
        assertFalse(schemaFailure.message.orEmpty().contains(secret))
        unknown.close()

        val rejected = tlsServer(MockResponse().setResponseCode(503).setBody(secret))
        val requestFailure = runCatching { SupportClient().generate(rejected.profile, "viewer-token") }.exceptionOrNull()!!
        assertFalse(requestFailure.message.orEmpty().contains(secret))
        rejected.close()
    }

    private fun tlsServer(response: MockResponse): Fixture {
        val server = MockWebServer()
        val loopback = InetAddress.getLoopbackAddress()
        val held = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").addSubjectAlternativeName(loopback.hostName).build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(response)
        server.start(loopback, 0)
        return Fixture(server, RouterProfile(
            id = "router-1", displayName = "Home", host = "192.0.2.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.0.2.1", mtu = 1380, keepalive = 25,
            companionUrl = "https://localhost:${server.port}", certificatePin = ExactPinTrustManager.pin(held.certificate),
        ))
    }

    private fun bundleJson(): String {
        val at = "2026-08-09T05:30:00Z"
        val checks = listOf("dns", "ipv4", "ipv6", "tcp", "quic").joinToString(",") { layer ->
            """{"layer":"$layer","status":"ok","duration_ms":4,"observation":{"code":"observed_$layer","at":"$at"},"inference":{"code":"inferred_$layer","at":"$at"}}"""
        }
        return """{"schema_version":1,"generated_at":"$at","report":{"schema_version":1,"generated_at":"$at","summary":{"version":"0.9.0","state_version":9,"active":true,"node_count":3,"target_kind":"domain","transport":"tcp"},"checks":[$checks],"notes":[]},"review_text":"KeenWG support report\nGenerated: $at\n"}"""
    }

    private data class Fixture(val server: MockWebServer, val profile: RouterProfile) {
        fun close() = server.shutdown()
    }
}
