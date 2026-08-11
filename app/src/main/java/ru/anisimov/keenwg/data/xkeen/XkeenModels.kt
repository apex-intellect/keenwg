package ru.anisimov.keenwg.data.xkeen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class XkeenOperationState {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("terminal") TERMINAL,
}

@Serializable
enum class XkeenOperationResult {
    @SerialName("success") SUCCESS,
    @SerialName("failed_rolled_back") FAILED_ROLLED_BACK,
    @SerialName("failed_no_change") FAILED_NO_CHANGE,
    @SerialName("uncertain") UNCERTAIN,
}

@Serializable
enum class XkeenDiagnosticStatus {
    @SerialName("reachable") REACHABLE,
    @SerialName("unreachable") UNREACHABLE,
    @SerialName("timeout") TIMEOUT,
    @SerialName("dns_error") DNS_ERROR,
}

@Serializable
data class XkeenNodeDiagnostic(
    @SerialName("node_id") val nodeId: String,
    val host: String,
    val port: Int,
    @SerialName("resolved_ip") val resolvedIp: String? = null,
    @SerialName("dns_ms") val dnsMs: Long,
    @SerialName("connect_ms") val connectMs: Long,
    val status: XkeenDiagnosticStatus,
)

@Serializable
data class XkeenDiagnosticReport(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("checked_at") val checkedAt: Long,
    val results: List<XkeenNodeDiagnostic>,
)

enum class XkeenErrorCode {
    INVALID_SETTINGS,
    NETWORK,
    TIMEOUT,
    UNAUTHORIZED,
    NOT_FOUND,
    STALE_STATE,
    BUSY,
    INVALID_SUBSCRIPTION,
    UNSUPPORTED_SCHEMA,
    OPERATION_TIMEOUT,
    COMPANION_UNAVAILABLE,
}

class XkeenException(
    val code: XkeenErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class XkeenNode(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val country: String? = null,
    val flag: String? = null,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val transport: String,
    val security: String,
    val flow: String,
    val active: Boolean,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class XkeenSubscription(
    @SerialName("refreshed_at") val refreshedAt: Long? = null,
    val stale: Boolean,
    val nodes: List<XkeenNode>,
)

@Serializable
data class XkeenActiveNode(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val country: String? = null,
    val flag: String? = null,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val transport: String,
    val security: String,
    val flow: String,
    val active: Boolean,
    val warnings: List<String> = emptyList(),
    @SerialName("resolved_ip") val resolvedIp: String,
    @SerialName("confirmed_at") val confirmedAt: Long,
    @SerialName("missing_from_subscription") val missingFromSubscription: Boolean,
)

@Serializable
data class XkeenOperation(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val kind: String,
    val state: XkeenOperationState,
    val result: XkeenOperationResult? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("finished_at") val finishedAt: Long? = null,
)

@Serializable
data class XkeenStatus(
    val version: String,
    @SerialName("state_version") val stateVersion: Long,
    val active: XkeenActiveNode? = null,
    val subscription: XkeenSubscription,
    val operation: XkeenOperation? = null,
)

@Serializable
internal data class XkeenMutationRequest(
    @SerialName("state_version") val stateVersion: Long,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

interface XkeenGateway {
    suspend fun probe(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint): XkeenStatus
    suspend fun status(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint): XkeenStatus
    suspend fun refresh(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint, stateVersion: Long, idempotencyKey: String): XkeenOperation
    suspend fun select(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint, nodeId: String, stateVersion: Long, idempotencyKey: String): XkeenOperation
    suspend fun operation(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint, idempotencyKey: String): XkeenOperation
    suspend fun diagnostics(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint): XkeenDiagnosticReport =
        throw UnsupportedOperationException("Diagnostics are unavailable")
}
