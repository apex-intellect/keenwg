package ru.anisimov.keenwg.data.backup

import java.net.InetAddress
import java.util.Base64
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

class BackupClientTest {
    @Test fun `create preview and apply use pinned owner requests and exact reviewed plan`() = runTest {
        val archive = "encrypted archive".toByteArray()
        val planId = "backup-0123456789abcdef01234567"
        val fixture = tlsServer(
            MockResponse().setBody(
                """{"schema_version":1,"archive":"${Base64.getEncoder().encodeToString(archive)}","preview":${previewJson(planId)}}""",
            ),
            MockResponse().setBody(previewJson(planId)),
            MockResponse().setBody("""{"applied":["controller-state"],"skipped_foreign":["foreign"]}"""),
        )
        val client = BackupClient()

        val created = client.create(fixture.profile, "owner-token", "correct horse")
        val preview = client.preview(fixture.profile, "owner-token", archive, "correct horse")
        val applied = client.apply(fixture.profile, "owner-token", archive, "correct horse", planId)

        assertTrue(created.archive.contentEquals(archive))
        assertEquals(planId, preview.planId)
        assertEquals(listOf("controller-state"), applied.applied)
        assertEquals(listOf("foreign"), applied.skippedForeign)
        listOf("/v1/backup", "/v1/backup/preview", "/v1/backup/apply").forEach { path ->
            fixture.server.takeRequest().also {
                assertEquals("POST", it.method)
                assertEquals(path, it.path)
                assertEquals("Bearer owner-token", it.getHeader("Authorization"))
                assertEquals("no-store", it.getHeader("Cache-Control"))
            }
        }
        fixture.close()
    }

    @Test fun `unknown oversized and invalid plan responses fail without leaking body`() = runTest {
        val secret = "hidden-backup-secret"
        val unknown = tlsServer(MockResponse().setBody(previewJson("backup-0123456789abcdef01234567").dropLast(1) + ",\"secret\":\"$secret\"}"))
        val unknownFailure = runCatching {
            BackupClient().preview(unknown.profile, "owner-token", byteArrayOf(1), "correct horse")
        }.exceptionOrNull()!!
        assertFalse(unknownFailure.message.orEmpty().contains(secret))
        unknown.close()

        val oversized = tlsServer(MockResponse().setBody("x".repeat(6 * 1024 * 1024 + 1)))
        val oversizedFailure = runCatching {
            BackupClient().preview(oversized.profile, "owner-token", byteArrayOf(1), "correct horse")
        }.exceptionOrNull()!!
        assertFalse(oversizedFailure.message.orEmpty().contains(secret))
        oversized.close()

        val rejected = tlsServer(MockResponse().setResponseCode(503).setBody(secret))
        val requestFailure = runCatching {
            BackupClient().preview(rejected.profile, "owner-token", byteArrayOf(1), "correct horse")
        }.exceptionOrNull()!!
        assertFalse(requestFailure.message.orEmpty().contains(secret))
        rejected.close()

        val invalidPlan = runCatching {
            BackupClient().apply(profile(), "owner-token", byteArrayOf(1), "correct horse", "wrong")
        }.exceptionOrNull()
        assertTrue(invalidPlan is IllegalArgumentException)
    }

    private fun previewJson(planId: String) =
        """{"schema_version":1,"plan_id":"$planId","source_version":"0.9.0","entries":[{"id":"controller-state","bytes":17,"owned":true}]}"""

    private fun tlsServer(vararg responses: MockResponse): Fixture {
        val server = MockWebServer()
        val loopback = InetAddress.getLoopbackAddress()
        val held = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1")
            .addSubjectAlternativeName(loopback.hostName)
            .build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(certificates.sslSocketFactory(), false)
        responses.forEach(server::enqueue)
        server.start(loopback, 0)
        return Fixture(server, profile("https://localhost:${server.port}", ExactPinTrustManager.pin(held.certificate)))
    }

    private fun profile(url: String = "https://192.0.2.1:18779", pin: String = "sha256/test") = RouterProfile(
        id = "router-1", displayName = "Home", host = "192.0.2.1", rciPort = 80,
        interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
        dns = "192.0.2.1", mtu = 1380, keepalive = 25, companionUrl = url, certificatePin = pin,
    )

    private data class Fixture(val server: MockWebServer, val profile: RouterProfile) {
        fun close() = server.shutdown()
    }
}
