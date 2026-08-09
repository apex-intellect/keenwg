package ru.anisimov.keenwg.data.companion

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.anisimov.keenwg.domain.model.RouterProfile
import java.net.InetAddress

class CompanionClientTest {
    @Test fun `pairing omits bearer while protected calls use selected device token`() = runTest {
        val fixture = tlsServer(
            MockResponse().setBody("""{"schema_version":1,"device_id":"phone-1","scope":"owner","token":"new-token"}"""),
            MockResponse().setBody(capabilitiesJson()),
        )
        val client = HttpCompanionClient()

        val credential = client.exchange(fixture.profile, "offer-1", "pair-secret", "Pixel")
        val capabilities = client.capabilities(fixture.profile, "device-token")

        assertEquals("new-token", credential.token)
        assertEquals(1, capabilities.capabilities.size)
        fixture.server.takeRequest().also { request ->
            assertEquals("/v1/pairing/exchange", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(
                """{"schema_version":1,"offer_id":"offer-1","secret":"pair-secret","device_label":"Pixel"}""",
                request.body.readUtf8(),
            )
        }
        fixture.server.takeRequest().also { request ->
            assertEquals("/v1/capabilities", request.path)
            assertEquals("Bearer device-token", request.getHeader("Authorization"))
        }
        fixture.close()
    }

    @Test fun `unknown schema and unauthorized responses map to fixed safe errors`() = runTest {
        val fixture = tlsServer(
            MockResponse().setBody(capabilitiesJson(schemaVersion = 2)),
            MockResponse().setResponseCode(401).setBody("device-token must never escape"),
        )
        val client = HttpCompanionClient()

        assertEquals(CompanionErrorCode.UNSUPPORTED_SCHEMA, failure { client.capabilities(fixture.profile, "device-token") }.code)
        val unauthorized = failure { client.capabilities(fixture.profile, "device-token") }
        assertEquals(CompanionErrorCode.UNAUTHORIZED, unauthorized.code)
        assertFalse(unauthorized.message.orEmpty().contains("device-token"))
        fixture.close()
    }

    @Test fun `devices and revocation use strict endpoints and bearer`() = runTest {
        val fixture = tlsServer(
            MockResponse().setBody("""{"schema_version":1,"devices":[{"id":"phone-1","label":"Pixel","scope":"owner","created_at":"2026-08-08T10:00:00Z","last_used":"2026-08-08T11:00:00Z"}]}"""),
            MockResponse().setResponseCode(204),
        )
        val client = HttpCompanionClient()

        assertEquals("phone-1", client.devices(fixture.profile, "owner-token").single().id)
        client.revokeDevice(fixture.profile, "owner-token", "phone-1")

        assertEquals("/v1/devices", fixture.server.takeRequest().path)
        fixture.server.takeRequest().also { request ->
            assertEquals("DELETE", request.method)
            assertEquals("/v1/devices/phone-1", request.path)
            assertEquals("Bearer owner-token", request.getHeader("Authorization"))
        }
        fixture.close()
    }

    @Test fun `viewer offer creation and dismissal use owner bearer and strict paths`() = runTest {
        val fixture = tlsServer(
            MockResponse().setResponseCode(201).setBody(
                """{"schema_version":1,"offer_id":"offer-1","secret":"one-use","scope":"viewer","expires_at":"2026-08-09T12:05:00Z"}""",
            ),
            MockResponse().setResponseCode(204),
        )
        val client = HttpCompanionClient()

        val offer = client.createOffer(fixture.profile, "owner-token", DeviceScope.VIEWER)
        client.revokeOffer(fixture.profile, "owner-token", offer.offerId)

        assertEquals("one-use", offer.secret)
        fixture.server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/v1/pairing/offers", request.path)
            assertEquals("Bearer owner-token", request.getHeader("Authorization"))
            assertEquals("""{"schema_version":1,"scope":"viewer"}""", request.body.readUtf8())
        }
        fixture.server.takeRequest().also { request ->
            assertEquals("DELETE", request.method)
            assertEquals("/v1/pairing/offers/offer-1", request.path)
            assertEquals("Bearer owner-token", request.getHeader("Authorization"))
        }
        fixture.close()
    }

    private fun tlsServer(vararg responses: MockResponse): Fixture {
        val server = MockWebServer()
        val loopback = InetAddress.getLoopbackAddress()
        val held = HeldCertificate.Builder()
            .commonName(loopback.hostName)
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1")
            .addSubjectAlternativeName(loopback.hostName)
            .build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.apply {
            useHttps(certificates.sslSocketFactory(), false)
            responses.forEach(::enqueue)
            start(loopback, 0)
        }
        return Fixture(
            server,
            RouterProfile(
                id = "router-1",
                displayName = "Home",
                host = "192.168.1.1",
                rciPort = 80,
                interfaceId = "Wireguard0",
                serverPublicKey = "",
                endpoint = "",
                subnetBase = "10.8.0.",
                dns = "192.168.1.1",
                mtu = 1380,
                keepalive = 25,
                companionUrl = "https://localhost:${server.port}",
                certificatePin = ExactPinTrustManager.pin(held.certificate),
                collectorUrl = "",
                legacyXkeenUrl = "",
            ),
        )
    }

    private fun capabilitiesJson(schemaVersion: Int = 1) = """{
      "schema_version":$schemaVersion,"state_version":7,"capabilities":[
        {"id":"overview.health","schema_version":1,"access":"read","available":true,"transport":"companion"}
      ]
    }"""

    private suspend fun failure(block: suspend () -> Unit): CompanionException =
        try {
            block()
            error("Expected CompanionException")
        } catch (failure: CompanionException) {
            failure
        }

    private data class Fixture(val server: MockWebServer, val profile: RouterProfile) {
        fun close() = server.shutdown()
    }
}
