package ru.anisimov.keenwg.data.collector

import java.security.MessageDigest

object PeerId {
    fun compute(interfaceId: String, canonicalPublicKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$interfaceId\n$canonicalPublicKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
