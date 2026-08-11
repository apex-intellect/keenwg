package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.test.TestCompanionServer

class DomainRoutingClientTest {
    @Test fun `load and mutations use strict authenticated paths`() = runTest {
        val server = server(
            MockResponse().setBody(statusJson()),
            MockResponse().setBody(resultJson("committed")),
            MockResponse().setBody(resultJson("committed")),
            MockResponse().setBody(resultJson("committed")),
        )
        val client = DomainRoutingClient(keyFactory = { "operation-key-01" })
        val endpoint = server.endpoint("control-secret")
        val loaded = client.load(endpoint)
        assertEquals("okko.sport", loaded.rules.single().value)
        val draft = DomainRuleDraft("domain", "example.com", "vpn", "Example", true)
        client.create(endpoint, loaded, draft)
        client.update(endpoint, loaded, "rule-a", draft)
        client.delete(endpoint, loaded, "rule-a")

        assertEquals("/v1/network/domains", server.takeRequest().path)
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("Bearer control-secret", create.getHeader("Authorization"))
        assertEquals("/v1/network/domains/rules", create.path)
        assertEquals(true, create.body.readUtf8().contains("\"idempotency_key\":\"operation-key-01\""))
        assertEquals("PUT", server.takeRequest().method)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/network/domains/rules/rule-a", delete.path)
        server.close()
    }

    @Test fun `rejected and uncertain mutation bodies remain actionable`() = runTest {
        val server = server(
            MockResponse().setResponseCode(409).setBody(resultJson("rejected")),
            MockResponse().setResponseCode(503).setBody(resultJson("uncertain")),
        )
        val client = DomainRoutingClient(keyFactory = { "operation-key-02" })
        val status = DomainRoutingStatus(1, 11u, emptyList(), emptyList(), emptyList())
        val draft = DomainRuleDraft("domain", "example.com", "direct", "", true)
        val endpoint = server.endpoint("control-secret")
        assertEquals("rejected", client.create(endpoint, status, draft).result)
        assertEquals("uncertain", client.create(endpoint, status, draft).result)
        server.close()
    }

    @Test fun `unknown fields and oversized responses fail without leaking body`() = runTest {
        val server = server(
            MockResponse().setBody(statusJson().dropLast(1) + ",\"uuid\":\"private-secret\"}"),
            MockResponse().setBody("x".repeat(262_145)),
        )
        val client = DomainRoutingClient()
        val endpoint = server.endpoint("control-secret")
        repeat(2) {
            val failure = failure { client.load(endpoint) }
            assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure.code)
            assertFalse(failure.message.orEmpty().contains("private-secret"))
        }
        server.close()
    }

    private fun server(vararg responses: MockResponse) = TestCompanionServer(*responses)

    private fun statusJson() = """{"schema_version":1,"state_version":11,"rules":[{"id":"rule-a","kind":"domain","value":"okko.sport","effect":"direct","label":"Okko","enabled":true,"source":"manual","protected":false}],"presets":[{"id":"category-gov-ru","label":"Госсайты РФ","matcher":"ext:geosite_v2fly.dat:category-gov-ru","available":true,"enabled":true}],"warnings":[]}"""
    private fun resultJson(result: String) = """{"result":"$result","status":${statusJson()}}"""

    private suspend fun failure(block: suspend () -> Unit): XkeenException = try {
        block(); error("Expected XkeenException")
    } catch (failure: XkeenException) { failure }
}
