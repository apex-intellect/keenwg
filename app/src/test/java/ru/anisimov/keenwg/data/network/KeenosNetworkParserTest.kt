package ru.anisimov.keenwg.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeenosNetworkParserTest {
    @Test fun `inventory merges hotspot lease and static reservation by mac`() {
        val hotspot = """{"host":[
          {"mac":"4c:3b:df:a6:1e:24","ip":"0.0.0.0","hostname":"","name":"xbox","active":false,"interface":{"name":"Home"}},
          {"mac":"70:d8:c2:71:b2:09","ip":"192.168.1.66","hostname":"srv-home","name":"сервак","active":true,"rssi":-55,"interface":{"name":"Home"}}
        ]}"""
        val leases = """{"lease":[
          {"ip":"192.168.1.141","mac":"4c:3b:df:a6:1e:24","hostname":"XBOX","name":"xbox","expires":4294},
          {"ip":"192.168.1.66","mac":"70:d8:c2:71:b2:09","hostname":"srv-home","name":"сервак","expires":"infinity"}
        ]}"""
        val config = """{"message":["ip dhcp host 70:d8:c2:71:b2:09 192.168.1.66"]}"""

        val devices = KeenosNetworkParser.devices(hotspot, leases, config)

        assertEquals(listOf("сервак", "xbox"), devices.map { it.name })
        assertEquals("192.168.1.141", devices.last().ip)
        assertFalse(devices.last().online)
        assertTrue(devices.first().staticReservation)
        assertEquals(-55, devices.first().rssi)
    }
}
