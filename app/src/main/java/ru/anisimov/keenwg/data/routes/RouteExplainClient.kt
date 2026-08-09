package ru.anisimov.keenwg.data.routes

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile

@Serializable data class RouteExplainRequest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val domain: String? = null,
    val ip: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val protocol: String? = null,
    val port: Int = 0,
)

@Serializable data class RouteDecision(val outcome: String, @SerialName("rule_id") val ruleId: String? = null, val confidence: String)
@Serializable data class RouteStep(val kind: String, val label: String, val source: String, @SerialName("observed_at") val observedAt: String? = null)
@Serializable data class RouteAdapterObservation(val id: String, val available: Boolean, val reason: String? = null)
@Serializable data class RouteExplanation(
    @SerialName("schema_version") val schemaVersion: Int,
    val decision: RouteDecision,
    val steps: List<RouteStep>,
    @SerialName("shadowed_rule_ids") val shadowedRuleIds: List<String>,
    val warnings: List<String>,
    val adapters: List<RouteAdapterObservation>,
    @SerialName("observed_at") val observedAt: String,
)

interface RouteExplainGateway {
    suspend fun explain(profile: RouterProfile, token: String, request: RouteExplainRequest): RouteExplanation
}

class RouteExplainClient(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true },
) : RouteExplainGateway {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    override suspend fun explain(profile: RouterProfile, token: String, request: RouteExplainRequest): RouteExplanation = withContext(Dispatchers.IO) {
        require(token.isNotBlank() && token.length <= 512)
        require(request.schemaVersion == 1 && (request.domain == null) != (request.ip == null))
        require(request.protocol == null || request.protocol in setOf("tcp", "udp"))
        require(request.port in 0..65535)
        val base = validatedBaseUrl(profile)
        val url = base.resolve("/v1/routes/explain") ?: error("Invalid Companion URL")
        val body = json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE)
        val call = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer $token").post(body).build()
        val response = try { client(profile, base).newCall(call).execute() } catch (failure: IOException) {
            throw IllegalStateException("Объяснение маршрута недоступно", failure)
        }
        response.use {
            val text = it.body?.charStream()?.readTextBounded() ?: error("Пустой ответ Companion")
            if (!it.isSuccessful) error("Companion не смог объяснить маршрут")
            val explanation = json.decodeFromString<RouteExplanation>(text)
            requireValid(explanation)
            explanation
        }
    }

    private fun client(profile: RouterProfile, base: HttpUrl): OkHttpClient {
        val key = ClientKey(base.host, base.port, profile.certificatePin)
        return clients.getOrPut(key) {
            val trust = ExactPinTrustManager(profile.certificatePin)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).build()
        }
    }

    private fun validatedBaseUrl(profile: RouterProfile): HttpUrl {
        val url = profile.companionUrl.toHttpUrlOrNull() ?: error("Companion не настроен")
        require(url.scheme == "https" && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
            url.query == null && url.fragment == null && (url.encodedPath == "/" || url.encodedPath.isEmpty()))
        return url
    }

    private fun requireValid(value: RouteExplanation) {
        require(value.schemaVersion == 1 && value.decision.outcome.isNotBlank() && value.decision.confidence in setOf("observed", "inferred"))
        require(value.steps.size <= 128 && value.shadowedRuleIds.size <= 128 && value.warnings.size <= 128 && value.adapters.size <= 16)
        require(value.steps.all { it.kind.isNotBlank() && it.label.length <= 160 && it.source in setOf("observed", "inferred") })
    }

    private fun java.io.Reader.readTextBounded(): String {
        use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
                require(result.length <= MAX_RESPONSE_CHARS)
            }
            return result.toString()
        }
    }

    private data class ClientKey(val host: String, val port: Int, val pin: String)
    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_CHARS = 256 * 1024
    }
}
