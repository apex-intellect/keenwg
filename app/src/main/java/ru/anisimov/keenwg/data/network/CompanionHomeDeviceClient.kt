package ru.anisimov.keenwg.data.network

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CompanionEndpoint
import ru.anisimov.keenwg.data.companion.CompanionHttpTransport
import ru.anisimov.keenwg.data.companion.CompanionResponseTooLargeException
import ru.anisimov.keenwg.data.companion.CompanionTransportException
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException

@Serializable
data class CompanionHomeDevice(
    val id: String,
    val mac: String,
    val name: String,
    val hostname: String? = null,
    val ip: String? = null,
    @SerialName("reserved_ip") val reservedIp: String? = null,
    val online: Boolean,
    @SerialName("static_reservation") val staticReservation: Boolean,
    @SerialName("interface_name") val interfaceName: String? = null,
    val rssi: Int? = null,
) {
    fun asNetworkDevice() = NetworkDevice(mac, name, hostname, ip, reservedIp, online, staticReservation, interfaceName, rssi)
}

@Serializable
data class CompanionHomeDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("state_version") val stateVersion: String,
    val devices: List<CompanionHomeDevice>,
)

@Serializable
data class CompanionReservationPlan(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("plan_id") val planId: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("state_version") val stateVersion: String,
    val mac: String,
    @SerialName("before_ip") val beforeIp: String? = null,
    @SerialName("after_ip") val afterIp: String? = null,
)

@Serializable
data class CompanionHomeMutationResult(
    @SerialName("schema_version") val schemaVersion: Int,
    val status: String,
    val code: String? = null,
    val home: CompanionHomeDocument? = null,
)

interface CompanionHomeDeviceGateway {
    suspend fun load(endpoint: CompanionEndpoint): CompanionHomeDocument
    suspend fun review(endpoint: CompanionEndpoint, stateVersion: String, deviceId: String, reservedIp: String?): CompanionReservationPlan
    suspend fun apply(endpoint: CompanionEndpoint, stateVersion: String, deviceId: String, reservedIp: String?, planId: String): CompanionHomeMutationResult
}

class CompanionHomeDeviceClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : CompanionHomeDeviceGateway {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = false
    }

    override suspend fun load(endpoint: CompanionEndpoint): CompanionHomeDocument = withContext(Dispatchers.IO) {
        execute(endpoint, "/v1/network/devices", "GET", null) { decodeDocument(it) }
    }

    override suspend fun review(
        endpoint: CompanionEndpoint,
        stateVersion: String,
        deviceId: String,
        reservedIp: String?,
    ): CompanionReservationPlan = withContext(Dispatchers.IO) {
        requireDeviceID(deviceId)
        requireReservationInput(stateVersion, reservedIp)
        val body = json.encodeToString(ReservationReviewBody(1, stateVersion, reservedIp))
        execute(endpoint, "/v1/network/devices/$deviceId/reservation/review", "POST", body) { text ->
            decode<CompanionReservationPlan>(text).also { plan ->
                if (plan.schemaVersion != 1 || plan.planId.isBlank() || plan.stateVersion != stateVersion || plan.expiresAt.isBlank()) schemaFailure()
            }
        }
    }

    override suspend fun apply(
        endpoint: CompanionEndpoint,
        stateVersion: String,
        deviceId: String,
        reservedIp: String?,
        planId: String,
    ): CompanionHomeMutationResult = withContext(Dispatchers.IO) {
        requireDeviceID(deviceId)
        requireReservationInput(stateVersion, reservedIp)
        if (planId.isBlank()) schemaFailure()
        val key = keyFactory()
        if (!UUID_PATTERN.matches(key)) schemaFailure()
        val body = json.encodeToString(ReservationApplyBody(1, stateVersion, reservedIp, planId, key))
        execute(endpoint, "/v1/network/devices/$deviceId/reservation/apply", "POST", body) { text ->
            decode<CompanionHomeMutationResult>(text).also(::requireValidResult)
        }
    }

    private fun decodeDocument(text: String): CompanionHomeDocument =
        decode<CompanionHomeDocument>(text).also(::requireValidDocument)

    private fun requireValidDocument(document: CompanionHomeDocument) {
        if (document.schemaVersion != 1 || document.stateVersion.isBlank() || document.devices.size > 1024) schemaFailure()
        if (document.devices.map { it.id }.toSet().size != document.devices.size ||
            document.devices.map { it.mac }.toSet().size != document.devices.size
        ) schemaFailure()
        document.devices.forEach { device ->
            if (!DEVICE_ID.matches(device.id) || !MAC.matches(device.mac) || device.name.isBlank() || device.name.length > 128 ||
                device.hostname.orEmpty().length > 253 || device.interfaceName.orEmpty().length > 64 ||
                listOfNotNull(device.ip, device.reservedIp).any { !canonicalIpv4(it) }
            ) schemaFailure()
        }
    }

    private fun requireValidResult(result: CompanionHomeMutationResult) {
        if (result.schemaVersion != 1 || result.status !in TERMINAL_RESULTS) schemaFailure()
        if (result.status == "committed" && result.home == null) schemaFailure()
        result.home?.let(::requireValidDocument)
    }

    private fun <T> execute(endpoint: CompanionEndpoint, path: String, method: String, body: String?, decoder: (String) -> T): T = try {
        val response = transport.execute(endpoint, path, method, body, MAX_BYTES)
        if (response.status !in 200..299) httpFailure(response.status)
        decoder(response.body)
    } catch (known: XkeenException) {
        throw known
    } catch (_: CompanionResponseTooLargeException) {
        schemaFailure()
    } catch (failure: CompanionTransportException) {
        throw XkeenException(XkeenErrorCode.NETWORK, "Companion connection failed", failure)
    } catch (_: IllegalArgumentException) {
        schemaFailure()
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        schemaFailure()
    }

    private fun requireDeviceID(value: String) {
        if (!DEVICE_ID.matches(value)) schemaFailure()
    }

    private fun requireReservationInput(stateVersion: String, reservedIp: String?) {
        if (stateVersion.isBlank() || reservedIp?.let { !canonicalIpv4(it) } == true) schemaFailure()
    }

    private fun canonicalIpv4(value: String): Boolean =
        IPV4.matches(value) && value.split('.').all { it.toInt() in 0..255 }

    private fun httpFailure(status: Int): Nothing = throw XkeenException(
        when (status) {
            401, 403 -> XkeenErrorCode.UNAUTHORIZED
            409 -> XkeenErrorCode.STALE_STATE
            413 -> XkeenErrorCode.UNSUPPORTED_SCHEMA
            504 -> XkeenErrorCode.TIMEOUT
            else -> XkeenErrorCode.COMPANION_UNAVAILABLE
        },
        "Companion request failed",
    )

    private fun schemaFailure(): Nothing =
        throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "Home device schema is not supported")

    @Serializable
    private data class ReservationReviewBody(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("state_version") val stateVersion: String,
        @SerialName("reserved_ip") val reservedIp: String?,
    )

    @Serializable
    private data class ReservationApplyBody(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("state_version") val stateVersion: String,
        @SerialName("reserved_ip") val reservedIp: String?,
        @SerialName("plan_id") val planId: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
    )

    private companion object {
        const val MAX_BYTES = 1_048_576L
        val DEVICE_ID = Regex("^mac-[0-9a-f]{16}$")
        val MAC = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
        val IPV4 = Regex("^(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}$")
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val TERMINAL_RESULTS = setOf("committed", "rolled_back", "rejected", "uncertain")
    }
}
