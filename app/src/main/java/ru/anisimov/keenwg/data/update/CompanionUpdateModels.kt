package ru.anisimov.keenwg.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class CompanionUpdateStatus(
    val currentVersion: String,
    val supported: Boolean,
    val phase: String,
    val result: String,
    val targetVersion: String?,
    val error: String?,
)

data class CompanionUpdateAccepted(val targetVersion: String)

enum class CompanionUpdateError {
    UNAUTHORIZED, FORBIDDEN, BUSY, TOO_LARGE, UNAVAILABLE, INVALID_UPDATE, UNSUPPORTED,
}

class CompanionUpdateException(val code: CompanionUpdateError) : Exception(
    when (code) {
        CompanionUpdateError.UNAUTHORIZED -> "Router access expired"
        CompanionUpdateError.FORBIDDEN -> "Owner access is required"
        CompanionUpdateError.BUSY -> "An update is already running"
        CompanionUpdateError.TOO_LARGE -> "The update file is too large"
        CompanionUpdateError.UNAVAILABLE -> "The router update service is unavailable"
        CompanionUpdateError.INVALID_UPDATE -> "The update file was rejected"
        CompanionUpdateError.UNSUPPORTED -> "Protected updates are not supported"
    },
)

@Serializable
internal data class UpdateStatusDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("current_version") val currentVersion: String,
    val supported: Boolean,
    val phase: String = "idle",
    val result: String = "idle",
    @SerialName("target_version") val targetVersion: String? = null,
    val error: String? = null,
)

@Serializable
internal data class UpdateAcceptedDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("target_version") val targetVersion: String,
)

@Serializable
internal data class SignedUpdateManifestDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    val version: String,
    val architecture: String,
    @SerialName("archive_sha256") val archiveSha256: String,
    @SerialName("archive_size") val archiveSize: Int,
    @SerialName("binary_sha256") val binarySha256: String,
    @SerialName("key_id") val keyId: String,
    val signature: String,
)
