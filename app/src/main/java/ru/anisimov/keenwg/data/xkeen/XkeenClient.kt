package ru.anisimov.keenwg.data.xkeen

import java.net.SocketTimeoutException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings

class XkeenClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .writeTimeout(Duration.ofSeconds(10))
        .build(),
    private val urlValidator: (String) -> String? = ServerSettingsValidator::validateXkeenControllerUrl,
) : XkeenGateway {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun probe(settings: ServerSettings): XkeenStatus = status(settings)

    override suspend fun status(settings: ServerSettings): XkeenStatus = withContext(Dispatchers.IO) {
        val url = baseUrl(settings).newBuilder().addPathSegments("v1/xkeen/status").build()
        executeStatus(settings, Request.Builder().url(url).authenticated(settings).get().build())
    }

    override suspend fun refresh(settings: ServerSettings, stateVersion: Long, idempotencyKey: String): XkeenOperation =
        mutate(settings, listOf("v1", "xkeen", "subscription", "refresh"), stateVersion, idempotencyKey)

    override suspend fun select(
        settings: ServerSettings,
        nodeId: String,
        stateVersion: Long,
        idempotencyKey: String,
    ): XkeenOperation {
        if (!NODE_ID.matches(nodeId)) throw invalidSettings()
        return mutate(settings, listOf("v1", "xkeen", "nodes", nodeId, "select"), stateVersion, idempotencyKey)
    }

    override suspend fun operation(settings: ServerSettings, idempotencyKey: String): XkeenOperation = withContext(Dispatchers.IO) {
        requireOperationKey(idempotencyKey)
        val url = baseUrl(settings).newBuilder()
            .addPathSegments("v1/xkeen/operations")
            .addPathSegment(idempotencyKey)
            .build()
        executeOperation(settings, Request.Builder().url(url).authenticated(settings).get().build())
    }

    override suspend fun diagnostics(settings: ServerSettings): XkeenDiagnosticReport = withContext(Dispatchers.IO) {
        val url = baseUrl(settings).newBuilder().addPathSegments("v1/diagnostics/nodes").build()
        val request = Request.Builder()
            .url(url)
            .authenticated(settings)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request, MAX_STATUS_BYTES) { text ->
            val report = decode<XkeenDiagnosticReport>(text)
            if (!validDiagnostics(report)) throw schemaFailure()
            report
        }
    }

    private suspend fun mutate(
        settings: ServerSettings,
        path: List<String>,
        stateVersion: Long,
        idempotencyKey: String,
    ): XkeenOperation = withContext(Dispatchers.IO) {
        if (stateVersion < 0) throw invalidSettings()
        requireOperationKey(idempotencyKey)
        val builder = baseUrl(settings).newBuilder()
        path.forEach(builder::addPathSegment)
        val body = json.encodeToString(XkeenMutationRequest(stateVersion, idempotencyKey))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(builder.build()).authenticated(settings).post(body).build()
        executeOperation(settings, request)
    }

    private fun executeStatus(settings: ServerSettings, request: Request): XkeenStatus = execute(request, MAX_STATUS_BYTES) { text ->
        val status = decode<XkeenStatus>(text)
        if (!validStatus(status)) throw schemaFailure()
        status
    }

    private fun executeOperation(settings: ServerSettings, request: Request): XkeenOperation {
        validateSettings(settings)
        return execute(request, MAX_OPERATION_BYTES) { text ->
            val operation = decode<XkeenOperation>(text)
            if (!validOperation(operation)) throw schemaFailure()
            operation
        }
    }

    private fun <T> execute(request: Request, maxBytes: Long, decodeBody: (String) -> T): T {
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code)
                return decodeBody(readBounded(response, maxBytes))
            }
        } catch (known: XkeenException) {
            throw known
        } catch (timeout: SocketTimeoutException) {
            throw XkeenException(XkeenErrorCode.TIMEOUT, "Контроллер XKeen не ответил вовремя", timeout)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.NETWORK, "Связь с контроллером XKeen прервана", failure)
        }
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (failure: Exception) {
            throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема ответа контроллера не поддерживается", failure)
        }

    private fun baseUrl(settings: ServerSettings): HttpUrl {
        validateSettings(settings)
        return try {
            settings.xkeenControllerUrl.toHttpUrl()
        } catch (failure: Exception) {
            throw invalidSettings()
        }
    }

    private fun validateSettings(settings: ServerSettings) {
        val issue = urlValidator(settings.xkeenControllerUrl)
        if (settings.xkeenControllerUrl.isBlank() || issue != null) throw XkeenException(
            XkeenErrorCode.INVALID_SETTINGS,
            issue ?: "Контроллер XKeen не настроен",
        )
        if (settings.xkeenControllerToken.isBlank() || settings.xkeenControllerToken.length > 256 || settings.xkeenControllerToken.any(Char::isISOControl)) {
            throw XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Укажите токен контроллера XKeen")
        }
    }

    private fun Request.Builder.authenticated(settings: ServerSettings): Request.Builder {
        validateSettings(settings)
        return header("Authorization", "Bearer ${settings.xkeenControllerToken}")
            .header("Cache-Control", "no-store")
    }

    private fun requireOperationKey(value: String) {
        if (!OPERATION_KEY.matches(value)) throw invalidSettings()
    }

    private fun readBounded(response: Response, maxBytes: Long): String {
        val body = response.body ?: throw schemaFailure()
        if (body.contentLength() > maxBytes) throw schemaFailure()
        val source = body.source()
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) throw schemaFailure()
        return source.readUtf8()
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
        400 -> XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Контроллер отклонил запрос")
        401 -> XkeenException(XkeenErrorCode.UNAUTHORIZED, "Контроллер XKeen отклонил токен")
        404 -> XkeenException(XkeenErrorCode.NOT_FOUND, "Узел или операция не найдены")
        409 -> XkeenException(XkeenErrorCode.STALE_STATE, "Состояние XKeen изменилось; обновите статус")
        413 -> schemaFailure()
        429, 503 -> XkeenException(XkeenErrorCode.BUSY, "Контроллер XKeen занят другой операцией")
        else -> XkeenException(XkeenErrorCode.CONTROLLER_UNAVAILABLE, "Контроллер XKeen недоступен")
    }

    private fun invalidSettings() = XkeenException(XkeenErrorCode.INVALID_SETTINGS, "Некорректные параметры контроллера XKeen")
    private fun schemaFailure() = XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Схема ответа контроллера не поддерживается")

    private companion object {
        const val MAX_STATUS_BYTES = 1_048_576L
        const val MAX_OPERATION_BYTES = 4_096L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val NODE_ID = Regex("^[0-9a-f]{32}$")
        val OPERATION_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val ERROR_CODE = Regex("^[a-z0-9_]{1,64}$")
    }
}
