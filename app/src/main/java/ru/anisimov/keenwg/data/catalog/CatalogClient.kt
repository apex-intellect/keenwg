package ru.anisimov.keenwg.data.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile

interface CatalogGateway {
    suspend fun snapshot(profile: RouterProfile, token: String): CatalogDocument
    suspend fun createGroup(profile: RouterProfile, token: String, stateVersion: ULong, key: String, label: String): CatalogOperation
    suspend fun saveSource(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String,
        draft: CatalogSourceDraft, source: ByteArray,
    ): CatalogOperation
    suspend fun deleteSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String): CatalogOperation
    suspend fun refreshSource(profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String): CatalogOperation
    suspend fun testNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String): CatalogOperation
    suspend fun activateNode(profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String): CatalogOperation
}

class CatalogClient(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true },
) : CatalogGateway {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    override suspend fun snapshot(profile: RouterProfile, token: String): CatalogDocument = withContext(Dispatchers.IO) {
        decodeDocument(execute(profile, "/v1/connections/catalog", token = requireToken(token)))
    }

    override suspend fun createGroup(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, label: String,
    ): CatalogOperation = withContext(Dispatchers.IO) {
        requireMutation(key)
        val body = json.encodeToString(GroupRequest(stateVersion = stateVersion, idempotencyKey = key, label = label))
        decodeOperation(execute(profile, "/v1/connections/groups", "POST", requireToken(token), body, mutation = true))
    }

    override suspend fun saveSource(
        profile: RouterProfile,
        token: String,
        stateVersion: ULong,
        key: String,
        draft: CatalogSourceDraft,
        source: ByteArray,
    ): CatalogOperation = withContext(Dispatchers.IO) {
        try {
            requireMutation(key)
            if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) throw CatalogException(CatalogErrorCode.PAYLOAD_TOO_LARGE)
            val sourceText = decodeUtf8(source)
            val body = json.encodeToString(SourceRequest(
                stateVersion = stateVersion, idempotencyKey = key, groupId = draft.groupId,
                kind = draft.kind, label = draft.label, adapterId = draft.adapterId, source = sourceText,
            ))
            decodeOperation(execute(profile, "/v1/connections/sources", "POST", requireToken(token), body, mutation = true))
        } finally {
            source.fill(0)
        }
    }

    override suspend fun deleteSource(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String,
    ): CatalogOperation = withContext(Dispatchers.IO) {
        requireMutation(key)
        if (!ID.matches(sourceId)) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        val body = json.encodeToString(MutationRequest(stateVersion = stateVersion, idempotencyKey = key))
        decodeOperation(execute(profile, "/v1/connections/sources/$sourceId", "DELETE", requireToken(token), body, mutation = true))
    }

    override suspend fun refreshSource(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, sourceId: String,
    ): CatalogOperation = connectionOperation(profile, token, stateVersion, key, sourceId, "sources", "refresh")

    override suspend fun testNode(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String,
    ): CatalogOperation = connectionOperation(profile, token, stateVersion, key, nodeId, "nodes", "test")

    override suspend fun activateNode(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, nodeId: String,
    ): CatalogOperation = connectionOperation(profile, token, stateVersion, key, nodeId, "nodes", "activate")

    private suspend fun connectionOperation(
        profile: RouterProfile, token: String, stateVersion: ULong, key: String, id: String, collection: String, action: String,
    ): CatalogOperation = withContext(Dispatchers.IO) {
        requireMutation(key)
        if (!ID.matches(id)) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        val body = json.encodeToString(MutationRequest(stateVersion = stateVersion, idempotencyKey = key))
        decodeOperation(execute(profile, "/v1/connections/$collection/$id/$action", "POST", requireToken(token), body, mutation = true))
    }

    private fun execute(
        profile: RouterProfile,
        path: String,
        method: String = "GET",
        token: String,
        body: String? = null,
        mutation: Boolean = false,
    ): String {
        val base = validatedBaseUrl(profile)
        val url = base.resolve(path) ?: throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("KeenWG-Catalog-Features", "subscription-metadata-v1")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer $token")
            .method(method, body?.toRequestBody(JSON_MEDIA_TYPE)).build()
        val response = try {
            client(profile, base).newCall(request).execute()
        } catch (_: IOException) {
            throw CatalogException(CatalogErrorCode.UNAVAILABLE)
        }
        response.use {
            val responseBody = readBounded(it)
            if (!it.isSuccessful &&
                !(mutation && it.code in setOf(409, 503) && responseBody.trimStart().startsWith("{\"schema_version\""))
            ) {
                throw statusFailure(it.code)
            }
            return responseBody
        }
    }

    private fun client(profile: RouterProfile, base: HttpUrl): OkHttpClient {
        val key = ClientKey(base.scheme, base.host, base.port, profile.certificatePin)
        return clients.getOrPut(key) {
            val trust = try { ExactPinTrustManager(profile.certificatePin) } catch (_: IllegalArgumentException) {
                throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        }
    }

    private fun validatedBaseUrl(profile: RouterProfile): HttpUrl {
        val url = profile.companionUrl.toHttpUrlOrNull() ?: throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        if (url.scheme != "https" || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
            url.query != null || url.fragment != null || (url.encodedPath != "/" && url.encodedPath.isNotEmpty())
        ) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        return url
    }

    private fun decodeDocument(body: String): CatalogDocument = decode<CatalogDocument>(body).also(::validate)

    private fun decodeOperation(body: String): CatalogOperation = decode<CatalogOperation>(body).also {
        if (it.schemaVersion != SCHEMA_VERSION || it.result !in RESULTS || (it.result == "committed" && it.catalog == null)) schemaFailure()
        it.catalog?.let(::validate)
        it.test?.let { test ->
            if (!ID.matches(test.nodeId) || test.latencyMs < 0 || test.observedAt.isBlank()) schemaFailure()
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
    } catch (_: IllegalArgumentException) {
        throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
    }

    private fun validate(document: CatalogDocument) {
        val groupIds = document.groups.map { it.id }.toSet()
        val sourceIds = document.sources.map { it.id }.toSet()
        val sourceGroups = document.sources.associate { it.id to it.groupId }
        if (document.schemaVersion != SCHEMA_VERSION || document.groups.size > 64 || document.sources.size > 128 ||
            document.nodes.size > 4096 || document.groups.any { !ID.matches(it.id) || it.label.isBlank() || it.order < 0 } ||
            groupIds.size != document.groups.size ||
            document.sources.any { !ID.matches(it.id) || it.groupId !in groupIds || !ID.matches(it.adapterId) || it.label.isBlank() || it.nodeCount < 0 } ||
            sourceIds.size != document.sources.size ||
            document.nodes.any { !ID.matches(it.id) || it.sourceId !in sourceIds || it.groupId !in groupIds || sourceGroups[it.sourceId] != it.groupId || it.displayName.isBlank() || it.host.isBlank() || it.port !in 1..65535 } ||
            document.nodes.map { it.id }.toSet().size != document.nodes.size
        ) schemaFailure()
    }

    private fun decodeUtf8(source: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString()
    } catch (_: Exception) {
        throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
    }

    private fun readBounded(response: Response): String {
        val body = response.body ?: schemaFailure()
        if (body.contentLength() > MAX_BODY_BYTES) throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
        val output = ByteArrayOutputStream()
        val input = body.byteStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BODY_BYTES) throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun statusFailure(status: Int) = CatalogException(when (status) {
        401 -> CatalogErrorCode.UNAUTHORIZED
        403 -> CatalogErrorCode.FORBIDDEN
        404 -> CatalogErrorCode.NOT_FOUND
        409 -> CatalogErrorCode.CONFLICT
        413 -> CatalogErrorCode.PAYLOAD_TOO_LARGE
        in 500..599 -> CatalogErrorCode.UNAVAILABLE
        else -> CatalogErrorCode.PROTOCOL
    })

    private fun requireToken(value: String): String {
        if (value.isBlank() || value.any { it.isWhitespace() }) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
        return value
    }

    private fun requireMutation(key: String) {
        if (!OPERATION_KEY.matches(key)) throw CatalogException(CatalogErrorCode.INVALID_SETTINGS)
    }

    private fun schemaFailure(): Nothing = throw CatalogException(CatalogErrorCode.UNSUPPORTED_SCHEMA)

    @Serializable private data class MutationRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        @SerialName("state_version") val stateVersion: ULong,
        @SerialName("idempotency_key") val idempotencyKey: String,
    )
    @Serializable private data class GroupRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        @SerialName("state_version") val stateVersion: ULong,
        @SerialName("idempotency_key") val idempotencyKey: String,
        val label: String,
    )
    @Serializable private data class SourceRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        @SerialName("state_version") val stateVersion: ULong,
        @SerialName("idempotency_key") val idempotencyKey: String,
        @SerialName("group_id") val groupId: String,
        val kind: SourceKind,
        val label: String,
        @SerialName("adapter_id") val adapterId: String,
        val source: String,
    )

    private data class ClientKey(val scheme: String, val host: String, val port: Int, val pin: String)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BODY_BYTES = 1_048_576L
        const val MAX_SOURCE_BYTES = 1_000_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val OPERATION_KEY = Regex("[A-Za-z0-9_-]{8,128}")
        val RESULTS = setOf("committed", "rejected", "rolled_back", "uncertain")
    }
}
