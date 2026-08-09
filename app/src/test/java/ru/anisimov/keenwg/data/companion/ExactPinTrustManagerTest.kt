package ru.anisimov.keenwg.data.companion

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit

class ExactPinTrustManagerTest {
    @Test fun `exact valid leaf is accepted`() {
        val certificate = HeldCertificate.Builder().commonName("router").build().certificate
        val manager = ExactPinTrustManager(ExactPinTrustManager.pin(certificate))

        manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
    }

    @Test fun `different key empty chain and expired certificate fail closed`() {
        val expected = HeldCertificate.Builder().commonName("router-a").build().certificate
        val other = HeldCertificate.Builder().commonName("router-b").build().certificate
        val expired = HeldCertificate.Builder()
            .commonName("expired")
            .validityInterval(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3),
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
            )
            .build()
            .certificate
        val expectedManager = ExactPinTrustManager(ExactPinTrustManager.pin(expected))
        val expiredManager = ExactPinTrustManager(ExactPinTrustManager.pin(expired))

        assertCertificateFailure { expectedManager.checkServerTrusted(arrayOf(other), "ECDHE_ECDSA") }
        assertCertificateFailure { expectedManager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertCertificateFailure { expiredManager.checkServerTrusted(arrayOf(expired), "ECDHE_ECDSA") }
    }

    @Test fun `malformed pin is rejected during construction`() {
        listOf("", "sha256/not-base64!", "sha1/AAAA", "sha256/AAAA").forEach { pin ->
            assertTrue(runCatching { ExactPinTrustManager(pin) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun assertCertificateFailure(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is CertificateException)
    }
}
