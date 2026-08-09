package ru.anisimov.keenwg.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.LocalAddressProvider
import ru.anisimov.keenwg.domain.RouterAddressProvider
import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings

class SelfPeerGuardTest {
    @Test fun `guard rechecks current route on every destructive tap`() = runTest {
        var host = "10.8.0.1"
        var addresses = setOf("10.8.0.3")
        val guard = SelfPeerGuard(
            localAddresses = LocalAddressProvider { addresses },
            routerAddresses = RouterAddressProvider { host -> setOf(host) },
            currentSettings = { ServerSettings(host = host, subnetBase = "10.8.0.") },
        )

        assertTrue(guard.blocks(peer("10.8.0.3")))
        host = "192.168.1.1"
        assertFalse(guard.blocks(peer("10.8.0.3")))
        host = "10.8.0.1"
        addresses = setOf("10.8.0.4")
        assertFalse(guard.blocks(peer("10.8.0.3")))
    }

    @Test fun `hostname resolving to wireguard address is blocked`() = runTest {
        val guard = SelfPeerGuard(
            localAddresses = LocalAddressProvider { setOf("10.8.0.3") },
            routerAddresses = RouterAddressProvider { setOf("10.8.0.1") },
            currentSettings = { ServerSettings(host = "keenetic.local", subnetBase = "10.8.0.") },
        )

        assertTrue(guard.blocks(peer("10.8.0.3")))
    }

    @Test fun `hostname resolving only to lan address can manage local peer`() = runTest {
        val guard = SelfPeerGuard(
            localAddresses = LocalAddressProvider { setOf("10.8.0.3") },
            routerAddresses = RouterAddressProvider { setOf("192.168.1.1") },
            currentSettings = { ServerSettings(host = "keenetic.local", subnetBase = "10.8.0.") },
        )

        assertFalse(guard.blocks(peer("10.8.0.3")))
    }

    @Test fun `unresolved hostname fails closed for local wireguard peer`() = runTest {
        val guard = SelfPeerGuard(
            localAddresses = LocalAddressProvider { setOf("10.8.0.3") },
            routerAddresses = RouterAddressProvider { null },
            currentSettings = { ServerSettings(host = "keenetic.local", subnetBase = "10.8.0.") },
        )

        assertTrue(guard.blocks(peer("10.8.0.3")))
    }

    @Test fun `list snapshot reads settings and addresses only once`() = runTest {
        var settingReads = 0
        var addressReads = 0
        val guard = SelfPeerGuard(
            localAddresses = LocalAddressProvider { addressReads++; setOf("10.8.0.3") },
            routerAddresses = RouterAddressProvider { setOf("10.8.0.1") },
            currentSettings = {
                settingReads++
                ServerSettings(host = "10.8.0.1", subnetBase = "10.8.0.")
            },
        )

        val keys = guard.unsafeKeys(listOf(peer("10.8.0.3"), peer("10.8.0.4")))

        assertTrue("key-10.8.0.3" in keys)
        assertFalse("key-10.8.0.4" in keys)
        org.junit.Assert.assertEquals(1, settingReads)
        org.junit.Assert.assertEquals(1, addressReads)
    }

    private fun peer(ip: String) = Peer(
        publicKey = "key-$ip",
        name = "phone",
        ip = ip,
        online = true,
        handshake = HandshakeStatus(HandshakeKind.JUST_NOW, 0),
        clientUploadBytes = 0,
        clientDownloadBytes = 0,
        enabled = true,
    )
}
