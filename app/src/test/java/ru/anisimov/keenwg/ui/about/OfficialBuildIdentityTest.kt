package ru.anisimov.keenwg.ui.about

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialBuildIdentityTest {
    @Test fun `official status requires an exact certificate digest`() {
        val certificate = "apex-intellect-release-certificate".encodeToByteArray()
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            BuildProvenance.OFFICIAL,
            classifyBuildProvenance(listOf(certificate), expected.uppercase()),
        )
        assertEquals(
            BuildProvenance.UNVERIFIED,
            classifyBuildProvenance(listOf("other".encodeToByteArray()), expected),
        )
    }

    @Test fun `missing or malformed signer data fails closed`() {
        assertEquals(BuildProvenance.UNVERIFIED, classifyBuildProvenance(emptyList(), "ab"))
        assertEquals(BuildProvenance.UNVERIFIED, classifyBuildProvenance(listOf(byteArrayOf()), "not-a-sha256"))
    }
}
