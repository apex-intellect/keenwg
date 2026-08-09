package ru.anisimov.keenwg.data.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.anisimov.keenwg.domain.model.RouterProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

interface CompanionClient {
    suspend fun exchange(profile: RouterProfile, offerId: String, secret: String, label: String): PairingCredential
    suspend fun health(profile: RouterProfile): CompanionHealth = error("Health is not implemented")
    suspend fun capabilities(profile: RouterProfile, deviceToken: String): CapabilityDocument
    suspend fun devices(profile: RouterProfile, deviceToken: String): List<PairedDevice>
    suspend fun createOffer(profile: RouterProfile, deviceToken: String, scope: DeviceScope): PairingOffer = error("Pairing offers are not implemented")
    suspend fun revokeOffer(profile: RouterProfile, deviceToken: String, offerId: String): Unit = error("Pairing offer revocation is not implemented")
    suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String)
}

class HttpCompanionClient(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    },
) : CompanionClient {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    override suspend fun exchange(
        profile: RouterProfile,
        offerId: String,
        secret: String,
        label: String,
    ): PairingCredential = withContext(Dispatchers.IO) {
        requireField(offerId)
        requireField(secret)
        requireField(label)
        val response = execute(
            profile = profile,
            path = "/v1/pairing/exchange",
            method = "POST",
            body = json.encodeToString(PairingRequest(offerId = offerId, secret = secret, deviceLabel = label)),
        )
        decode<PairingCredential>(response).also { requireSchema(it.schemaVersion) }
    }

    override suspend fun capabilities(profile: RouterProfile, deviceToken: String): CapabilityDocument =
        withContext(Dispatchers.IO) {
            decode<CapabilityDocument>(execute(profile, "/v1/capabilities", token = requireField(deviceToken)))
                .requireSupported()
        }

    override suspend fun health(profile: RouterProfile): CompanionHealth = withContext(Dispatchers.IO) {
        decode<CompanionHealth>(execute(profile, "/v1/health")).also {
            if (it.status != "ok" || it.storage != "ok" || it.version.isBlank()) {
                throw CompanionException(CompanionErrorCode.PROTOCOL)
            }
        }
    }

    override suspend fun devices(profile: RouterProfile, deviceToken: String): List<PairedDevice> = withContext(Dispatchers.IO) {
        val document = decode<DeviceDocument>(
            execute(profile, "/v1/devices", token = requireField(deviceToken)),
        )
        requireSchema(document.schemaVersion)
        document.devices
    }

    override suspend fun revokeDevice(profile: RouterProfile, deviceToken: String, deviceId: String) = withContext(Dispatchers.IO) {
        require(deviceId.matches(DEVICE_ID)) { "Invalid device identifier" }
        execute(
            profile = profile,
            path = "/v1/devices/$deviceId",
            method = "DELETE",
            token = requireField(deviceToken),
            expectBody = false,
        )
        Unit
    }

    override suspend fun createOffer(
        profile: RouterProfile,
        deviceToken: String,
        scope: DeviceScope,
    ): PairingOffer = withContext(Dispatchers.IO) {
        val offer = decode<PairingOffer>(
            execute(
                profile = profile,
                path = "/v1/pairing/offers",
                method = "POST",
                token = requireField(deviceToken),
                body = json.encodeToString(PairingOfferRequest(scope = scope)),
            ),
        )
        requireSchema(offer.schemaVersion)
        if (offer.offerId.isBlank() || offer.secret.isBlank() || offer.scope != scope || offer.expiresAt.isBlank()) {
            throw CompanionException(CompanionErrorCode.PROTOCOL)
        }
        offer
    }

    override suspend fun revokeOffer(profile: RouterProfile, deviceToken: String, offerId: String) = withContext(Dispatchers.IO) {
        require(offerId.matches(DEVICE_ID)) { "Invalid offer identifier" }
        execute(
            profile = profile,
            path = "/v1/pairing/offers/$offerId",
            method = "DELETE",
            token = requireField(deviceToken),
            expectBody = false,
        )
        Unit
    }

    private fun execute(
        profile: RouterProfile,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
        expectBody: Boolean = true,
    ): String {
        val base = validatedBaseUrl(profile)
        val url = base.resolve(path) ?: throw CompanionException(CompanionErrorCode.INVALID_SETTINGS)
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, requestBody)
            .build()
        val response = try {
            client(profile, base).newCall(request).execute()
        } catch (failure: IOException) {
            throw CompanionException(CompanionErrorCode.UNAVAILABLE, failure)
        }
        response.use {
            if (!it.isSuccessful) throw statusFailure(it.code)
            if (!expectBody) {
                if (it.code != 204) throw CompanionException(CompanionErrorCode.PROTOCOL)
                return ""
            }
            return readBounded(it)
        }
    }

    private fun client(profile: RouterProfile, base: HttpUrl): OkHttpClient {
        val key = ClientKey(base.scheme, base.host, base.port, profile.certificatePin)
        return clients.getOrPut(key) {
            val trustManager = try {
                ExactPinTrustManager(profile.certificatePin)
            } catch (failure: IllegalArgumentException) {
                throw CompanionException(CompanionErrorCode.INVALID_SETTINGS, failure)
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trustManager)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun validatedBaseUrl(profile: RouterProfile): HttpUrl {
        val url = profile.companionUrl.toHttpUrlOrNull()
            ?: throw CompanionException(CompanionErrorCode.INVALID_SETTINGS)
        if (url.scheme != "https" || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
            url.query != null || url.fragment != null || (url.encodedPath != "/" && url.encodedPath.isNotEmpty())
        ) {
            throw CompanionException(CompanionErrorCode.INVALID_SETTINGS)
        }
        return url
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (failure: SerializationException) {
        throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA, failure)
    } catch (failure: IllegalArgumentException) {
        throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA, failure)
    }

    private fun readBounded(response: Response): String {
        val body = response.body ?: throw CompanionException(CompanionErrorCode.PROTOCOL)
        if (body.contentLength() > MAX_BODY_BYTES) throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA)
        val input = body.byteStream()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BODY_BYTES) throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA)
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun statusFailure(status: Int) = CompanionException(
        when (status) {
            401 -> CompanionErrorCode.UNAUTHORIZED
            403 -> CompanionErrorCode.FORBIDDEN
            404 -> CompanionErrorCode.NOT_FOUND
            409 -> CompanionErrorCode.CONFLICT
            429 -> CompanionErrorCode.RATE_LIMITED
            in 500..599 -> CompanionErrorCode.UNAVAILABLE
            else -> CompanionErrorCode.PROTOCOL
        },
    )

    private fun requireSchema(schemaVersion: Int) {
        if (schemaVersion != SCHEMA_VERSION) throw CompanionException(CompanionErrorCode.UNSUPPORTED_SCHEMA)
    }

    private fun requireField(value: String): String {
        if (value.isBlank()) throw CompanionException(CompanionErrorCode.INVALID_SETTINGS)
        return value
    }

    @Serializable
    private data class PairingRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        @SerialName("offer_id") val offerId: String,
        val secret: String,
        @SerialName("device_label") val deviceLabel: String,
    )

    @Serializable
    private data class PairingOfferRequest(
        @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
        val scope: DeviceScope,
    )

    @Serializable
    private data class DeviceDocument(
        @SerialName("schema_version") val schemaVersion: Int,
        val devices: List<PairedDevice>,
    )

    private data class ClientKey(val scheme: String, val host: String, val port: Int, val pin: String)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        const val MAX_BODY_BYTES = 1_048_576
    }
}
