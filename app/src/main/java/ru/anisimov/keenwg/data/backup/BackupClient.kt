package ru.anisimov.keenwg.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile

@Serializable
data class BackupPreviewEntry(
    val id: String,
    val bytes: Int,
    val owned: Boolean,
)

@Serializable
data class BackupPreview(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("plan_id") val planId: String,
    @SerialName("source_version") val sourceVersion: String,
    val entries: List<BackupPreviewEntry>,
)

@Serializable
private data class BackupRequest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val archive: String? = null,
    val passphrase: String,
    @SerialName("reviewed_plan_id") val reviewedPlanId: String? = null,
)

@Serializable
private data class BackupCreateResponse(
    @SerialName("schema_version") val schemaVersion: Int,
    val archive: String,
    val preview: BackupPreview,
)

@Serializable
data class BackupApplyResult(
    val applied: List<String>,
    @SerialName("skipped_foreign") val skippedForeign: List<String>,
)

data class BackupExport(val archive: ByteArray, val preview: BackupPreview)

interface BackupGateway {
    suspend fun create(profile: RouterProfile, token: String, passphrase: String): BackupExport
    suspend fun preview(profile: RouterProfile, token: String, archive: ByteArray, passphrase: String): BackupPreview
    suspend fun apply(
        profile: RouterProfile,
        token: String,
        archive: ByteArray,
        passphrase: String,
        reviewedPlanId: String,
    ): BackupApplyResult
}

class BackupClient(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false },
) : BackupGateway {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    override suspend fun create(profile: RouterProfile, token: String, passphrase: String): BackupExport =
        withContext(Dispatchers.IO) {
            val response = post<BackupCreateResponse>(
                profile,
                token,
                "/v1/backup",
                BackupRequest(passphrase = validPassphrase(passphrase)),
            )
            require(response.schemaVersion == SCHEMA_VERSION)
            val archive = decodeArchive(response.archive)
            validatePreview(response.preview)
            BackupExport(archive, response.preview)
        }

    override suspend fun preview(
        profile: RouterProfile,
        token: String,
        archive: ByteArray,
        passphrase: String,
    ): BackupPreview = withContext(Dispatchers.IO) {
        requireArchive(archive)
        post<BackupPreview>(
            profile,
            token,
            "/v1/backup/preview",
            BackupRequest(
                archive = Base64.getEncoder().encodeToString(archive),
                passphrase = validPassphrase(passphrase),
            ),
        ).also(::validatePreview)
    }

    override suspend fun apply(
        profile: RouterProfile,
        token: String,
        archive: ByteArray,
        passphrase: String,
        reviewedPlanId: String,
    ): BackupApplyResult = withContext(Dispatchers.IO) {
        requireArchive(archive)
        require(PLAN_ID.matches(reviewedPlanId))
        post<BackupApplyResult>(
            profile,
            token,
            "/v1/backup/apply",
            BackupRequest(
                archive = Base64.getEncoder().encodeToString(archive),
                passphrase = validPassphrase(passphrase),
                reviewedPlanId = reviewedPlanId,
            ),
        ).also(::validateApplyResult)
    }

    private inline fun <reified T> post(
        profile: RouterProfile,
        token: String,
        path: String,
        payload: BackupRequest,
    ): T {
        require(token.isNotBlank() && token.length <= 512)
        val base = base(profile)
        val body = json.encodeToString(payload).toRequestBody(JSON)
        val request = Request.Builder()
            .url(requireNotNull(base.resolve(path)))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        val response = try {
            client(profile, base).newCall(request).execute()
        } catch (_: IOException) {
            throw IllegalStateException("Backup service unavailable")
        }
        response.use {
            if (!it.isSuccessful) error("Backup operation failed")
            val raw = readBounded(requireNotNull(it.body).byteStream())
            return try {
                json.decodeFromString<T>(raw.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                throw IllegalStateException("Unsupported backup response")
            }
        }
    }

    private fun validatePreview(value: BackupPreview) {
        require(value.schemaVersion == SCHEMA_VERSION)
        require(PLAN_ID.matches(value.planId))
        require(VERSION.matches(value.sourceVersion))
        require(value.entries.size <= MAX_ENTRIES)
        require(value.entries.all { ID.matches(it.id) && it.bytes in 0..MAX_ARCHIVE })
    }

    private fun validateApplyResult(value: BackupApplyResult) {
        require(value.applied.size + value.skippedForeign.size <= MAX_ENTRIES)
        require((value.applied + value.skippedForeign).all(ID::matches))
    }

    private fun validPassphrase(value: String): String = value.also { require(it.length in 8..1024) }

    private fun decodeArchive(value: String): ByteArray = try {
        Base64.getDecoder().decode(value).also(::requireArchive)
    } catch (_: Exception) {
        throw IllegalStateException("Invalid backup archive")
    }

    private fun requireArchive(value: ByteArray) {
        require(value.size in 1..MAX_ARCHIVE)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_RESPONSE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun base(profile: RouterProfile): HttpUrl {
        val url = profile.companionUrl.toHttpUrlOrNull() ?: error("Companion not configured")
        require(url.scheme == "https")
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty())
        require(url.query == null && url.fragment == null)
        require(url.encodedPath == "/" || url.encodedPath.isEmpty())
        return url
    }

    private fun client(profile: RouterProfile, base: HttpUrl): OkHttpClient =
        clients.getOrPut(ClientKey(base.host, base.port, profile.certificatePin)) {
            val trust = ExactPinTrustManager(profile.certificatePin)
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trust), SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trust)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .build()
        }

    private data class ClientKey(val host: String, val port: Int, val pin: String)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ARCHIVE = 4 * 1024 * 1024
        const val MAX_RESPONSE = 6 * 1024 * 1024
        const val MAX_ENTRIES = 64
        val JSON = "application/json".toMediaType()
        val PLAN_ID = Regex("^backup-[0-9a-f]{24}$")
        val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        val ID = Regex("^[a-z0-9._-]{1,64}$")
    }
}
