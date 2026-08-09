package ru.anisimov.keenwg.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfPeerDetectorTest {
    @Test fun `active tunnel peer is unsafe when router is reached through wg subnet`() {
        assertTrue(
            SelfPeerDetector.isUnsafe(
                peerIp = "10.8.0.3",
                routerAddresses = setOf("10.8.0.1"),
                subnetBase = "10.8.0.",
                localAddresses = setOf("10.8.0.3"),
            ),
        )
    }

    @Test fun `same peer can be managed through explicit lan router host`() {
        assertFalse(
            SelfPeerDetector.isUnsafe(
                peerIp = "10.8.0.3",
                routerAddresses = setOf("192.168.1.1"),
                subnetBase = "10.8.0.",
                localAddresses = setOf("10.8.0.3"),
            ),
        )
    }

    @Test fun `another peer is not marked self`() {
        assertFalse(
            SelfPeerDetector.isUnsafe(
                peerIp = "10.8.0.4",
                routerAddresses = setOf("10.8.0.1"),
                subnetBase = "10.8.0.",
                localAddresses = setOf("10.8.0.3"),
            ),
        )
    }

    @Test fun `unresolved router fails closed for a local wireguard peer`() {
        assertTrue(SelfPeerDetector.isUnsafe("10.8.0.3", null, "10.8.0.", setOf("10.8.0.3")))
        assertTrue(SelfPeerDetector.isUnsafe("10.8.0.3", emptySet(), "10.8.0.", setOf("10.8.0.3")))
    }

    @Test fun `invalid peer or subnet never becomes a destructive false positive`() {
        assertFalse(SelfPeerDetector.isUnsafe(null, setOf("10.8.0.1"), "10.8.0.", setOf("10.8.0.3")))
        assertFalse(SelfPeerDetector.isUnsafe("10.8.0.3", setOf("10.8.0.1"), "bad", setOf("10.8.0.3")))
    }
}
