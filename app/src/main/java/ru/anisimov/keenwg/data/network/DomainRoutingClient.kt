package ru.anisimov.keenwg.data.network

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException

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
    suspend fun load(endpoint: CompanionEndpoint): DomainRoutingStatus
    suspend fun create(endpoint: CompanionEndpoint, status: DomainRoutingStatus, draft: DomainRuleDraft): DomainRoutingResult
    suspend fun update(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String, draft: DomainRuleDraft): DomainRoutingResult
    suspend fun delete(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String): DomainRoutingResult
}

class DomainRoutingClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : DomainRoutingGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun load(endpoint: CompanionEndpoint): DomainRoutingStatus = withContext(Dispatchers.IO) {
        execute(endpoint, "/v1/network/domains") { decodeStatus(it) }
    }

    override suspend fun create(endpoint: CompanionEndpoint, status: DomainRoutingStatus, draft: DomainRuleDraft) =
        mutate(endpoint, status, null, draft, "POST")

    override suspend fun update(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String, draft: DomainRuleDraft): DomainRoutingResult {
        requireRuleId(id)
        return mutate(endpoint, status, id, draft, "PUT")
    }

    override suspend fun delete(endpoint: CompanionEndpoint, status: DomainRoutingStatus, id: String): DomainRoutingResult {
        requireRuleId(id)
        return mutate(endpoint, status, id, null, "DELETE")
    }

    private suspend fun mutate(
        endpoint: CompanionEndpoint,
        status: DomainRoutingStatus,
        id: String?,
        draft: DomainRuleDraft?,
        method: String,
    ): DomainRoutingResult = withContext(Dispatchers.IO) {
        val path = "/v1/network/domains/rules" + (id?.let { "/$it" } ?: "")
        val body = json.encodeToString(DomainMutationRequest(status.stateVersion, keyFactory(), draft?.toRequestRule()))
        executeMutation(endpoint, path, method, body)
    }

    private fun DomainRuleDraft.toRequestRule() = DomainRule(
        id = "", kind = kind, value = value, effect = effect, label = label, enabled = enabled,
        source = when (kind) { "suffix" -> "zone"; "geosite" -> "geosite"; else -> "manual" },
        isProtected = false,
    )

    private fun executeMutation(endpoint: CompanionEndpoint, path: String, method: String, body: String): DomainRoutingResult {
        try {
            val response = transport.execute(endpoint, path, method, body, MAX_BYTES)
            if (response.status in 200..299 || response.status == 409 || response.status == 503) {
                val result = decode<DomainRoutingResult>(response.body)
                requireValid(result.status)
                if (result.result !in RESULTS) schemaFailure()
                return result
            }
            throw httpFailure(response.status)
        } catch (known: XkeenException) {
            throw known
        } catch (failure: CompanionResponseTooLargeException) {
            schemaFailure()
        } catch (failure: CompanionTransportException) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
        }
    }

    private fun <T> execute(endpoint: CompanionEndpoint, path: String, decoder: (String) -> T): T {
        try {
            val response = transport.execute(endpoint, path, maxResponseBytes = MAX_BYTES)
            if (response.status !in 200..299) throw httpFailure(response.status)
            return decoder(response.body)
        } catch (known: XkeenException) {
            throw known
        } catch (failure: CompanionResponseTooLargeException) {
            schemaFailure()
        } catch (failure: CompanionTransportException) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
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

    private fun requireRuleId(id: String) { if (!RULE_ID.matches(id)) throw invalid() }

    private fun httpFailure(code: Int) = when (code) {
        401 -> XkeenException(XkeenErrorCode.UNAUTHORIZED, "Companion отклонил токен устройства")
        404 -> XkeenException(XkeenErrorCode.NOT_FOUND, "Доменное правило не найдено")
        409 -> XkeenException(XkeenErrorCode.STALE_STATE, "Доменные правила изменились; обновите список")
        413 -> schemaFailure()
        429, 503 -> XkeenException(XkeenErrorCode.BUSY, "Companion занят другой операцией")
        else -> XkeenException(XkeenErrorCode.COMPANION_UNAVAILABLE, "Companion недоступен")
    }

    private fun invalid() = XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Некорректные параметры доменного правила")
    private fun schemaFailure(): Nothing = throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема доменных правил не поддерживается")

    private companion object {
        const val MAX_BYTES = 262_144L
        val RULE_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        val KINDS = setOf("domain", "suffix", "geosite")
        val EFFECTS = setOf("direct", "vpn")
        val SOURCES = setOf("manual", "zone", "geosite", "system")
        val RESULTS = setOf("committed", "rolled_back", "rejected", "uncertain")
    }
}
