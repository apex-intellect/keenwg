package ru.anisimov.keenwg.data.wireguard

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.test.TestCompanionServer

class CompanionWireGuardClientTest {
    @Test fun transientInventoryFailureIsRetriedOnce() = runTest {
        val server = TestCompanionServer(
            MockResponse().setResponseCode(503).setBody("""{"error":"router_unavailable"}"""),
            MockResponse().setBody(documentJson()),
        )

        val loaded = CompanionWireGuardClient().load(server.endpoint())

        assertEquals("wg-v1", loaded.stateVersion)
        assertEquals(2, server.requestCount)
        server.close()
    }

    @Test fun inventoryAndMutationsPreserveExactProtectedContract() = runTest {
        val server = TestCompanionServer(
            MockResponse().setBody(documentJson()),
            MockResponse().setBody("""{"schema_version":1,"plan_id":"peer-plan","expires_at":"2026-08-12T12:00:00Z","request":{"state_version":"wg-v1","interface_id":"Wireguard0","action":"set_enabled","public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","enabled":false},"before":""" + peerJson(true) + ""","after":""" + peerJson(false) + "}"),
            MockResponse().setBody("""{"schema_version":1,"status":"committed","wireguard":""" + documentJson("wg-v2", false) + "}"),
        )
        val client = CompanionWireGuardClient(keyFactory = { "22222222-2222-4222-8222-222222222222" })
        val endpoint = server.endpoint("control-secret")

        val loaded = client.load(endpoint)
        assertEquals(1, loaded.interfaces.single().peers.size)
        val request = CompanionPeerMutation(
            stateVersion = loaded.stateVersion,
            interfaceId = "Wireguard0",
            action = "set_enabled",
            publicKey = loaded.interfaces.single().peers.single().publicKey,
            enabled = false,
        )
        val plan = client.review(endpoint, request)
        val result = client.apply(endpoint, request, plan.planId)
        assertEquals("committed", result.status)

        assertEquals("/v1/access/wireguard", server.takeRequest().path)
        val review = server.takeRequest()
        assertEquals("Bearer control-secret", review.getHeader("Authorization"))
        assertEquals("/v1/access/wireguard/peers/review", review.path)
        assertFalse(review.body.readUtf8().contains("private"))
        val apply = server.takeRequest()
        assertEquals("/v1/access/wireguard/peers/apply", apply.path)
        assertEquals(true, apply.body.readUtf8().contains("\"plan_id\":\"peer-plan\""))
        server.close()
    }

    @Test fun duplicatePeerIdentitiesAndAuthorizationFailuresAreSanitized() = runTest {
        val duplicate = documentJson().replace("]}]}", "," + peerJson(true) + "]}]}")
        val server = TestCompanionServer(
            MockResponse().setBody(duplicate),
            MockResponse().setResponseCode(403).setBody("router-private-response"),
        )
        val client = CompanionWireGuardClient()
        val first = failure { client.load(server.endpoint()) }
        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, first.code)
        val second = failure { client.load(server.endpoint()) }
        assertEquals(XkeenErrorCode.UNAUTHORIZED, second.code)
        assertFalse(second.message.orEmpty().contains("router-private-response"))
        server.close()
    }

    @Test fun outOfRangeIpv4IsRejectedByTheStrictSchema() = runTest {
        val server = TestCompanionServer(
            MockResponse().setBody(documentJson().replace("10.8.0.2", "999.8.0.2")),
        )

        val failure = failure { CompanionWireGuardClient().load(server.endpoint()) }

        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure.code)
        server.close()
    }

    private fun peerJson(enabled: Boolean) =
        """{"public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","name":"phone","allowed_ip":"10.8.0.2","keepalive":25,"enabled":$enabled,"online":true,"rx_bytes":12,"tx_bytes":34}"""

    private fun documentJson(state: String = "wg-v1", enabled: Boolean = true) =
        """{"schema_version":1,"state_version":"$state","interfaces":[{"id":"Wireguard0","public_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=","addresses":["10.8.0.1/24"],"listen_port":51820,"mtu":1420,"peers":[""" + peerJson(enabled) + "]}]}"

    private suspend fun failure(block: suspend () -> Unit): XkeenException = try {
        block()
        error("Expected XkeenException")
    } catch (failure: XkeenException) {
        failure
    }
}
