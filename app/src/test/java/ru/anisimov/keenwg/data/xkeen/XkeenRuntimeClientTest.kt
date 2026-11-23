package ru.anisimov.keenwg.data.xkeen

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
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager

class XkeenRuntimeClientTest {
    @Test fun `GET and POST use pinned authenticated API and exact enabled body`() = runTest {
        fixture(response(true), response(false)).use { fixture ->
            val client = XkeenRuntimeClient()
            assertTrue(client.status(fixture.endpoint).running)
            assertFalse(client.setEnabled(fixture.endpoint, false).running)
            fixture.server.takeRequest().also {
                assertEquals("GET", it.method)
                assertEquals("/v1/xkeen/runtime", it.path)
                assertEquals("Bearer test-owner-token", it.getHeader("Authorization"))
            }
            fixture.server.takeRequest().also {
                assertEquals("POST", it.method)
                assertEquals("/v1/xkeen/runtime", it.path)
                assertEquals("Bearer test-owner-token", it.getHeader("Authorization"))
                assertTrue(it.getHeader("Content-Type")!!.startsWith("application/json"))
                assertEquals("""{"enabled":false}""", it.body.readUtf8())
            }
        }
    }

    @Test fun `capability distinguishes owner from read only without guessing scope`() = runTest {
        fixture(capability("write"), capability("read")).use {
            val client = XkeenRuntimeClient()
            assertEquals(CapabilityAccess.WRITE, client.access(it.endpoint))
            assertEquals(CapabilityAccess.READ, client.access(it.endpoint))
            assertEquals("/v1/capabilities", it.server.takeRequest().path)
        }
    }

    @Test fun `missing runtime capability and old 404 are unsupported`() = runTest {
        fixture(MockResponse().setBody("""{"schema_version":1,"capabilities":[]}"""), MockResponse().setResponseCode(404)).use {
            val client = XkeenRuntimeClient()
            assertEquals(XkeenRuntimeError.UNSUPPORTED, failure { client.access(it.endpoint) })
            assertEquals(XkeenRuntimeError.UNSUPPORTED, failure { client.status(it.endpoint) })
        }
    }

    @Test fun `missing running unsupported schema and persistent semantics fail closed`() = runTest {
        val invalid = listOf(
            """{"schema_version":1,"runtime_only":true}""",
            """{"running":false,"runtime_only":true}""",
            """{"schema_version":2,"running":false,"runtime_only":true}""",
            """{"schema_version":1,"running":false,"runtime_only":false}""",
            """{"schema_version":1,"running":null,"runtime_only":true}""",
        )
        fixture(*invalid.map { MockResponse().setBody(it) }.toTypedArray()).use {
            invalid.forEach { _ ->
                assertEquals(XkeenRuntimeError.INVALID_RESPONSE, failure { XkeenRuntimeClient().status(it.endpoint) })
            }
        }
    }

    @Test fun `errors have typed safe codes and never echo server content`() = runTest {
        val codes = listOf(401 to XkeenRuntimeError.UNAUTHORIZED, 403 to XkeenRuntimeError.FORBIDDEN,
            409 to XkeenRuntimeError.BUSY, 503 to XkeenRuntimeError.UNAVAILABLE)
        fixture(*codes.map { MockResponse().setResponseCode(it.first).setBody("private-server-content") }.toTypedArray()).use {
            codes.forEach { (_, expected) ->
                assertEquals(expected, failure { XkeenRuntimeClient().setEnabled(it.endpoint, true) })
            }
        }
    }

    @Test fun `wrong certificate pin prevents request`() = runTest {
        fixture(response(true)).use {
            val otherCertificate = HeldCertificate.Builder().commonName("other").build()
            val endpoint = it.endpoint.copy(certificatePin = ExactPinTrustManager.pin(otherCertificate.certificate))
            assertEquals(XkeenRuntimeError.UNAVAILABLE, failure { XkeenRuntimeClient().status(endpoint) })
            assertEquals(0, it.server.requestCount)
        }
    }

    @Test fun `backend verification failure has its own non transport error code`() = runTest {
        fixture(MockResponse().setResponseCode(503).setBody("""{"error":"runtime_verification_failed"}""")).use {
            assertEquals(XkeenRuntimeError.VERIFICATION_FAILED, failure {
                XkeenRuntimeClient().setEnabled(it.endpoint, true)
            })
        }
    }

    @Test fun `explicit server startup failure survives matching process state readback`() = runTest {
        fixture(
            MockResponse().setResponseCode(503).setBody("""{"error":"runtime_start_failed"}"""),
            response(true),
        ).use {
            val result = XkeenRuntimeRepository(XkeenRuntimeClient()).setEnabled(it.endpoint, true)
            assertEquals(true, result.running)
            assertEquals(XkeenRuntimeError.UNAVAILABLE, result.error)
            assertEquals("POST", it.server.takeRequest().method)
            assertEquals("GET", it.server.takeRequest().method)
        }
    }

    private suspend fun failure(block: suspend () -> Any): XkeenRuntimeError = try {
        block()
        error("Expected failure")
    } catch (failure: XkeenRuntimeException) {
        assertFalse(failure.message.orEmpty().contains("private-server-content"))
        failure.code
    }

    private fun response(running: Boolean) = MockResponse().setBody("""{"schema_version":1,"running":$running,"runtime_only":true}""")
    private fun capability(access: String) = MockResponse().setBody("""{"schema_version":1,"capabilities":[{"schema_version":1,"id":"xkeen.runtime","access":"$access","available":true,"transport":"companion"}]}""")
    private fun fixture(vararg responses: MockResponse): Fixture {
        val server = MockWebServer()
        val held = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        responses.forEach(server::enqueue)
        server.start(InetAddress.getLoopbackAddress(), 0)
        return Fixture(server, CompanionEndpoint(server.url("/"), ExactPinTrustManager.pin(held.certificate), "test-owner-token"))
    }

    private data class Fixture(val server: MockWebServer, val endpoint: CompanionEndpoint) : AutoCloseable {
        override fun close() = server.shutdown()
    }
}
