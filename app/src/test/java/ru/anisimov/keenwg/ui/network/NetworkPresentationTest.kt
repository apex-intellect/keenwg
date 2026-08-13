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
        assertEquals(R.string.rules_direct, domainEffectResource("direct"))
        assertEquals(R.string.rules_via_vpn, domainEffectResource("vpn"))
        assertEquals(R.string.domain_source_manual, domainSourceResource("manual"))
        assertEquals(R.string.domain_source_geosite, domainSourceResource("geosite"))
        assertEquals(R.string.domain_matcher_domain, domainMatcherResource(rule()))
        assertEquals("okko.sport", domainMatcherValue(rule()))
        val suffix = rule().copy(kind = "suffix", value = "ru")
        assertEquals(R.string.domain_matcher_zone, domainMatcherResource(suffix))
        assertEquals("ru", domainMatcherValue(suffix))
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
