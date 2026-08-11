package ru.anisimov.keenwg.data.network

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
    suspend fun load(endpoint: CompanionEndpoint): NetworkExclusionStatus
    suspend fun mutate(endpoint: CompanionEndpoint, stateVersion: ULong, action: String, value: String): NetworkExclusionResult
}

class NetworkExclusionClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
) : NetworkExclusionGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun load(endpoint: CompanionEndpoint): NetworkExclusionStatus = withContext(Dispatchers.IO) {
        execute(endpoint, "GET", null) { decodeStatus(it) }
    }

    override suspend fun mutate(endpoint: CompanionEndpoint, stateVersion: ULong, action: String, value: String): NetworkExclusionResult = withContext(Dispatchers.IO) {
        require(action == "add" || action == "delete")
        val body = json.encodeToString(NetworkExclusionMutation(stateVersion, action, value))
        execute(endpoint, "POST", body) { text ->
            val result = decode<NetworkExclusionResult>(text)
            requireValid(result.status)
            if (result.result !in setOf("committed", "rolled_back", "rejected", "uncertain")) schemaFailure()
            result
        }
    }

    private fun <T> execute(endpoint: CompanionEndpoint, method: String, body: String?, decoder: (String) -> T): T = try {
        val response = transport.execute(endpoint, "/v1/network/exclusions", method, body, MAX_BYTES)
        if (response.status !in 200..299) throw XkeenException(
            if (response.status == 409) XkeenErrorCode.STALE_STATE else XkeenErrorCode.COMPANION_UNAVAILABLE,
            "Не удалось изменить исключения XKeen",
        )
        decoder(response.body)
    } catch (known: XkeenException) {
        throw known
    } catch (failure: CompanionResponseTooLargeException) {
        schemaFailure()
    } catch (failure: CompanionTransportException) {
        throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
    }

    private fun decodeStatus(text: String): NetworkExclusionStatus = decode<NetworkExclusionStatus>(text).also(::requireValid)
    private inline fun <reified T> decode(text: String): T = try { json.decodeFromString<T>(text) } catch (failure: Exception) { throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема исключений не поддерживается", failure) }
    private fun requireValid(status: NetworkExclusionStatus) {
        if (status.schemaVersion != 1 || status.entries.any { it.id.isBlank() || it.value.isBlank() } || status.entries.map { it.id }.toSet().size != status.entries.size) schemaFailure()
    }
    private fun schemaFailure(): Nothing = throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема исключений не поддерживается")
    private companion object { const val MAX_BYTES = 262_144L }
}
