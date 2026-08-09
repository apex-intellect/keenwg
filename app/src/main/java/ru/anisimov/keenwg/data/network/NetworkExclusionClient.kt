package ru.anisimov.keenwg.data.network

import java.time.Duration
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
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings

@Serializable data class NetworkExclusionEntry(val id: String, val value: String, @SerialName("protected") val isProtected: Boolean)
@Serializable data class NetworkExclusionStatus(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("state_version") val stateVersion: ULong,
    val entries: List<NetworkExclusionEntry>,
    val warnings: List<String>,
)
@Serializable data class NetworkExclusionMutation(@SerialName("state_version") val stateVersion: ULong, val action: String, val value: String)
@Serializable data class NetworkExclusionResult(val result: String, val status: NetworkExclusionStatus)

interface NetworkExclusionGateway {
    suspend fun load(settings: ServerSettings): NetworkExclusionStatus
    suspend fun mutate(settings: ServerSettings, stateVersion: ULong, action: String, value: String): NetworkExclusionResult
}

class NetworkExclusionClient(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(60)).build(),
    private val urlValidator: (String) -> String? = ServerSettingsValidator::validateXkeenControllerUrl,
) : NetworkExclusionGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun load(settings: ServerSettings): NetworkExclusionStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(settings)).authenticated(settings).get().build()
        execute(request) { decodeStatus(it) }
    }

    override suspend fun mutate(settings: ServerSettings, stateVersion: ULong, action: String, value: String): NetworkExclusionResult = withContext(Dispatchers.IO) {
        require(action == "add" || action == "delete")
        val body = json.encodeToString(NetworkExclusionMutation(stateVersion, action, value)).toRequestBody(JSON)
        val request = Request.Builder().url(url(settings)).authenticated(settings).post(body).build()
        execute(request) { text ->
            val result = decode<NetworkExclusionResult>(text)
            requireValid(result.status)
            if (result.result !in setOf("committed", "rolled_back", "rejected", "uncertain")) schemaFailure()
            result
        }
    }

    private fun url(settings: ServerSettings) = try {
        validate(settings)
        settings.xkeenControllerUrl.toHttpUrl().newBuilder().addPathSegments("v1/network/exclusions").build()
    } catch (failure: XkeenException) { throw failure } catch (_: Exception) { throw invalid() }

    private fun Request.Builder.authenticated(settings: ServerSettings) = header("Authorization", "Bearer ${settings.xkeenControllerToken}").header("Cache-Control", "no-store")

    private fun <T> execute(request: Request, decoder: (String) -> T): T = try {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw XkeenException(if (response.code == 409) XkeenErrorCode.STALE_STATE else XkeenErrorCode.CONTROLLER_UNAVAILABLE, "Не удалось изменить исключения XKeen")
            val body = response.body ?: schemaFailure()
            if (body.contentLength() > MAX_BYTES) schemaFailure()
            val source = body.source(); source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) schemaFailure()
            decoder(source.readUtf8())
        }
    } catch (known: XkeenException) { throw known } catch (failure: Exception) { throw XkeenException(XkeenErrorCode.NETWORK, "Связь с контроллером XKeen прервана", failure) }

    private fun decodeStatus(text: String): NetworkExclusionStatus = decode<NetworkExclusionStatus>(text).also(::requireValid)
    private inline fun <reified T> decode(text: String): T = try { json.decodeFromString<T>(text) } catch (failure: Exception) { throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема исключений не поддерживается", failure) }
    private fun requireValid(status: NetworkExclusionStatus) {
        if (status.schemaVersion != 1 || status.entries.any { it.id.isBlank() || it.value.isBlank() } || status.entries.map { it.id }.toSet().size != status.entries.size) schemaFailure()
    }
    private fun validate(settings: ServerSettings) {
        if (settings.xkeenControllerUrl.isBlank() || urlValidator(settings.xkeenControllerUrl) != null || settings.xkeenControllerToken.isBlank()) throw invalid()
    }
    private fun invalid() = XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Контроллер XKeen не настроен")
    private fun schemaFailure(): Nothing = throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема исключений не поддерживается")
    private companion object { const val MAX_BYTES = 262_144L; val JSON = "application/json; charset=utf-8".toMediaType() }
}
