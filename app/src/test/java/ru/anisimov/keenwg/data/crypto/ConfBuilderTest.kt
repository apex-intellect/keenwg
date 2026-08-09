package ru.anisimov.keenwg.data.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class ConfBuilderTest {
    @Test fun builds_conf() {
        val c = ConfBuilder.build("PRIV", "10.8.0.7/32", "192.168.1.1", 1380, "SRVPUB", "1.2.3.4:51820", 25)
        assertTrue(c.contains("PrivateKey = PRIV"))
        assertTrue(c.contains("Address = 10.8.0.7/32"))
        assertTrue(c.contains("PublicKey = SRVPUB"))
        assertTrue(c.contains("Endpoint = 1.2.3.4:51820"))
        assertTrue(c.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(c.contains("PersistentKeepalive = 25"))
    }

    @Test fun `policy controls allowed networks and dns without changing router peer address`() {
        val conf = ConfBuilder.build(
            privateKey = "private",
            address = "10.8.0.7/32",
            dnsServers = listOf("192.168.1.1", "1.1.1.1"),
            mtu = 1380,
            serverPublicKey = "server",
            endpoint = "vpn.example.net:51820",
            keepalive = 25,
            allowedNetworks = listOf("10.0.0.0/8", "192.168.0.0/16"),
        )
        assertTrue(conf.contains("DNS = 192.168.1.1, 1.1.1.1"))
        assertTrue(conf.contains("AllowedIPs = 10.0.0.0/8, 192.168.0.0/16"))
        assertTrue(conf.contains("Address = 10.8.0.7/32"))
    }
}
