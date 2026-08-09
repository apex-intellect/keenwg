package ru.anisimov.keenwg.data.rci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RciCommandsNetworkTest {
    @Test fun `static dhcp commands are exact and injection safe`() {
        assertEquals("""[{"parse":"ip dhcp host 4c:3b:df:a6:1e:24 192.168.1.141"}]""", RciCommands.setDhcpHost("4C:3B:DF:A6:1E:24", "192.168.1.141"))
        assertEquals("""[{"parse":"no ip dhcp host 4c:3b:df:a6:1e:24"}]""", RciCommands.removeDhcpHost("4c:3b:df:a6:1e:24"))
        assertThrows(IllegalArgumentException::class.java) { RciCommands.setDhcpHost("4c:3b:df:a6:1e:24; reboot", "192.168.1.141") }
        assertThrows(IllegalArgumentException::class.java) { RciCommands.setDhcpHost("4c:3b:df:a6:1e:24", "192.168.1.141; reboot") }
    }
}
