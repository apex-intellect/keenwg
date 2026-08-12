package ru.anisimov.keenwg.data.catalog

import java.net.InetAddress
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

class SourceConfigurationClientTest {
    @Test fun `status returns only configured boolean over pinned owner path`() = runTest {
        val fixture = tlsServer(MockResponse().setBody("""{"schema_version":1,"configured":false}"""))
        try {
            val status = SourceConfigurationClient().status(fixture.profile, "owner-token", "xkeen-subscription")

            assertFalse(status.configured)
            fixture.server.takeRequest().also {
                assertEquals("GET", it.method)
                assertEquals("/v1/connections/sources/xkeen-subscription/configuration", it.path)
                assertEquals("Bearer owner-token", it.getHeader("Authorization"))
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun `replace clears caller bytes and never leaks URL in errors`() = runTest {
        val secretText = "https://vpn.example.test/sub/private"
        val secret = secretText.toByteArray()
        val fixture = tlsServer(MockResponse().setResponseCode(503).setBody(secretText))
        try {
            val failure = try {
                SourceConfigurationClient().replace(fixture.profile, "owner-token", "xkeen-subscription", secret)
                error("Expected CatalogException")
            } catch (failure: CatalogException) {
                failure
            }

            assertTrue(secret.all { it == 0.toByte() })
            assertEquals(CatalogErrorCode.UNAVAILABLE, failure.code)
            assertFalse(failure.message.orEmpty().contains("private"))
            fixture.server.takeRequest().also {
                assertEquals("PUT", it.method)
                assertTrue(it.body.readUtf8().contains(secretText))
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun `replace rejects non HTTPS input and still clears bytes`() = runTest {
        val secret = "http://vpn.example.test/sub/private".toByteArray()
        val fixture = tlsServer()
        try {
            val failure = try {
                SourceConfigurationClient().replace(fixture.profile, "owner-token", "xkeen-subscription", secret)
                error("Expected CatalogException")
            } catch (failure: CatalogException) {
                failure
            }

            assertEquals(CatalogErrorCode.INVALID_SETTINGS, failure.code)
            assertTrue(secret.all { it == 0.toByte() })
            assertEquals(0, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }

    @Test fun `unknown response fields fail closed`() = runTest {
        val fixture = tlsServer(MockResponse().setBody("""{"schema_version":1,"configured":true,"subscription_url":"hidden"}"""))
        try {
            val failure = try {
                SourceConfigurationClient().status(fixture.profile, "owner-token", "xkeen-subscription")
                error("Expected CatalogException")
            } catch (failure: CatalogException) {
                failure
            }
            assertEquals(CatalogErrorCode.UNSUPPORTED_SCHEMA, failure.code)
            assertFalse(failure.message.orEmpty().contains("hidden"))
        } finally {
            fixture.close()
        }
    }

    private fun tlsServer(vararg responses: MockResponse): Fixture {
        val server = MockWebServer()
        val loopback = InetAddress.getLoopbackAddress()
        val held = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").addSubjectAlternativeName(loopback.hostName).build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.apply {
            useHttps(certificates.sslSocketFactory(), false)
            responses.forEach(::enqueue)
            start(loopback, 0)
        }
        return Fixture(
            server,
            RouterProfile(
                id = "router-1", displayName = "Home", host = "192.168.1.1", rciPort = 80,
                interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
                dns = "192.168.1.1", mtu = 1380, keepalive = 25,
                companionUrl = "https://localhost:${server.port}",
                certificatePin = ExactPinTrustManager.pin(held.certificate),
            ),
        )
    }

    private data class Fixture(val server: MockWebServer, val profile: RouterProfile) {
        fun close() = server.shutdown()
    }
}
