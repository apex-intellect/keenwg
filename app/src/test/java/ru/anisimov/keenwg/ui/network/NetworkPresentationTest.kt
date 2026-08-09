package ru.anisimov.keenwg.ui.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.network.DomainRoutingStatus
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.NetworkDevice
import ru.anisimov.keenwg.data.network.NetworkExclusionEntry
import ru.anisimov.keenwg.data.network.NetworkExclusionStatus

class NetworkPresentationTest {
    @Test fun `segment counts use their own data source`() {
        val state = NetworkUiState(
            devices = listOf(NetworkDevice("aa:bb:cc:dd:ee:ff", "ТВ", null, "192.168.1.2", null, true, false, null, null)),
            exclusions = NetworkExclusionStatus(1, 1u, listOf(NetworkExclusionEntry("ip", "192.0.2.0/24", false)), emptyList()),
            domains = DomainRoutingStatus(1, 1u, listOf(rule()), emptyList(), emptyList()),
        )
        assertEquals(1, segmentCount(NetworkSegment.DEVICES, state))
        assertEquals(1, segmentCount(NetworkSegment.IP_ADDRESSES, state))
        assertEquals(1, segmentCount(NetworkSegment.DOMAINS, state))
    }

    @Test fun `domain labels explain effect source and matcher`() {
        assertEquals("Напрямую", domainEffectLabel("direct"))
        assertEquals("Через VPN", domainEffectLabel("vpn"))
        assertEquals("вручную", domainSourceLabel("manual"))
        assertEquals("GeoSite", domainSourceLabel("geosite"))
        assertEquals("Домен · okko.sport", domainMatcherLabel(rule()))
        assertEquals("Зона · .ru", domainMatcherLabel(rule().copy(kind = "suffix", value = "ru")))
    }

    @Test fun `protected and system rules are read only`() {
        assertTrue(canEditDomainRule(rule()))
        assertFalse(canEditDomainRule(rule().copy(isProtected = true)))
        assertFalse(canEditDomainRule(rule().copy(source = "system")))
    }

    private fun rule() = DomainRule("rule-a", "domain", "okko.sport", "direct", "Okko", true, "manual", false)
}
