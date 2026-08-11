package ru.anisimov.keenwg.data.xkeen

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.test.TestCompanionServer

class XkeenClientTest {
    @Test fun `refresh uses bearer and exact idempotency body`() = runTest {
        val server = server(MockResponse().setResponseCode(202).setBody(operationJson("queued")))
        val endpoint = server.endpoint("control-secret")

        client().refresh(endpoint, 7, KEY)

        val request = server.takeRequest()
        assertEquals("/v1/xkeen/subscription/refresh", request.path)
        assertEquals("Bearer control-secret", request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertEquals("""{"state_version":7,"idempotency_key":"$KEY"}""", request.body.readUtf8())
        server.close()
    }

    @Test fun `unknown secret-bearing status fields fail closed`() = runTest {
        val body = validStatusJson().dropLast(1) + ",\"uuid\":\"secret\"}"
        val server = server(MockResponse().setBody(body))

        val failure = failure { client().status(server.endpoint()) }

        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure.code)
        assertFalse(failure.message.orEmpty().contains("secret"))
        server.close()
    }

    @Test fun `status and probe use authenticated status endpoint`() = runTest {
        val server = server(MockResponse().setBody(validStatusJson()), MockResponse().setBody(validStatusJson()))
        val endpoint = server.endpoint("control-secret")
        val client = client()

        assertEquals(7, client.status(endpoint).stateVersion)
        assertEquals("0.4.0", client.probe(endpoint).version)

        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/v1/xkeen/status", request.path)
            assertEquals("Bearer control-secret", request.getHeader("Authorization"))
        }
        server.close()
    }

    @Test fun `select encodes one node segment and operation polls same key`() = runTest {
        val server = server(
            MockResponse().setResponseCode(202).setBody(operationJson("running")),
            MockResponse().setBody(operationJson("terminal", "success")),
        )
        val endpoint = server.endpoint()
        val nodeId = "aabbccddeeff00112233445566778899"

        client().select(endpoint, nodeId, 7, KEY)
        client().operation(endpoint, KEY)

        assertEquals("/v1/xkeen/nodes/$nodeId/select", server.takeRequest().path)
        assertEquals("/v1/xkeen/operations/$KEY", server.takeRequest().path)
        server.close()
    }

    @Test fun `http errors map to fixed codes`() = runTest {
        val server = server(
            MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(409),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(404),
        )
        val endpoint = server.endpoint()
        val client = client()

        assertEquals(XkeenErrorCode.UNAUTHORIZED, failure { client.status(endpoint) }.code)
        assertEquals(XkeenErrorCode.STALE_STATE, failure { client.status(endpoint) }.code)
        assertEquals(XkeenErrorCode.BUSY, failure { client.status(endpoint) }.code)
        assertEquals(XkeenErrorCode.NOT_FOUND, failure { client.status(endpoint) }.code)
        server.close()
    }

    @Test fun `oversize status and operation fail closed`() = runTest {
        val server = server(
            MockResponse().setBody("x".repeat(1_048_577)),
            MockResponse().setBody("x".repeat(4_097)),
        )
        val endpoint = server.endpoint()
        val client = client()

        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure { client.status(endpoint) }.code)
        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure { client.operation(endpoint, KEY) }.code)
        server.close()
    }

    @Test fun `invalid local ids make zero requests`() = runTest {
        val server = server()

        assertEquals(XkeenErrorCode.INVALID_SETTINGS, failure { client().select(server.endpoint(), "../secret", 7, KEY) }.code)
        assertEquals(0, server.requestCount)
        server.close()
    }

    @Test fun `diagnostics are explicit authenticated post and preserve result order`() = runTest {
        val server = server(MockResponse().setBody("""{
          "schema_version":1,"checked_at":100,"results":[
            {"node_id":"aabbccddeeff00112233445566778899","host":"nl.example.test","port":443,"resolved_ip":"192.0.2.1","dns_ms":4,"connect_ms":17,"status":"reachable"},
            {"node_id":"bbccddeeff00112233445566778899aa","host":"de.example.test","port":443,"dns_ms":9,"connect_ms":0,"status":"dns_error"}
          ]
        }"""))

        val report = client().diagnostics(server.endpoint("control-secret"))

        assertEquals(listOf(XkeenDiagnosticStatus.REACHABLE, XkeenDiagnosticStatus.DNS_ERROR), report.results.map { it.status })
        val request = server.takeRequest()
        assertEquals("/v1/diagnostics/nodes", request.path)
        assertEquals("POST", request.method)
        assertEquals("{}", request.body.readUtf8())
        assertEquals("Bearer control-secret", request.getHeader("Authorization"))
        server.close()
    }

    private fun client() = XkeenClient()

    private fun server(vararg responses: MockResponse) = TestCompanionServer(*responses)

    private fun validStatusJson() = """{
      "version":"0.4.0","state_version":7,
      "active":null,
      "subscription":{"refreshed_at":100,"stale":false,"nodes":[{
        "id":"aabbccddeeff00112233445566778899","display_name":"🇳🇱 Нидерланды 1","country":"Нидерланды","flag":"🇳🇱",
        "host":"nl.example.test","port":443,"fingerprint":"firefox","transport":"tcp","security":"reality","flow":"xtls-rprx-vision","active":false,"warnings":[]
      }]},"operation":null
    }"""

    private fun operationJson(state: String, result: String? = null): String {
        val terminal = if (result == null) "" else ",\"result\":\"$result\",\"finished_at\":101"
        return """{"idempotency_key":"$KEY","kind":"refresh","state":"$state"$terminal,"started_at":100}"""
    }

    private suspend fun failure(block: suspend () -> Unit): XkeenException =
        try {
            block()
            error("Expected XkeenException")
        } catch (failure: XkeenException) {
            failure
        }

    private companion object {
        const val KEY = "11111111-1111-4111-8111-111111111111"
    }
}
