package ru.anisimov.keenwg.data.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HostKeyPinTest {
    @Test fun `OpenSSH SHA256 fingerprint is canonical and unpadded`() {
        val key = keyBlob("ssh-ed25519", ByteArray(32) { it.toByte() })

        val observed = HostKeyPin.observe(key)

        assertEquals("ssh-ed25519", observed.algorithm)
        assertEquals("SHA256:OOMwCahJqCUC5vJDQLW6XAEOazkBM4yLc+h2Pubn8eg", observed.sha256)
        assertFalse(observed.sha256.endsWith("="))
    }

    @Test fun `algorithm and digest must both match in constant pin check`() {
        val key = keyBlob("ssh-ed25519", ByteArray(32) { it.toByte() })
        val expected = HostKeyPin.observe(key)

        assertTrue(HostKeyPin.matches(expected, key))
        assertFalse(HostKeyPin.matches(expected.copy(algorithm = "ssh-rsa"), key))
        assertFalse(HostKeyPin.matches(expected, key.copyOf().also { it[it.lastIndex] = 99 }))
    }

    @Test fun `malformed key blob and fingerprint fail closed`() {
        assertTrue(runCatching { HostKeyPin.observe(byteArrayOf(1, 2, 3)) }.isFailure)
        assertTrue(runCatching { HostKeyObservation("ssh-ed25519", "SHA256:bad=") }.isFailure)
    }

    private fun keyBlob(algorithm: String, payload: ByteArray): ByteArray {
        val name = algorithm.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(4 + name.size + payload.size).putInt(name.size).put(name).put(payload).array()
    }
}
