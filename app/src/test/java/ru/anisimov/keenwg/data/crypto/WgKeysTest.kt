package ru.anisimov.keenwg.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WgKeysTest {
    @Test fun generates_valid_pair() {
        val kp = WgKeys.generate()
        assertEquals(44, kp.privateKey.length)
        assertEquals(44, kp.publicKey.length)
        assertNotEquals(kp.privateKey, kp.publicKey)
        assertEquals(kp.publicKey, WgKeys.publicFrom(kp.privateKey))
    }
}
