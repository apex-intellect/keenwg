package ru.anisimov.keenwg.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {
    @Test fun `expiry boundary is inclusive and never mutates access automatically`() {
        val policy = AccessPolicy(expiresAtEpochSeconds = 1_000)

        assertEquals(AccessExpiry.ACTIVE, policy.expiryAt(999))
        assertEquals(AccessExpiry.EXPIRED_REQUIRES_ACTION, policy.expiryAt(1_000))
        assertEquals(AccessExpiry.EXPIRED_REQUIRES_ACTION, policy.expiryAt(1_001))
    }

    @Test fun `allowed networks and dns are canonical bounded literals`() {
        val valid = AccessPolicy(
            allowedNetworks = listOf("0.0.0.0/0", "10.0.0.0/8", "2001:db8::/32"),
            dnsServers = listOf("192.168.1.1", "2001:4860:4860::8888"),
            expiresAtEpochSeconds = 2_000,
        )
        AccessPolicyValidator.requireValid(valid, nowEpochSeconds = 1_000)

        val invalid = listOf(
            valid.copy(allowedNetworks = emptyList()),
            valid.copy(allowedNetworks = listOf("example.com/24")),
            valid.copy(allowedNetworks = listOf("10.0.0.0/99")),
            valid.copy(dnsServers = listOf("dns.example.com")),
            valid.copy(expiresAtEpochSeconds = 1_000),
        )
        assertTrue(invalid.all { runCatching { AccessPolicyValidator.requireValid(it, 1_000) }.isFailure })
    }
}
