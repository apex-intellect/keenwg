package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.test.TestCompanionServer

class NetworkExclusionClientTest {
    @Test fun `load and add use authenticated versioned controller contract`() = runTest {
        val largeStateVersion = 17_909_411_532_848_731_629uL
        val server = TestCompanionServer(
            MockResponse().setBody("""{"schema_version":1,"state_version":17909411532848731629,"entries":[{"id":"a","value":"203.0.113.10/32","protected":true}],"warnings":[]}"""),
            MockResponse().setBody("""{"result":"committed","status":{"schema_version":1,"state_version":17909411532848731630,"entries":[],"warnings":[]}}"""),
        )
        val endpoint = server.endpoint("control-secret")
        val client = NetworkExclusionClient()

        val loaded = client.load(endpoint)
        assertEquals(true, loaded.entries.single().isProtected)
        assertEquals(largeStateVersion, loaded.stateVersion)
        client.mutate(endpoint, largeStateVersion, "add", "198.18.0.0/15")

        assertEquals("/v1/network/exclusions", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("Bearer control-secret", post.getHeader("Authorization"))
        assertEquals("""{"state_version":17909411532848731629,"action":"add","value":"198.18.0.0/15"}""", post.body.readUtf8())
        server.close()
    }
}
