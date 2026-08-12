package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

class AdaptiveNetworkRepositoryTest {
    @Test fun pairedProfileUsesCompanionForLoadAndReservationWithoutRci() = runTest {
        val companion = FakeCompanionHome()
        val legacy = FakeLegacyNetwork()
        val repository = AdaptiveNetworkRepository(flowOf(activeProfile(paired = true)), companion, legacy)

        val loaded = repository.load(ServerSettings(password = ""))
        repository.setStaticReservation(ServerSettings(password = ""), loaded.single().mac, "192.168.1.20")

        assertEquals("Phone", loaded.single().name)
        assertEquals(2, companion.loads)
        assertEquals(1, companion.applies)
        assertEquals(0, legacy.loads)
    }

    @Test fun companionFailureNeverFallsBackToRci() = runTest {
        val companion = FakeCompanionHome(fail = true)
        val legacy = FakeLegacyNetwork()
        val repository = AdaptiveNetworkRepository(flowOf(activeProfile(paired = true)), companion, legacy)

        runCatching { repository.load(ServerSettings()) }
        assertEquals(0, legacy.loads)
    }

    @Test fun unpairedProfileRetainsLegacyRciPath() = runTest {
        val companion = FakeCompanionHome()
        val legacy = FakeLegacyNetwork()
        val repository = AdaptiveNetworkRepository(flowOf(activeProfile(paired = false)), companion, legacy)

        repository.load(ServerSettings())
        assertEquals(0, companion.loads)
        assertEquals(1, legacy.loads)
    }

    private class FakeCompanionHome(private val fail: Boolean = false) : CompanionHomeDeviceGateway {
        var loads = 0
        var applies = 0
        private var state = "home-v1"
        override suspend fun load(endpoint: CompanionEndpoint): CompanionHomeDocument {
            loads++
            if (fail) error("companion unavailable")
            return CompanionHomeDocument(
                1,
                state,
                listOf(CompanionHomeDevice("mac-1234567890abcdef", "02:00:00:00:00:01", "Phone", ip = "192.168.1.10", online = true, staticReservation = false)),
            )
        }
        override suspend fun review(endpoint: CompanionEndpoint, stateVersion: String, deviceId: String, reservedIp: String?) =
            CompanionReservationPlan(1, "plan-1", "2026-08-12T12:00:00Z", stateVersion, "02:00:00:00:00:01", afterIp = reservedIp)
        override suspend fun apply(endpoint: CompanionEndpoint, stateVersion: String, deviceId: String, reservedIp: String?, planId: String): CompanionHomeMutationResult {
            applies++
            state = "home-v2"
            return CompanionHomeMutationResult(1, "committed", home = CompanionHomeDocument(1, state, emptyList()))
        }
    }

    private class FakeLegacyNetwork : NetworkGateway {
        var loads = 0
        override suspend fun load(settings: ServerSettings): List<NetworkDevice> {
            loads++
            return emptyList()
        }
        override suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String) = Unit
        override suspend fun removeStaticReservation(settings: ServerSettings, mac: String) = Unit
    }

    private fun activeProfile(paired: Boolean) = ActiveRouterProfile(
        RouterProfile(
            id = "router", displayName = "Router", host = "192.168.1.1", rciPort = 80,
            interfaceId = "Wireguard0", serverPublicKey = "", endpoint = "", subnetBase = "10.8.0.",
            dns = "192.168.1.1", mtu = 1380, keepalive = 25,
            companionUrl = if (paired) "https://192.168.1.1:18779" else "",
            certificatePin = if (paired) "sha256/test" else "",
        ),
        RouterSecrets(companionToken = if (paired) "device-token" else ""),
    )
}
