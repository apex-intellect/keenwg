package ru.anisimov.keenwg.data.collector

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.anisimov.keenwg.test.TestCompanionServer

class CompanionHistoryClientTest {
    @Test fun `history uses the protected Companion endpoint and exact query contract`() = runTest {
        val server = TestCompanionServer(MockResponse().setBody(historyDocument()))
        val client = CompanionHistoryClient(endpointProvider = { server.endpoint("device-secret") })

        val result = client.history(TEST_PEER_ID, HistoryRange(100, 200, "raw", 100))

        assertEquals(TEST_PEER_ID, result.peerId)
        assertEquals(1, result.points.size)
        val request = server.takeRequest()
        assertEquals("/v1/access/wireguard/history/query", request.path)
        assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertEquals(
            """{"schema_version":1,"peer_id":"$TEST_PEER_ID","from":100,"to":200,"resolution":"raw","limit":100}""",
            body,
        )
        assertFalse(body.contains("token", ignoreCase = true))
        server.close()
    }

    @Test fun `old Companion and unavailable history have distinct safe failures`() = runTest {
        val server = TestCompanionServer(
            MockResponse().setResponseCode(404).setBody("router-private-response"),
            MockResponse().setResponseCode(503).setBody("token=collector-secret"),
        )
        val client = CompanionHistoryClient(endpointProvider = { server.endpoint() })

        val outdated = failure { client.history(TEST_PEER_ID, HistoryRange(100, 200, "raw")) }
        val unavailable = failure { client.history(TEST_PEER_ID, HistoryRange(100, 200, "raw")) }

        assertEquals(HistoryFailure.UPDATE_COMPONENT, outdated.reason)
        assertEquals(HistoryFailure.UNAVAILABLE, unavailable.reason)
        assertFalse(outdated.message.orEmpty().contains("router-private-response"))
        assertFalse(unavailable.message.orEmpty().contains("collector-secret"))
        server.close()
    }

    @Test fun `missing protected access and unsupported response are explicit`() = runTest {
        val missing = CompanionHistoryClient(endpointProvider = { null })
        assertEquals(
            HistoryFailure.PROTECTED_ACCESS_REQUIRED,
            failure { missing.history(TEST_PEER_ID, HistoryRange(100, 200, "raw")) }.reason,
        )

        val server = TestCompanionServer(
            MockResponse().setBody(historyDocument().replace(TEST_PEER_ID, "a".repeat(64))),
        )
        val malformed = CompanionHistoryClient(endpointProvider = { server.endpoint() })
        assertEquals(
            HistoryFailure.UNSUPPORTED_RESPONSE,
            failure { malformed.history(TEST_PEER_ID, HistoryRange(100, 200, "raw")) }.reason,
        )
        server.close()
    }

    @Test fun `hour history accepts the Collector aligned window`() = runTest {
        val response = historyDocument()
            .replace("\"from\":100,\"to\":200,\"resolution\":\"raw\"", "\"from\":0,\"to\":7200,\"resolution\":\"1h\"")
            .replace("\"at\":100", "\"at\":0")
            .replace("\"last_online_at\":150", "\"last_online_at\":3600")
        val server = TestCompanionServer(MockResponse().setBody(response))
        val client = CompanionHistoryClient(endpointProvider = { server.endpoint() })

        val history = client.history(TEST_PEER_ID, HistoryRange(101, 3700, "1h", 100))

        assertEquals(0L, history.from)
        assertEquals(7200L, history.to)
        server.close()
    }

    @Test fun `bucketed history rejects a range that cannot be aligned safely`() = runTest {
        val client = CompanionHistoryClient(endpointProvider = { error("endpoint must not be read") })

        val failure = failure {
            client.history(TEST_PEER_ID, HistoryRange(Long.MAX_VALUE - 1, Long.MAX_VALUE, "1h", 1))
        }

        assertEquals(HistoryFailure.UNSUPPORTED_RESPONSE, failure.reason)
    }

    private suspend fun failure(block: suspend () -> Unit): HistoryException = try {
        block()
        error("Expected HistoryException")
    } catch (failure: HistoryException) {
        failure
    }

    private fun historyDocument() = """{
      "schema_version":1,
      "history":{
        "peer_id":"$TEST_PEER_ID","from":100,"to":200,"resolution":"raw",
        "observed_seconds":100,"online_seconds":60,"last_online_at":150,
        "client_upload_bytes":12,"client_download_bytes":34,"counter_resets":0,"coverage_ratio":1.0,
        "points":[{"at":100,"observed_seconds":100,"online_seconds":60,"client_upload_bytes":12,"client_download_bytes":34}]
      }
    }"""

    private companion object {
        const val TEST_PEER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
