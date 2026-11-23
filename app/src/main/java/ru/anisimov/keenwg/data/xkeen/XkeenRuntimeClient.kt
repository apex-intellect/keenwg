package ru.anisimov.keenwg.data.xkeen

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport

@Serializable
data class XkeenRuntimeStatus(
    @SerialName("schema_version") val schemaVersion: Int,
    val running: Boolean,
    @SerialName("runtime_only") val runtimeOnly: Boolean,
)

enum class XkeenRuntimeError {
    UNSUPPORTED, UNAUTHORIZED, FORBIDDEN, BUSY, UNAVAILABLE, INVALID_RESPONSE, VERIFICATION_FAILED,
}

class XkeenRuntimeException(val code: XkeenRuntimeError) :
    Exception("XKeen runtime request failed: ${code.name.lowercase()}")

interface XkeenRuntimeGateway {
    suspend fun status(endpoint: CompanionEndpoint): XkeenRuntimeStatus
    suspend fun access(endpoint: CompanionEndpoint): CapabilityAccess
    suspend fun setEnabled(endpoint: CompanionEndpoint, enabled: Boolean): XkeenRuntimeStatus
}

class XkeenRuntimeClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(readTimeoutSeconds = 70),
) : XkeenRuntimeGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun status(endpoint: CompanionEndpoint): XkeenRuntimeStatus =
        runtimeRequest(endpoint, "GET")

    override suspend fun setEnabled(endpoint: CompanionEndpoint, enabled: Boolean): XkeenRuntimeStatus =
        runtimeRequest(endpoint, "POST", json.encodeToString(EnabledRequest(enabled)))

    override suspend fun access(endpoint: CompanionEndpoint): CapabilityAccess = withContext(Dispatchers.IO) {
        val body = request(endpoint, "/v1/capabilities", "GET")
        val document = try {
            json.decodeFromString<CapabilityDocument>(body)
        } catch (_: SerializationException) {
            throw XkeenRuntimeException(XkeenRuntimeError.INVALID_RESPONSE)
        }
        if (document.schemaVersion != 1 || document.capabilities.any { it.schemaVersion != 1 } ||
            document.capabilities.map { it.id }.distinct().size != document.capabilities.size
        ) throw XkeenRuntimeException(XkeenRuntimeError.INVALID_RESPONSE)
        val capability = document.capabilities.singleOrNull { it.id == "xkeen.runtime" }
            ?: throw XkeenRuntimeException(XkeenRuntimeError.UNSUPPORTED)
        if (!capability.available) throw XkeenRuntimeException(XkeenRuntimeError.UNSUPPORTED)
        capability.access
    }

    private suspend fun runtimeRequest(
        endpoint: CompanionEndpoint,
        method: String,
        body: String? = null,
    ): XkeenRuntimeStatus = withContext(Dispatchers.IO) {
        val response = request(endpoint, "/v1/xkeen/runtime", method, body)
        val status = try {
            json.decodeFromString<XkeenRuntimeStatus>(response)
        } catch (_: SerializationException) {
            throw XkeenRuntimeException(XkeenRuntimeError.INVALID_RESPONSE)
        }
        if (status.schemaVersion != 1 || !status.runtimeOnly) {
            throw XkeenRuntimeException(XkeenRuntimeError.INVALID_RESPONSE)
        }
        status
    }

    private fun request(endpoint: CompanionEndpoint, path: String, method: String, body: String? = null): String {
        val response = try {
            transport.execute(endpoint, path, method, body, maxResponseBytes = 65_536)
        } catch (_: IOException) {
            throw XkeenRuntimeException(XkeenRuntimeError.UNAVAILABLE)
        }
        if (response.status != 200) {
            val error = when (response.status) {
                401 -> XkeenRuntimeError.UNAUTHORIZED
                403 -> XkeenRuntimeError.FORBIDDEN
                404 -> XkeenRuntimeError.UNSUPPORTED
                409 -> XkeenRuntimeError.BUSY
                503 -> when (runCatching { json.decodeFromString<ErrorResponse>(response.body).error }.getOrNull()) {
                    "feature_unavailable" -> XkeenRuntimeError.UNSUPPORTED
                    "runtime_verification_failed" -> XkeenRuntimeError.VERIFICATION_FAILED
                    else -> XkeenRuntimeError.UNAVAILABLE
                }
                else -> XkeenRuntimeError.UNAVAILABLE
            }
            throw XkeenRuntimeException(error)
        }
        return response.body
    }

    @Serializable private data class EnabledRequest(val enabled: Boolean)
    @Serializable private data class ErrorResponse(val error: String)
}
