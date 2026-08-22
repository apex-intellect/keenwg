package ru.anisimov.keenwg.data.catalog

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

class CatalogClientTest {
    @Test fun `snapshot and mutation use pinned companion paths bearer and unsigned versions`() = runTest {
        val fixture = tlsServer(
            MockResponse().setBody(catalogJson(ULong.MAX_VALUE)),
            MockResponse().setBody(operationJson(ULong.MAX_VALUE)),
        )
        val client = CatalogClient()

        val snapshot = client.snapshot(fixture.profile, "viewer-token")
        val result = client.createGroup(fixture.profile, "operator-token", snapshot.stateVersion, "create-group-0001", "Work")

        assertEquals(ULong.MAX_VALUE, snapshot.stateVersion)
        assertEquals("committed", result.result)
        fixture.server.takeRequest().also {
            assertEquals("/v1/connections/catalog", it.path)
            assertEquals("Bearer viewer-token", it.getHeader("Authorization"))
            assertEquals("subscription-metadata-v1", it.getHeader("KeenWG-Catalog-Features"))
        }
        fixture.server.takeRequest().also {
            assertEquals("POST", it.method)
            assertEquals("/v1/connections/groups", it.path)
            assertEquals("Bearer operator-token", it.getHeader("Authorization"))
            assertTrue(it.body.readUtf8().contains("18446744073709551615"))
        }
        fixture.close()
    }

    @Test fun `source bytes are zeroed and never appear in failures`() = runTest {
        val secretText = "vless://private-user-id@vpn.example:443"
        val fixture = tlsServer(MockResponse().setResponseCode(503).setBody(secretText))
        val source = secretText.toByteArray()

        val failure = try {
            CatalogClient().saveSource(
                fixture.profile, "operator-token", 1u, "create-source-0001",
                CatalogSourceDraft("primary", SourceKind.SHARE_LINK, "Personal", "catalog"), source,
            )
            error("Expected CatalogException")
        } catch (failure: CatalogException) {
            failure
        }

        assertTrue(source.all { it == 0.toByte() })
        assertEquals(CatalogErrorCode.UNAVAILABLE, failure.code)
        assertFalse(failure.message.orEmpty().contains("private-user-id"))
        fixture.server.takeRequest().also {
            assertEquals("/v1/connections/sources", it.path)
            assertEquals("Bearer operator-token", it.getHeader("Authorization"))
            assertTrue(it.body.readUtf8().contains("private-user-id"))
        }
        fixture.close()
    }

    @Test fun `unknown response fields fail closed`() = runTest {
        val fixture = tlsServer(MockResponse().setBody(catalogJson(1u).dropLast(1) + ",\"secret\":\"must-not-escape\"}"))
        val failure = try {
            CatalogClient().snapshot(fixture.profile, "viewer-token")
            error("Expected CatalogException")
        } catch (failure: CatalogException) {
            failure
        }
        assertEquals(CatalogErrorCode.UNSUPPORTED_SCHEMA, failure.code)
        assertFalse(failure.message.orEmpty().contains("must-not-escape"))
        fixture.close()
    }

    @Test fun `snapshot decodes public subscription metadata without exposing its link`() = runTest {
        val source = """{"id":"owned-source","group_id":"primary","kind":"subscription","label":"provider.example","adapter_id":"catalog","status":"ready","node_count":0,"warnings":[],"foreign":false,"subscription_info":{"profile_title":"ScufVPN","upload_bytes":10,"download_bytes":20,"total_bytes":100,"expires_at":1850601905}}"""
        val body = catalogJson(2u).replace("\"sources\":[]", "\"sources\":[$source]")
        val fixture = tlsServer(MockResponse().setBody(body))

        val snapshot = CatalogClient().snapshot(fixture.profile, "viewer-token")

        val info = snapshot.sources.single().subscriptionInfo
        assertEquals("ScufVPN", info?.profileTitle)
        assertEquals(20L, info?.downloadBytes)
        assertFalse(body.contains("private-token"))
        fixture.close()
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
        return Fixture(server, RouterProfile(
            id = "router-1", displayName = "Home", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            companionUrl = "https://localhost:${server.port}",
            certificatePin = ExactPinTrustManager.pin(held.certificate),
        ))
    }

    private fun catalogJson(version: ULong) = """{"schema_version":1,"state_version":$version,"groups":[{"id":"primary","label":"Primary","order":0}],"sources":[],"nodes":[]}"""
    private fun operationJson(version: ULong) = """{"schema_version":1,"result":"committed","catalog":${catalogJson(version)}}"""

    private data class Fixture(val server: MockWebServer, val profile: RouterProfile) {
        fun close() = server.shutdown()
    }
}
