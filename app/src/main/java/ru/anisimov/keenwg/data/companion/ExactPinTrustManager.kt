package ru.anisimov.keenwg.data.companion

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

// Companion uses a self-issued local certificate learned over a separately
// verified SSH channel. System CA validation would weaken, not strengthen,
// this exact SPKI trust contract.
@SuppressLint("CustomX509TrustManager")
class ExactPinTrustManager(expectedPin: String) : X509TrustManager {
    private val expectedSpkiHash: ByteArray = decodePin(expectedPin)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain == null || chain.size != 1 || authType.isNullOrBlank()) {
            throw CertificateException("Pinned server must present exactly one certificate")
        }
        val leaf = chain.single()
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (!MessageDigest.isEqual(expectedSpkiHash, actual)) {
            throw CertificateException("Server certificate pin mismatch")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not trusted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun pin(certificate: X509Certificate): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            return "sha256/${Base64.getEncoder().encodeToString(hash)}"
        }

        private fun decodePin(pin: String): ByteArray {
            require(pin.startsWith("sha256/")) { "Certificate pin must use sha256/" }
            val encoded = pin.removePrefix("sha256/")
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Certificate pin must contain standard base64", error)
            }
            require(decoded.size == 32 && Base64.getEncoder().encodeToString(decoded) == encoded) {
                "Certificate pin must contain one canonical SHA-256 digest"
            }
            return decoded
        }
    }
}
