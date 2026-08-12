package ru.anisimov.keenwg.data.update

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException
import ru.anisimov.keenwg.data.installer.VerifiedCompanionAsset

interface CompanionUpdateGateway {
    suspend fun status(endpoint: CompanionEndpoint): CompanionUpdateStatus
    suspend fun install(endpoint: CompanionEndpoint, asset: VerifiedCompanionAsset): CompanionUpdateAccepted
}

class CompanionUpdateClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    },
) : CompanionUpdateGateway {
    override suspend fun status(endpoint: CompanionEndpoint): CompanionUpdateStatus = withContext(Dispatchers.IO) {
        val response = execute { transport.execute(endpoint, UPDATE_PATH, maxResponseBytes = MAX_RESPONSE_BYTES) }
        if (response.status != 200) throw statusFailure(response.status)
        val document = decode<UpdateStatusDocument>(response.body)
        if (document.schemaVersion != SCHEMA_VERSION || !VERSION.matches(document.currentVersion) ||
            !SAFE_STATE.matches(document.phase) || !SAFE_STATE.matches(document.result) ||
            document.targetVersion?.takeIf(String::isNotBlank)?.let(VERSION::matches) == false ||
            document.error?.takeIf(String::isNotBlank)?.let(SAFE_STATE::matches) == false
        ) throw CompanionUpdateException(CompanionUpdateError.UNSUPPORTED)
        CompanionUpdateStatus(
            document.currentVersion, document.supported, document.phase, document.result,
            document.targetVersion?.takeIf(String::isNotBlank), document.error?.takeIf(String::isNotBlank),
        )
    }

    override suspend fun install(
        endpoint: CompanionEndpoint,
        asset: VerifiedCompanionAsset,
    ): CompanionUpdateAccepted = withContext(Dispatchers.IO) {
        try {
            val signedManifest = SignedUpdateManifestDocument(
                schemaVersion = asset.manifest.schemaVersion,
                version = asset.manifest.version,
                architecture = asset.manifest.architecture,
                archiveSha256 = asset.manifest.sha256,
                archiveSize = asset.manifest.size,
                binarySha256 = asset.manifest.binarySha256,
                keyId = asset.manifest.keyId,
                signature = asset.manifest.signature,
            )
            val manifestBytes = json.encodeToString(signedManifest).toByteArray(Charsets.UTF_8)
            if (manifestBytes.isEmpty() || manifestBytes.size > MAX_MANIFEST_BYTES ||
                asset.bytes.size != asset.manifest.size
            ) throw CompanionUpdateException(CompanionUpdateError.INVALID_UPDATE)
            val response = try {
                execute {
                    transport.execute(
                        endpoint = endpoint,
                        path = UPDATE_PATH,
                        method = "POST",
                        body = UpdateEnvelopeRequestBody(manifestBytes, asset.bytes),
                        maxResponseBytes = MAX_RESPONSE_BYTES,
                    )
                }
            } finally {
                manifestBytes.fill(0)
            }
            if (response.status != 202) throw statusFailure(response.status)
            val document = decode<UpdateAcceptedDocument>(response.body)
            if (document.schemaVersion != SCHEMA_VERSION || !VERSION.matches(document.targetVersion) ||
                document.targetVersion != asset.manifest.version
            ) throw CompanionUpdateException(CompanionUpdateError.UNSUPPORTED)
            CompanionUpdateAccepted(document.targetVersion)
        } finally {
            asset.bytes.fill(0)
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString(body)
    } catch (_: SerializationException) {
        throw CompanionUpdateException(CompanionUpdateError.UNSUPPORTED)
    } catch (_: IllegalArgumentException) {
        throw CompanionUpdateException(CompanionUpdateError.UNSUPPORTED)
    }

    private fun <T> execute(block: () -> T): T = try {
        block()
    } catch (_: CompanionResponseTooLargeException) {
        throw CompanionUpdateException(CompanionUpdateError.UNSUPPORTED)
    } catch (_: CompanionTransportException) {
        throw CompanionUpdateException(CompanionUpdateError.UNAVAILABLE)
    } catch (_: IOException) {
        throw CompanionUpdateException(CompanionUpdateError.UNAVAILABLE)
    }

    private fun statusFailure(status: Int) = CompanionUpdateException(
        when (status) {
            400 -> CompanionUpdateError.INVALID_UPDATE
            401 -> CompanionUpdateError.UNAUTHORIZED
            403 -> CompanionUpdateError.FORBIDDEN
            404 -> CompanionUpdateError.UNSUPPORTED
            409 -> CompanionUpdateError.BUSY
            413 -> CompanionUpdateError.TOO_LARGE
            in 500..599 -> CompanionUpdateError.UNAVAILABLE
            else -> CompanionUpdateError.UNSUPPORTED
        },
    )

    private class UpdateEnvelopeRequestBody(
        private val manifest: ByteArray,
        private val archive: ByteArray,
    ) : RequestBody() {
        override fun contentType() = UPDATE_MEDIA_TYPE
        override fun contentLength() = 4L + manifest.size + archive.size
        override fun writeTo(sink: BufferedSink) {
            sink.writeInt(manifest.size)
            sink.write(manifest)
            sink.write(archive)
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val UPDATE_PATH = "/v1/system/update"
        const val MAX_MANIFEST_BYTES = 16 * 1024
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        val UPDATE_MEDIA_TYPE = "application/vnd.apex-intellect.keenwg-update.v1".toMediaType()
        val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")
        val SAFE_STATE = Regex("[a-z][a-z0-9_]{1,63}")
    }
}
