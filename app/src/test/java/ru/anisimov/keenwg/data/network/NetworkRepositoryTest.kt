package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings

class NetworkRepositoryTest {
    @Test fun `static reservation is changed saved and verified`() = runTest {
        val source = FakeSource()
        val repository = NetworkRepository(source)

        repository.setStaticReservation(ServerSettings(), "4c:3b:df:a6:1e:24", "192.168.1.141")

        assertEquals("192.168.1.141", repository.load(ServerSettings()).single().reservedIp)
        assertTrue(source.saved)
    }

    @Test fun `duplicate address is rejected before mutation`() = runTest {
        val source = FakeSource(reservations = mutableMapOf("70:d8:c2:71:b2:09" to "192.168.1.66"))
        val repository = NetworkRepository(source)

        val failure = runCatching { repository.setStaticReservation(ServerSettings(), "4c:3b:df:a6:1e:24", "192.168.1.66") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, source.mutations)
    }

    private class FakeSource(
        val reservations: MutableMap<String, String> = mutableMapOf(),
    ) : NetworkDataSource {
        var saved = false
        var mutations = 0
        override suspend fun hotspot(settings: ServerSettings) = """{"host":[{"mac":"4c:3b:df:a6:1e:24","ip":"192.168.1.141","name":"xbox","active":false}]}"""
        override suspend fun leases(settings: ServerSettings) = """{"lease":[]}"""
        override suspend fun runningConfig(settings: ServerSettings) = """{"message":[${reservations.entries.joinToString { "\"ip dhcp host ${it.key} ${it.value}\"" }}]}"""
        override suspend fun setReservation(settings: ServerSettings, mac: String, ip: String?) { mutations++; if (ip == null) reservations.remove(mac) else reservations[mac] = ip }
        override suspend fun save(settings: ServerSettings) { saved = true }
    }
}
