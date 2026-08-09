package ru.anisimov.keenwg.data.network

import java.net.SocketTimeoutException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings

@Serializable data class DomainRule(
    val id: String,
    val kind: String,
    val value: String,
    val effect: String,
    val label: String,
    val enabled: Boolean,
    val source: String,
    @SerialName("protected") val isProtected: Boolean,
)

@Serializable data class DomainPreset(
    val id: String,
    val label: String,
    val matcher: String,
    val available: Boolean,
    val enabled: Boolean,
)

@Serializable data class DomainRoutingStatus(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("state_version") val stateVersion: ULong,
    val rules: List<DomainRule>,
    val presets: List<DomainPreset>,
    val warnings: List<String>,
)

data class DomainRuleDraft(
    val kind: String = "domain",
    val value: String = "",
    val effect: String = "direct",
    val label: String = "",
    val enabled: Boolean = true,
)

@Serializable data class DomainRoutingResult(val result: String, val status: DomainRoutingStatus)

@Serializable private data class DomainMutationRequest(
    @SerialName("state_version") val stateVersion: ULong,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val rule: DomainRule? = null,
)

interface DomainRoutingGateway {
    suspend fun load(settings: ServerSettings): DomainRoutingStatus
    suspend fun create(settings: ServerSettings, status: DomainRoutingStatus, draft: DomainRuleDraft): DomainRoutingResult
    suspend fun update(settings: ServerSettings, status: DomainRoutingStatus, id: String, draft: DomainRuleDraft): DomainRoutingResult
    suspend fun delete(settings: ServerSettings, status: DomainRoutingStatus, id: String): DomainRoutingResult
}

class DomainRoutingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(60))
        .writeTimeout(Duration.ofSeconds(10))
        .build(),
    private val urlValidator: (String) -> String? = ServerSettingsValidator::validateXkeenControllerUrl,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : DomainRoutingGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun load(settings: ServerSettings): DomainRoutingStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl(settings).newBuilder().addPathSegments("v1/network/domains").build())
            .authenticated(settings).get().build()
        execute(request) { decodeStatus(it) }
    }

    override suspend fun create(settings: ServerSettings, status: DomainRoutingStatus, draft: DomainRuleDraft) =
        mutate(settings, status, null, draft, "POST")

    override suspend fun update(settings: ServerSettings, status: DomainRoutingStatus, id: String, draft: DomainRuleDraft): DomainRoutingResult {
        requireRuleId(id)
        return mutate(settings, status, id, draft, "PUT")
    }

    override suspend fun delete(settings: ServerSettings, status: DomainRoutingStatus, id: String): DomainRoutingResult {
        requireRuleId(id)
        return mutate(settings, status, id, null, "DELETE")
    }

    private suspend fun mutate(
        settings: ServerSettings,
        status: DomainRoutingStatus,
        id: String?,
        draft: DomainRuleDraft?,
        method: String,
    ): DomainRoutingResult = withContext(Dispatchers.IO) {
        val builder = baseUrl(settings).newBuilder().addPathSegments("v1/network/domains/rules")
        if (id != null) builder.addPathSegment(id)
        val body = json.encodeToString(DomainMutationRequest(status.stateVersion, keyFactory(), draft?.toRequestRule())).toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(builder.build()).authenticated(settings)
        val request = when (method) {
            "POST" -> requestBuilder.post(body).build()
            "PUT" -> requestBuilder.put(body).build()
            else -> requestBuilder.delete(body).build()
        }
        executeMutation(request)
    }

    private fun DomainRuleDraft.toRequestRule() = DomainRule(
        id = "", kind = kind, value = value, effect = effect, label = label, enabled = enabled,
        source = when (kind) { "suffix" -> "zone"; "geosite" -> "geosite"; else -> "manual" },
        isProtected = false,
    )

    private fun executeMutation(request: Request): DomainRoutingResult {
        try {
            http.newCall(request).execute().use { response ->
                val text = readBounded(response)
                if (response.isSuccessful || response.code == 409 || response.code == 503) {
                    val result = decode<DomainRoutingResult>(text)
                    requireValid(result.status)
                    if (result.result !in RESULTS) schemaFailure()
                    return result
                }
                throw httpFailure(response.code)
            }
        } catch (known: XkeenException) {
            throw known
        } catch (timeout: SocketTimeoutException) {
            throw XkeenException(XkeenErrorCode.TIMEOUT, "Контроллер XKeen не ответил вовремя", timeout)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с контроллером XKeen прервана", failure)
        }
    }

    private fun <T> execute(request: Request, decoder: (String) -> T): T {
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code)
                return decoder(readBounded(response))
            }
        } catch (known: XkeenException) {
            throw known
        } catch (timeout: SocketTimeoutException) {
            throw XkeenException(XkeenErrorCode.TIMEOUT, "Контроллер XKeen не ответил вовремя", timeout)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с контроллером XKeen прервана", failure)
        }
    }

    private fun decodeStatus(text: String) = decode<DomainRoutingStatus>(text).also(::requireValid)
    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (failure: Exception) {
        throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема доменных правил не поддерживается", failure)
    }

    private fun requireValid(status: DomainRoutingStatus) {
        if (status.schemaVersion != 1 || status.rules.map { it.id }.toSet().size != status.rules.size ||
            status.rules.any { !validRule(it) } || status.presets.any { it.id.isBlank() || it.label.isBlank() || it.matcher.isBlank() }
        ) schemaFailure()
    }

    private fun validRule(rule: DomainRule) = RULE_ID.matches(rule.id) && rule.kind in KINDS && rule.effect in EFFECTS &&
        rule.source in SOURCES && rule.value.isNotBlank() && rule.label.length <= 160

    private fun readBounded(response: Response): String {
        val body = response.body ?: schemaFailure()
        if (body.contentLength() > MAX_BYTES) schemaFailure()
        val source = body.source()
        source.request(MAX_BYTES + 1)
        if (source.buffer.size > MAX_BYTES) schemaFailure()
        return source.readUtf8()
    }

    private fun baseUrl(settings: ServerSettings) = try {
        validate(settings)
        settings.xkeenControllerUrl.toHttpUrl()
    } catch (known: XkeenException) {
        throw known
    } catch (_: Exception) {
        throw invalid()
    }

    private fun Request.Builder.authenticated(settings: ServerSettings) = header("Authorization", "Bearer ${settings.xkeenControllerToken}")
        .header("Cache-Control", "no-store")

    private fun validate(settings: ServerSettings) {
        if (settings.xkeenControllerUrl.isBlank() || urlValidator(settings.xkeenControllerUrl) != null ||
            settings.xkeenControllerToken.isBlank() || settings.xkeenControllerToken.length > 256 || settings.xkeenControllerToken.any(Char::isISOControl)
        ) throw invalid()
    }

    private fun requireRuleId(id: String) { if (!RULE_ID.matches(id)) throw invalid() }

    private fun httpFailure(code: Int) = when (code) {
        401 -> XkeenException(XkeenErrorCode.UNAUTHORIZED, "Контроллер XKeen отклонил токен")
        404 -> XkeenException(XkeenErrorCode.NOT_FOUND, "Доменное правило не найдено")
        409 -> XkeenException(XkeenErrorCode.STALE_STATE, "Доменные правила изменились; обновите список")
        413 -> schemaFailure()
        429, 503 -> XkeenException(XkeenErrorCode.BUSY, "Контроллер XKeen занят другой операцией")
        else -> XkeenException(XkeenErrorCode.CONTROLLER_UNAVAILABLE, "Контроллер XKeen недоступен")
    }

    private fun invalid() = XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Некорректные параметры доменного правила")
    private fun schemaFailure(): Nothing = throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема доменных правил не поддерживается")

    private companion object {
        const val MAX_BYTES = 262_144L
        val JSON = "application/json; charset=utf-8".toMediaType()
        val RULE_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        val KINDS = setOf("domain", "suffix", "geosite")
        val EFFECTS = setOf("direct", "vpn")
        val SOURCES = setOf("manual", "zone", "geosite", "system")
        val RESULTS = setOf("committed", "rolled_back", "rejected", "uncertain")
    }
}
