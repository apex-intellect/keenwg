package ru.anisimov.keenwg.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerRoutesTest {
    @Test fun `connections route is parameter free and serializable`() {
        val encoded = Json.encodeToString(ConnectionsRoute.serializer(), ConnectionsRoute)

        assertEquals("{}", encoded)
        assertEquals(ConnectionsRoute, Json.decodeFromString(ConnectionsRoute.serializer(), encoded))
    }

    @Test fun `peer detail route preserves canonical base64 characters`() {
        val key = "+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/8="

        val encoded = Json.encodeToString(PeerDetailRoute.serializer(), PeerDetailRoute(key))

        assertEquals(key, Json.decodeFromString(PeerDetailRoute.serializer(), encoded).publicKey)
    }
}
