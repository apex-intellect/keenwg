package ru.anisimov.keenwg.ui.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.network.DomainRoutingStatus
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.NetworkDevice
import ru.anisimov.keenwg.data.network.NetworkExclusionEntry
import ru.anisimov.keenwg.data.network.NetworkExclusionStatus

class NetworkPresentationTest {
    @Test fun `rule segments use task language`() {
        assertEquals(
            listOf(
                R.string.rules_devices,
                R.string.rules_addresses,
                R.string.rules_sites,
                R.string.rules_check,
                R.string.rules_sets,
            ),
            NetworkSegment.entries.map(::networkSegmentLabelResource),
        )
    }

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

    @Test fun `domain rule kinds are resource backed`() {
        assertEquals(R.string.rules_site_domain, domainRuleKindResource("domain"))
        assertEquals(R.string.rules_site_zone, domainRuleKindResource("suffix"))
        assertEquals(R.string.rules_site_category, domainRuleKindResource("geosite"))
    }

    @Test fun `protected and system rules are read only`() {
        assertTrue(canEditDomainRule(rule()))
        assertFalse(canEditDomainRule(rule().copy(isProtected = true)))
        assertFalse(canEditDomainRule(rule().copy(source = "system")))
    }

    private fun rule() = DomainRule("rule-a", "domain", "okko.sport", "direct", "Okko", true, "manual", false)
}
