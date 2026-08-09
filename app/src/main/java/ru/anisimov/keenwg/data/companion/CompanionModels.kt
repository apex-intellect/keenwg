package ru.anisimov.keenwg.data.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CapabilityAccess {
    @SerialName("none") NONE,
    @SerialName("read") READ,
    @SerialName("write") WRITE,
}

@Serializable
data class Capability(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val access: CapabilityAccess,
    val available: Boolean,
    val transport: String,
    val reason: String? = null,
)

@Serializable
data class CapabilityDocument(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("state_version") val stateVersion: ULong = 0u,
    val capabilities: List<Capability>,
)

@Serializable
enum class DeviceScope {
    @SerialName("viewer") VIEWER,
    @SerialName("operator") OPERATOR,
    @SerialName("owner") OWNER,
}

@Serializable
data class PairingCredential(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("device_id") val deviceId: String,
    val scope: DeviceScope,
    val token: String,
)

@Serializable
data class PairingOffer(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("offer_id") val offerId: String,
    val secret: String,
    val scope: DeviceScope,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class CompanionHealth(
    val status: String = "ok",
    val storage: String = "ok",
    val version: String,
)

@Serializable
data class PairedDevice(
    val id: String,
    val label: String,
    val scope: DeviceScope,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used") val lastUsed: String? = null,
)

enum class CompanionErrorCode {
    INVALID_SETTINGS,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    UNAVAILABLE,
    UNSUPPORTED_SCHEMA,
    PROTOCOL,
}

class CompanionException(
    val code: CompanionErrorCode,
    cause: Throwable? = null,
) : Exception("Companion request failed: ${code.name.lowercase()}", cause)

internal const val SCHEMA_VERSION = 1

internal fun CapabilityDocument.requireSupported(): CapabilityDocument {
    if (schemaVersion != SCHEMA_VERSION ||
        capabilities.any { it.schemaVersion != SCHEMA_VERSION || it.id.isBlank() } ||
        capabilities.map { it.id }.toSet().size != capabilities.size
    ) {
        throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA)
    }
    return this
}
