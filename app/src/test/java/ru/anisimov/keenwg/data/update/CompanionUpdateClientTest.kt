package ru.anisimov.keenwg.data.update

import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.installer.CompanionAssetManifest
import ru.anisimov.keenwg.data.installer.VerifiedCompanionAsset
import ru.anisimov.keenwg.test.TestCompanionServer

class CompanionUpdateClientTest {
    @Test fun `status uses protected path and strictly decodes status`() = runTest {
        TestCompanionServer(MockResponse().setBody(statusBody())).use { server ->
            val status = CompanionUpdateClient().status(server.endpoint("owner-token"))

            assertEquals("2.1.2", status.currentVersion)
            assertTrue(status.supported)
            server.takeRequest().also {
                assertEquals("GET", it.method)
                assertEquals("/v1/system/update", it.path)
                assertEquals("Bearer owner-token", it.getHeader("Authorization"))
            }
        }
    }

    @Test fun `install streams signed manifest prefix and archive then clears bytes`() = runTest {
        TestCompanionServer(MockResponse().setResponseCode(202).setBody("""{"schema_version":1,"target_version":"2.2.0"}""")).use { server ->
            val asset = asset()

            val accepted = CompanionUpdateClient().install(server.endpoint("owner-token"), asset)

            assertEquals("2.2.0", accepted.targetVersion)
            assertTrue(asset.bytes.all { it == 0.toByte() })
            server.takeRequest().also { request ->
                assertEquals("POST", request.method)
                assertEquals("/v1/system/update", request.path)
                assertEquals("application/vnd.apex-intellect.keenwg-update.v1", request.getHeader("Content-Type"))
                val bytes = request.body.readByteArray()
                val length = ByteBuffer.wrap(bytes, 0, 4).int
                val manifest = bytes.copyOfRange(4, 4 + length).toString(Charsets.UTF_8)
                assertTrue(manifest.contains("\"archive_sha256\""))
                assertFalse(manifest.contains("\"asset\""))
                assertTrue(bytes.copyOfRange(4 + length, bytes.size).contentEquals("official-archive".toByteArray()))
            }
        }
    }

    @Test fun `install maps failures without leaking response signature or archive`() = runTest {
        val secret = "official-archive signature-private-content"
        TestCompanionServer(MockResponse().setResponseCode(503).setBody(secret)).use { server ->
            val asset = asset()
            val failure = runCatching { CompanionUpdateClient().install(server.endpoint(), asset) }.exceptionOrNull()

            assertTrue(failure is CompanionUpdateException)
            assertEquals(CompanionUpdateError.UNAVAILABLE, (failure as CompanionUpdateException).code)
            assertFalse(failure.message.orEmpty().contains("signature-private"))
            assertTrue(asset.bytes.all { it == 0.toByte() })
        }
    }

    @Test fun `unknown fields and unsupported schema fail closed`() = runTest {
        for (body in listOf(
            statusBody().dropLast(1) + ",\"private_path\":\"hidden\"}",
            statusBody().replace("\"schema_version\":1", "\"schema_version\":2"),
        )) {
            TestCompanionServer(MockResponse().setBody(body)).use { server ->
                val failure = runCatching { CompanionUpdateClient().status(server.endpoint()) }.exceptionOrNull()
                assertTrue(failure is CompanionUpdateException)
                assertEquals(CompanionUpdateError.UNSUPPORTED, (failure as CompanionUpdateException).code)
                assertFalse(failure.message.orEmpty().contains("hidden"))
            }
        }
    }

    @Test fun `HTTP statuses have stable update errors`() = runTest {
        val cases = mapOf(401 to CompanionUpdateError.UNAUTHORIZED, 403 to CompanionUpdateError.FORBIDDEN,
            409 to CompanionUpdateError.BUSY, 413 to CompanionUpdateError.TOO_LARGE, 503 to CompanionUpdateError.UNAVAILABLE)
        for ((status, expected) in cases) {
            TestCompanionServer(MockResponse().setResponseCode(status).setBody("private response")).use { server ->
                val failure = runCatching { CompanionUpdateClient().status(server.endpoint()) }.exceptionOrNull() as CompanionUpdateException
                assertEquals(expected, failure.code)
            }
        }
    }

    private fun statusBody() = """{"schema_version":1,"current_version":"2.1.2","supported":true,"phase":"idle","result":"idle","target_version":"","error":""}"""

    private fun asset() = VerifiedCompanionAsset(
        CompanionAssetManifest(
            schemaVersion = 1, version = "2.2.0", architecture = "arm64",
            asset = "keenwg-companion-arm64.tgz", sha256 = "a".repeat(64),
            binarySha256 = "b".repeat(64), size = "official-archive".length,
            keyId = "release-test", signature = "A".repeat(86),
        ),
        "official-archive".toByteArray(),
    )
}
