package ru.anisimov.keenwg.data.rci

import java.security.MessageDigest

/**
 * Keenetic RCI challenge-response auth (verified live 2026-06-23):
 * response = sha256( challenge + md5(login:realm:password) ), all hex-lowercase.
 */
object RciAuth {
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun md5Hex(s: String): String =
        hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.US_ASCII)))

    fun sha256Hex(s: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII)))

    fun authResponse(login: String, realm: String, password: String, challenge: String): String =
        sha256Hex(challenge + md5Hex("$login:$realm:$password"))
}
