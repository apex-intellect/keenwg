package ru.anisimov.keenwg.data.installer

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

object HostKeyPin {
    fun observe(keyBlob: ByteArray): HostKeyObservation {
        val algorithm = algorithm(keyBlob)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        return HostKeyObservation(algorithm, fingerprint)
    }

    fun matches(expected: HostKeyObservation, keyBlob: ByteArray): Boolean {
        val actual = runCatching { observe(keyBlob) }.getOrNull() ?: return false
        if (actual.algorithm != expected.algorithm) return false
        val expectedDigest = decodeFingerprint(expected.sha256) ?: return false
        val actualDigest = decodeFingerprint(actual.sha256) ?: return false
        return MessageDigest.isEqual(expectedDigest, actualDigest)
    }

    private fun algorithm(keyBlob: ByteArray): String {
        require(keyBlob.size >= 5) { "Malformed SSH host key" }
        val length = ByteBuffer.wrap(keyBlob, 0, 4).int
        require(length in 1..64 && 4 + length <= keyBlob.size) { "Malformed SSH host key" }
        val name = keyBlob.copyOfRange(4, 4 + length).toString(Charsets.US_ASCII)
        require(name.matches(Regex("[A-Za-z0-9@._+-]{1,64}"))) { "Malformed SSH host key algorithm" }
        return name
    }

    private fun decodeFingerprint(value: String): ByteArray? = runCatching {
        val encoded = value.removePrefix("SHA256:")
        if (encoded.length != 43 || value == encoded) return null
        Base64.getDecoder().decode(encoded + "=").takeIf { it.size == 32 }
    }.getOrNull()
}

internal class ExactHostKeyRepository(
    private val expected: HostKeyObservation,
) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray?): Int =
        if (key != null && HostKeyPin.matches(expected, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "KeenWG exact in-memory host key pin"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

internal class CapturingHostKeyRepository : HostKeyRepository {
    @Volatile var observation: HostKeyObservation? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key != null) observation = runCatching { HostKeyPin.observe(key) }.getOrNull()
        return HostKeyRepository.NOT_INCLUDED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "KeenWG observation-only host key capture"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
