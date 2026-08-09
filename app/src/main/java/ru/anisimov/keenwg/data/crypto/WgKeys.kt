package ru.anisimov.keenwg.data.crypto

import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

data class WgKeyPair(val privateKey: String, val publicKey: String)

/** X25519 keygen via the WireGuard crypto classes (pure Java — no NDK/tunnel touched). */
object WgKeys {
    fun generate(): WgKeyPair {
        val kp = KeyPair()
        return WgKeyPair(kp.privateKey.toBase64(), kp.publicKey.toBase64())
    }

    fun publicFrom(privateKeyB64: String): String =
        KeyPair(Key.fromBase64(privateKeyB64)).publicKey.toBase64()
}
