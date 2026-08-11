package ru.anisimov.keenwg.data.xkeen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException

class XkeenClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
) : XkeenGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun probe(endpoint: CompanionEndpoint): XkeenStatus = status(endpoint)

    override suspend fun status(endpoint: CompanionEndpoint): XkeenStatus = withContext(Dispatchers.IO) {
        executeStatus(endpoint)
    }

    override suspend fun refresh(endpoint: CompanionEndpoint, stateVersion: Long, idempotencyKey: String): XkeenOperation =
        mutate(endpoint, "/v1/xkeen/subscription/refresh", stateVersion, idempotencyKey)

    override suspend fun select(
        endpoint: CompanionEndpoint,
        nodeId: String,
        stateVersion: Long,
        idempotencyKey: String,
    ): XkeenOperation {
        if (!NODE_ID.matches(nodeId)) throw invalidSettings()
        return mutate(endpoint, "/v1/xkeen/nodes/$nodeId/select", stateVersion, idempotencyKey)
    }

    override suspend fun operation(endpoint: CompanionEndpoint, idempotencyKey: String): XkeenOperation = withContext(Dispatchers.IO) {
        requireOperationKey(idempotencyKey)
        executeOperation(endpoint, "/v1/xkeen/operations/$idempotencyKey")
    }

    override suspend fun diagnostics(endpoint: CompanionEndpoint): XkeenDiagnosticReport = withContext(Dispatchers.IO) {
        execute(endpoint, "/v1/diagnostics/nodes", "POST", "{}", MAX_STATUS_BYTES) { text ->
            val report = decode<XkeenDiagnosticReport>(text)
            if (!validDiagnostics(report)) throw schemaFailure()
            report
        }
    }

    private suspend fun mutate(
        endpoint: CompanionEndpoint,
        path: String,
        stateVersion: Long,
        idempotencyKey: String,
    ): XkeenOperation = withContext(Dispatchers.IO) {
        if (stateVersion < 0) throw invalidSettings()
        requireOperationKey(idempotencyKey)
        val body = json.encodeToString(XkeenMutationRequest(stateVersion, idempotencyKey))
        executeOperation(endpoint, path, "POST", body)
    }

    private fun executeStatus(endpoint: CompanionEndpoint): XkeenStatus = execute(endpoint, "/v1/xkeen/status", maxBytes = MAX_STATUS_BYTES) { text ->
        val status = decode<XkeenStatus>(text)
        if (!validStatus(status)) throw schemaFailure()
        status
    }

    private fun executeOperation(endpoint: CompanionEndpoint, path: String, method: String = "GET", body: String? = null): XkeenOperation {
        return execute(endpoint, path, method, body, MAX_OPERATION_BYTES) { text ->
            val operation = decode<XkeenOperation>(text)
            if (!validOperation(operation)) throw schemaFailure()
            operation
        }
    }

    private fun <T> execute(
        endpoint: CompanionEndpoint,
        path: String,
        method: String = "GET",
        body: String? = null,
        maxBytes: Long,
        decodeBody: (String) -> T,
    ): T {
        try {
            val response = transport.execute(endpoint, path, method, body, maxBytes)
            if (response.status !in 200..299) throw httpFailure(response.status)
            return decodeBody(response.body)
        } catch (known: XkeenException) {
            throw known
        } catch (failure: CompanionResponseTooLargeException) {
            throw schemaFailure()
        } catch (failure: CompanionTransportException) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с Companion прервана", failure)
        }
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема ответа Companion не поддерживается", failure)
        }

    private fun requireOperationKey(value: String) {
        if (!OPERATION_KEY.matches(value)) throw invalidSettings()
    }

    private fun validStatus(status: XkeenStatus): Boolean {
        if (status.version.isBlank() || status.stateVersion < 0 || status.subscription.refreshedAt?.let { it < 0 } == true) return false
        if (status.subscription.nodes.map { it.id }.toSet().size != status.subscription.nodes.size) return false
        if (status.subscription.nodes.any { !validNode(it) }) return false
        val active = status.active
        if (active != null && (!validPublicFields(active.id, active.displayName, active.host, active.port, active.fingerprint, active.transport, active.security, active.flow) ||
                active.resolvedIp.isBlank() || active.confirmedAt < 0 || !active.active)) return false
        return status.operation?.let(::validOperation) != false
    }

    private fun validNode(node: XkeenNode): Boolean =
        NODE_ID.matches(node.id) && validPublicFields(
            node.id, node.displayName, node.host, node.port, node.fingerprint, node.transport, node.security, node.flow,
        )

    private fun validPublicFields(
        id: String,
        displayName: String,
        host: String,
        port: Int,
        fingerprint: String,
        transport: String,
        security: String,
        flow: String,
    ): Boolean = (id.isEmpty() || NODE_ID.matches(id)) && displayName.isNotBlank() && host.isNotBlank() && port in 1..65535 &&
        fingerprint.isNotBlank() && transport == "tcp" && security == "reality" && flow == "xtls-rprx-vision"

    private fun validOperation(operation: XkeenOperation): Boolean {
        if (!OPERATION_KEY.matches(operation.idempotencyKey) || operation.kind.isBlank() || operation.startedAt < 0) return false
        if (operation.errorCode?.let { !ERROR_CODE.matches(it) } == true) return false
        return when (operation.state) {
            XkeenOperationState.TERMINAL -> operation.result != null && operation.finishedAt != null && operation.finishedAt >= operation.startedAt
            XkeenOperationState.QUEUED, XkeenOperationState.RUNNING -> operation.result == null && operation.finishedAt == null
        }
    }

    private fun validDiagnostics(report: XkeenDiagnosticReport): Boolean =
        report.schemaVersion == 1 && report.checkedAt >= 0 &&
            report.results.map { it.nodeId }.toSet().size == report.results.size &&
            report.results.all { result ->
                NODE_ID.matches(result.nodeId) && result.host.isNotBlank() && result.port in 1..65535 &&
                    result.dnsMs >= 0 && result.connectMs >= 0 &&
                    (result.resolvedIp == null || result.resolvedIp.isNotBlank())
            }

    private fun httpFailure(status: Int): XkeenException = when (status) {
        400 -> XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Companion отклонил запрос")
        401 -> XkeenException(XkeenErrorCode.UNAUTHORIZED, "Companion отклонил токен устройства")
        404 -> XkeenException(XkeenErrorCode.NOT_FOUND, "Узел или операция не найдены")
        409 -> XkeenException(XkeenErrorCode.STALE_STATE, "Состояние XKeen изменилось; обновите статус")
        413 -> schemaFailure()
        429, 503 -> XkeenException(XkeenErrorCode.BUSY, "Companion занят другой операцией")
        else -> XkeenException(XkeenErrorCode.COMPANION_UNAVAILABLE, "Companion недоступен")
    }

    private fun invalidSettings() = XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Некорректные параметры Companion")
    private fun schemaFailure() = XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема ответа Companion не поддерживается")

    private companion object {
        const val MAX_STATUS_BYTES = 1_048_576L
        const val MAX_OPERATION_BYTES = 4_096L
        val NODE_ID = Regex("^[0-9a-f]{32}$")
        val OPERATION_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val ERROR_CODE = Regex("^[a-z0-9_]{1,64}$")
    }
}
