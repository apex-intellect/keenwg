package ru.anisimov.keenwg.data.catalog

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException
import ru.anisimov.keenwg.data.companion.requireCompanionTarget
import ru.anisimov.keenwg.domain.model.RouterProfile

data class SourceConfigurationStatus(val configured: Boolean)

interface SourceConfigurationGateway {
    suspend fun status(profile: RouterProfile, token: String, sourceId: String): SourceConfigurationStatus
    suspend fun replace(
        profile: RouterProfile,
        token: String,
        sourceId: String,
        subscriptionUrl: ByteArray,
    ): SourceConfigurationStatus
}

class SourceConfigurationClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    },
) : SourceConfigurationGateway {
    override suspend fun status(
        profile: RouterProfile,
        token: String,
        sourceId: String,
    ): SourceConfigurationStatus = withContext(Dispatchers.IO) {
        requireSource(sourceId)
        decode(execute(profile, requireToken(token), sourceId))
    }

    override suspend fun replace(
        profile: RouterProfile,
        token: String,
        sourceId: String,
        subscriptionUrl: ByteArray,
    ): SourceConfigurationStatus = withContext(Dispatchers.IO) {
        try {
            requireSource(sourceId)
            val value = decodeAndValidateURL(subscriptionUrl)
            val body = json.encodeToString(ReplaceRequest(subscriptionUrl = value))
            decode(execute(profile, requireToken(token), sourceId, method = "PUT", body = body))
        } finally {
            subscriptionUrl.fill(0)
        }
    }

    private fun execute(
        profile: RouterProfile,
        token: String,
        sourceId: String,
        method: String = "GET",
        body: String? = null,
    ): String {
        val target = try {
            profile.requireCompanionTarget()
        } catch (_: IllegalArgumentException) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        val response = try {
            transport.execute(
                target = target,
                path = "/v1/connections/sources/$sourceId/configuration",
                method = method,
                token = token,
                body = body,
                maxResponseBytes = MAX_BODY_BYTES,
            )
        } catch (_: CompanionResponseTooLargeException) {
            throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        } catch (_: CompanionTransportException) {
            throw CatalogException(CatalogErrorCode.UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        if (response.status != 200) throw statusFailure(response.status)
        return response.body
    }

    private fun decode(body: String): SourceConfigurationStatus {
        val document = try {
            json.decodeFromString<StatusDocument>(body)
        } catch (_: SerializationException) {
            throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        } catch (_: IllegalArgumentException) {
            throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        }
        if (document.schemaVersion != SCHEMA_VERSION) {
            throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        }
        return SourceConfigurationStatus(document.configured)
    }

    private fun decodeAndValidateURL(value: ByteArray): String {
        if (value.isEmpty() || value.size > MAX_URL_BYTES) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        val raw = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString()
        } catch (_: Exception) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        if (raw.isBlank() || raw != raw.trim() || raw.any(Char::isISOControl)) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        val parsed = raw.toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host.isBlank() ||
            parsed.encodedUsername.isNotEmpty() || parsed.encodedPassword.isNotEmpty() || parsed.fragment != null
        ) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        return raw
    }

    private fun requireSource(value: String) {
        if (value != XKEEN_SOURCE_ID) throw CatalogException(CatalogErrorCode.NOT_FOUND)
    }

    private fun requireToken(value: String): String {
        if (value.isBlank() || value.length > 512 || value.any { it.isWhitespace() || it.isISOControl() }) {
            throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        }
        return value
    }

    private fun statusFailure(status: Int) = CatalogException(
        when (status) {
            400 -> CatalogErrorCode.INVALID_SETTINGS
            401 -> CatalogErrorCode.UNAUTHORIZED
            403 -> CatalogErrorCode.FORBIDDEN
            404 -> CatalogErrorCode.NOT_FOUND
            413 -> CatalogErrorCode.PAYLOAD_TOO_LARGE
            in 500..599 -> CatalogErrorCode.UNAVAILABLE
            else -> CatalogErrorCode.PROTOCOL
        },
    )

    @Serializable
    private data class StatusDocument(
        @SerialName("schema_version") val schemaVersion: Int,
        val configured: Boolean,
    )

    @Serializable
    private data class ReplaceRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        @SerialName("subscription_url") val subscriptionUrl: String,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BODY_BYTES = 16_384L
        const val MAX_URL_BYTES = 8_192
        const val XKEEN_SOURCE_ID = "xkeen-subscription"
    }
}
