package ru.anisimov.keenwg.test

import java.io.Closeable
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager

class TestCompanionServer(vararg responses: MockResponse) : Closeable {
    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("::1")
        .build()
    private val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val server = MockWebServer().apply {
        useHttps(certificates.sslSocketFactory(), false)
        responses.forEach(::enqueue)
        start(InetAddress.getLoopbackAddress(), 0)
    }

    fun endpoint(token: String = "device-token") = CompanionEndpoint(
        baseUrl = "https://localhost:${server.port}/".toHttpUrl(),
        certificatePin = ExactPinTrustManager.pin(certificate.certificate),
        deviceToken = token,
    )

    fun enqueue(response: MockResponse) = server.enqueue(response)
    fun takeRequest(): RecordedRequest = server.takeRequest()
    val requestCount: Int get() = server.requestCount

    override fun close() = server.shutdown()
}
