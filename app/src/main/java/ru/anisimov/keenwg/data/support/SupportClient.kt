package ru.anisimov.keenwg.data.support

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile

@Serializable data class SupportEvidence(val code: String, val at: String)
@Serializable data class SupportCheck(
    val layer: String,
    val status: String,
    @SerialName("duration_ms") val durationMs: Long,
    val observation: SupportEvidence,
    val inference: SupportEvidence,
)
@Serializable data class SupportSummary(
    val version: String,
    @SerialName("state_version") val stateVersion: ULong,
    val active: Boolean,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("target_kind") val targetKind: String,
    val transport: String,
)
@Serializable data class SupportReport(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val summary: SupportSummary,
    val checks: List<SupportCheck>,
    val notes: List<String>,
)
@Serializable data class SupportBundle(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val report: SupportReport,
    @SerialName("review_text") val reviewText: String,
)
data class SupportExport(val bundle: SupportBundle, val json: String, val text: String)

fun interface SupportGateway {
    suspend fun generate(profile: RouterProfile, token: String): SupportExport
}

class SupportClient(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false },
) : SupportGateway {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    override suspend fun generate(profile: RouterProfile, token: String): SupportExport = withContext(Dispatchers.IO) {
        require(token.isNotBlank() && token.length <= 512) { "Диагностика недоступна" }
        val base = base(profile)
        val request = Request.Builder()
            .url(requireNotNull(base.resolve("/v1/support/report")))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = try {
            client(profile, base).newCall(request).execute()
        } catch (_: IOException) {
            throw IllegalStateException("Диагностика недоступна")
        }
        response.use {
            if (!it.isSuccessful) error("Companion не сформировал отчёт")
            val raw = readBounded(requireNotNull(it.body).byteStream())
            val text = raw.toString(Charsets.UTF_8)
            val bundle = try {
                json.decodeFromString<SupportBundle>(text)
            } catch (_: Exception) {
                throw IllegalStateException("Схема диагностического отчёта не поддерживается")
            }
            requireValid(bundle, raw.size)
            SupportExport(bundle, text, bundle.reviewText)
        }
    }

    private fun requireValid(bundle: SupportBundle, bytes: Int) {
        require(bytes in 1..MAX_BUNDLE && bundle.schemaVersion == 1)
        require(TIMESTAMP.matches(bundle.generatedAt))
        val report = bundle.report
        require(report.schemaVersion == 1 && report.generatedAt == bundle.generatedAt)
        require(report.summary.version.length <= 256 && report.summary.nodeCount in 0..10_000)
        require(report.summary.targetKind in TARGET_KINDS && report.summary.transport in TRANSPORTS)
        require(report.checks.size <= 8 && report.checks.map { it.layer }.toSet().size == report.checks.size)
        require(report.checks.all { check ->
            check.layer in LAYERS && check.status in STATUSES && check.durationMs in 0..10_000 &&
                CODE.matches(check.observation.code) && CODE.matches(check.inference.code) &&
                TIMESTAMP.matches(check.observation.at) && TIMESTAMP.matches(check.inference.at)
        })
        require(report.notes.size <= 16 && report.notes.all { it.toByteArray().size <= 1024 })
        require(bundle.reviewText.toByteArray().size <= MAX_REVIEW)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BUNDLE) error("Диагностический отчёт слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun base(profile: RouterProfile): HttpUrl {
        val url = profile.companionUrl.toHttpUrlOrNull() ?: error("Companion не настроен")
        require(url.scheme == "https" && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
            url.query == null && url.fragment == null && (url.encodedPath == "/" || url.encodedPath.isEmpty()))
        return url
    }

    private fun client(profile: RouterProfile, base: HttpUrl) = clients.getOrPut(ClientKey(base.host, base.port, profile.certificatePin)) {
        val trust = ExactPinTrustManager(profile.certificatePin)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }

    private data class ClientKey(val host: String, val port: Int, val pin: String)

    private companion object {
        const val MAX_BUNDLE = 64 * 1024
        const val MAX_REVIEW = 16 * 1024
        val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
        val CODE = Regex("^[a-z0-9_]{1,64}$")
        val LAYERS = setOf("dns", "ipv4", "ipv6", "tcp", "quic")
        val STATUSES = setOf("ok", "failed", "unsupported")
        val TARGET_KINDS = setOf("none", "domain", "ipv4", "ipv6")
        val TRANSPORTS = setOf("unknown", "tcp", "quic", "other")
    }
}
