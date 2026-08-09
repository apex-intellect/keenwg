package ru.anisimov.keenwg.data.collector

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings

class CollectorClientTest {
    @Test fun `history request uses bearer peer id and bounded query contract`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(historyJson(upload = 101, download = 202)))
        server.start()
        val settings = ServerSettings(collectorUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"), collectorToken = "token-secret")
        val peerId = "a".repeat(64)

        val history = testClient().history(settings, peerId, HistoryRange(100, 200, "5m", 288))
        val request = server.takeRequest()

        assertEquals("Bearer token-secret", request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertEquals("/v1/peers/$peerId/history?from=100&to=200&resolution=5m&limit=288", request.path)
        assertEquals(101L, history.clientUploadBytes)
        assertEquals(202L, history.clientDownloadBytes)
        assertFalse(request.path!!.contains("+/="))
        server.shutdown()
    }

    @Test fun `collector maps authorization not found and oversize distinctly`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
        server.start()
        val settings = ServerSettings(collectorUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"), collectorToken = "bad")
        val client = testClient()
        val range = HistoryRange(100, 200)

        assertTrue(expectFailure { client.history(settings, "b".repeat(64), range) }.message!!.contains("отклонил токен"))
        assertTrue(client.history(settings, "b".repeat(64), range).points.isEmpty())
        assertTrue(expectFailure { client.history(settings, "b".repeat(64), range) }.message!!.contains("слишком большой"))
        server.shutdown()
    }

    @Test fun `blank collector token fails before any network request`() = runTest {
        val server = MockWebServer()
        server.start()
        val settings = ServerSettings(
            collectorUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"),
            collectorToken = "",
        )

        val failure = expectFailure {
            testClient().history(settings, "c".repeat(64), HistoryRange(100, 200))
        }

        assertTrue(failure.message!!.contains("токен", ignoreCase = true))
        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test fun `probe authenticates against meta instead of public health`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"0.3.0","max_points":2000}"""))
        server.start()
        val settings = ServerSettings(
            collectorUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"),
            collectorToken = "accepted-token",
        )

        val meta = testClient().probe(settings)
        val request = server.takeRequest()

        assertEquals("0.3.0", meta.version)
        assertEquals("/v1/meta", request.path)
        assertEquals("Bearer accepted-token", request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        server.shutdown()
    }

    private fun testClient() = CollectorClient(urlValidator = { null })

    private fun historyJson(upload: Long, download: Long) = """{
      "peer_id":"${"a".repeat(64)}","from":100,"to":200,"resolution":"5m",
      "observed_seconds":60,"online_seconds":30,"last_online_at":190,
      "client_upload_bytes":$upload,"client_download_bytes":$download,
      "counter_resets":1,"coverage_ratio":0.6,
      "points":[{"at":100,"observed_seconds":60,"online_seconds":30,"client_upload_bytes":$upload,"client_download_bytes":$download}]
    }"""
}

private suspend fun expectFailure(block: suspend () -> Unit): Throwable =
    try { block(); error("Expected failure") } catch (failure: Throwable) { failure }
