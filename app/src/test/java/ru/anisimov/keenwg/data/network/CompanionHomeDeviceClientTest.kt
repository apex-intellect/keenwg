package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.test.TestCompanionServer

class CompanionHomeDeviceClientTest {
    @Test fun loadReviewAndApplyUsePinnedAuthenticatedDevicePaths() = runTest {
        val server = TestCompanionServer(
            MockResponse().setBody(documentJson()),
            MockResponse().setBody("""{"schema_version":1,"plan_id":"plan-1","expires_at":"2026-08-12T12:00:00Z","state_version":"home-v1","mac":"02:00:00:00:00:01","before_ip":"192.168.1.10","after_ip":"192.168.1.20"}"""),
            MockResponse().setBody("""{"schema_version":1,"status":"committed","home":""" + documentJson("home-v2") + "}"),
        )
        val client = CompanionHomeDeviceClient(keyFactory = { "11111111-1111-4111-8111-111111111111" })
        val endpoint = server.endpoint("control-secret")

        val loaded = client.load(endpoint)
        assertEquals("Phone", loaded.devices.single().name)
        val plan = client.review(endpoint, loaded.stateVersion, loaded.devices.single().id, "192.168.1.20")
        val result = client.apply(endpoint, loaded.stateVersion, loaded.devices.single().id, "192.168.1.20", plan.planId)
        assertEquals("committed", result.status)

        assertEquals("/v1/network/devices", server.takeRequest().path)
        val review = server.takeRequest()
        assertEquals("Bearer control-secret", review.getHeader("Authorization"))
        assertEquals("/v1/network/devices/mac-1234567890abcdef/reservation/review", review.path)
        assertEquals("""{"schema_version":1,"state_version":"home-v1","reserved_ip":"192.168.1.20"}""", review.body.readUtf8())
        val apply = server.takeRequest()
        assertEquals("/v1/network/devices/mac-1234567890abcdef/reservation/apply", apply.path)
        assertEquals(true, apply.body.readUtf8().contains("\"idempotency_key\":\"11111111-1111-4111-8111-111111111111\""))
        server.close()
    }

    @Test fun unknownFieldsAndOversizedResponsesFailWithoutLeakingBody() = runTest {
        val secret = "private-router-secret"
        val server = TestCompanionServer(
            MockResponse().setBody(documentJson().dropLast(1) + ",\"password\":\"" + secret + "\"}"),
            MockResponse().setBody("x".repeat(1_048_577)),
        )
        val client = CompanionHomeDeviceClient()
        repeat(2) {
            val failure = failure { client.load(server.endpoint()) }
            assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure.code)
            assertFalse(failure.message.orEmpty().contains(secret))
        }
        server.close()
    }

    @Test fun outOfRangeIpv4IsRejectedByTheStrictSchema() = runTest {
        val server = TestCompanionServer(
            MockResponse().setBody(documentJson().replace("192.168.1.10", "999.168.1.10")),
        )

        val failure = failure { CompanionHomeDeviceClient().load(server.endpoint()) }

        assertEquals(XkeenErrorCode.UNSUPPORTED_SCHEMA, failure.code)
        server.close()
    }

    private fun documentJson(state: String = "home-v1") =
        """{"schema_version":1,"state_version":"$state","devices":[{"id":"mac-1234567890abcdef","mac":"02:00:00:00:00:01","name":"Phone","hostname":"phone.local","ip":"192.168.1.10","reserved_ip":"192.168.1.10","online":true,"static_reservation":true,"interface_name":"Home","rssi":-54}]}"""

    private suspend fun failure(block: suspend () -> Unit): XkeenException = try {
        block()
        error("Expected XkeenException")
    } catch (failure: XkeenException) {
        failure
    }
}
