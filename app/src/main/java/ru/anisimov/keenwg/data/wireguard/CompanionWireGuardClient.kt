package ru.anisimov.keenwg.data.wireguard

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
data class CompanionWireGuardPeer(
    @SerialName("public_key") val publicKey: String,
    val name: String,
    @SerialName("allowed_ip") val allowedIp: String? = null,
    val keepalive: Int,
    val enabled: Boolean,
    val online: Boolean,
    @SerialName("last_handshake_sec") val lastHandshakeSec: Long? = null,
    @SerialName("rx_bytes") val rxBytes: Long,
    @SerialName("tx_bytes") val txBytes: Long,
)

@Serializable
data class CompanionWireGuardInterface(
    val id: String,
    @SerialName("public_key") val publicKey: String? = null,
    val addresses: List<String>,
    @SerialName("listen_port") val listenPort: Int? = null,
    val mtu: Int? = null,
    val peers: List<CompanionWireGuardPeer>,
)

@Serializable
data class CompanionWireGuardDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("state_version") val stateVersion: String,
    val interfaces: List<CompanionWireGuardInterface>,
)

@Serializable
data class CompanionPeerMutation(
    @SerialName("state_version") val stateVersion: String,
    @SerialName("interface_id") val interfaceId: String,
    val action: String,
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("new_public_key") val newPublicKey: String? = null,
    val name: String? = null,
    @SerialName("allowed_ip") val allowedIp: String? = null,
    val keepalive: Int? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class CompanionPeerPlan(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("plan_id") val planId: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val request: CompanionPeerMutation,
    val before: CompanionWireGuardPeer? = null,
    val after: CompanionWireGuardPeer? = null,
)

@Serializable
data class CompanionWireGuardMutationResult(
    @SerialName("schema_version") val schemaVersion: Int,
    val status: String,
    val code: String? = null,
    val wireguard: CompanionWireGuardDocument? = null,
)

interface CompanionWireGuardGateway {
    suspend fun load(endpoint: CompanionEndpoint): CompanionWireGuardDocument
    suspend fun review(endpoint: CompanionEndpoint, request: CompanionPeerMutation): CompanionPeerPlan
    suspend fun apply(endpoint: CompanionEndpoint, request: CompanionPeerMutation, planId: String): CompanionWireGuardMutationResult
}

class CompanionWireGuardClient(
    private val transport: CompanionHttpTransport = CompanionHttpTransport(),
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : CompanionWireGuardGateway {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = false
    }

    override suspend fun load(endpoint: CompanionEndpoint): CompanionWireGuardDocument = withContext(Dispatchers.IO) {
        LOAD_RETRY_DELAYS.forEach { wait ->
            try {
                return@withContext loadOnce(endpoint)
            } catch (failure: XkeenException) {
                if (failure.code !in RETRYABLE_LOAD_ERRORS) throw failure
                delayMillis(wait)
            }
        }
        loadOnce(endpoint)
    }

    private fun loadOnce(endpoint: CompanionEndpoint): CompanionWireGuardDocument =
        execute(endpoint, "/v1/access/wireguard", "GET", null) { decodeDocument(it) }

    override suspend fun review(endpoint: CompanionEndpoint, request: CompanionPeerMutation): CompanionPeerPlan = withContext(Dispatchers.IO) {
        requireValidMutation(request)
        val body = json.encodeToString(PeerReviewBody.from(request))
        execute(endpoint, "/v1/access/wireguard/peers/review", "POST", body) { text ->
            decode<CompanionPeerPlan>(text).also { plan ->
                if (plan.schemaVersion != 1 || plan.planId.isBlank() || plan.request != request) schemaFailure()
                plan.before?.let(::requireValidPeer)
                plan.after?.let(::requireValidPeer)
            }
        }
    }

    override suspend fun apply(
        endpoint: CompanionEndpoint,
        request: CompanionPeerMutation,
        planId: String,
    ): CompanionWireGuardMutationResult = withContext(Dispatchers.IO) {
        requireValidMutation(request)
        if (planId.isBlank()) schemaFailure()
        val key = keyFactory()
        if (!UUID_PATTERN.matches(key)) schemaFailure()
        val body = json.encodeToString(PeerApplyBody.from(request, planId, key))
        execute(endpoint, "/v1/access/wireguard/peers/apply", "POST", body) { text ->
            decode<CompanionWireGuardMutationResult>(text).also(::requireValidResult)
        }
    }

    private fun decodeDocument(text: String): CompanionWireGuardDocument =
        decode<CompanionWireGuardDocument>(text).also(::requireValidDocument)

    private fun requireValidDocument(document: CompanionWireGuardDocument) {
        if (document.schemaVersion != 1 || document.stateVersion.isBlank() || document.interfaces.size > 64 ||
            document.interfaces.map { it.id }.toSet().size != document.interfaces.size
        ) schemaFailure()
        document.interfaces.forEach { value ->
            if (!INTERFACE_ID.matches(value.id) || value.publicKey?.let(::canonicalKey) == false ||
                value.addresses.size > 16 || value.addresses.any { !canonicalCidr(it) } ||
                value.listenPort?.let { it !in 1..65535 } == true || value.mtu?.let { it !in 576..9000 } == true ||
                value.peers.size > 1024 || value.peers.map { it.publicKey }.toSet().size != value.peers.size
            ) schemaFailure()
            value.peers.forEach(::requireValidPeer)
        }
    }

    private fun requireValidPeer(peer: CompanionWireGuardPeer) {
        if (!canonicalKey(peer.publicKey) || !PEER_NAME.matches(peer.name) ||
            peer.allowedIp?.let { !canonicalIpv4(it) } == true || peer.keepalive !in 0..3600 ||
            peer.rxBytes < 0 || peer.txBytes < 0
        ) schemaFailure()
    }

    private fun requireValidMutation(request: CompanionPeerMutation) {
        if (request.stateVersion.isBlank() || !INTERFACE_ID.matches(request.interfaceId) ||
            request.publicKey?.let(::canonicalKey) == false || request.newPublicKey?.let(::canonicalKey) == false ||
            request.name?.let { !PEER_NAME.matches(it) } == true || request.allowedIp?.let { !canonicalIpv4(it) } == true ||
            request.keepalive?.let { it !in 0..3600 } == true
        ) schemaFailure()
        when (request.action) {
            "create" -> if (request.publicKey == null || request.name == null || request.allowedIp == null) schemaFailure()
            "rename" -> if (request.publicKey == null || request.name == null) schemaFailure()
            "set_enabled" -> if (request.publicKey == null || request.enabled == null) schemaFailure()
            "rotate" -> if (request.publicKey == null || request.newPublicKey == null || request.publicKey == request.newPublicKey) schemaFailure()
            "revoke" -> if (request.publicKey == null) schemaFailure()
            else -> schemaFailure()
        }
    }

    private fun requireValidResult(result: CompanionWireGuardMutationResult) {
        if (result.schemaVersion != 1 || result.status !in TERMINAL_RESULTS) schemaFailure()
        if (result.status == "committed" && result.wireguard == null) schemaFailure()
        result.wireguard?.let(::requireValidDocument)
    }

    private fun canonicalKey(value: String): Boolean = try {
        value.length == 44 && Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value)) == value &&
            Base64.getDecoder().decode(value).size == 32
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun canonicalIpv4(value: String): Boolean =
        IPV4.matches(value) && value.split('.').all { it.toInt() in 0..255 }

    private fun canonicalCidr(value: String): Boolean {
        if (!CIDR.matches(value)) return false
        val (address, prefix) = value.split('/', limit = 2)
        return canonicalIpv4(address) && prefix.toInt() in 0..32
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
        throw XkeenException(XkeenErrorCode.UNSUPPORTED_SCHEMA, "WireGuard schema is not supported")

    @Serializable
    private data class PeerReviewBody(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("state_version") val stateVersion: String,
        @SerialName("interface_id") val interfaceId: String,
        val action: String,
        @SerialName("public_key") val publicKey: String? = null,
        @SerialName("new_public_key") val newPublicKey: String? = null,
        val name: String? = null,
        @SerialName("allowed_ip") val allowedIp: String? = null,
        val keepalive: Int? = null,
        val enabled: Boolean? = null,
    ) {
        companion object {
            fun from(value: CompanionPeerMutation) = PeerReviewBody(
                1, value.stateVersion, value.interfaceId, value.action, value.publicKey, value.newPublicKey,
                value.name, value.allowedIp, value.keepalive, value.enabled,
            )
        }
    }

    @Serializable
    private data class PeerApplyBody(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("state_version") val stateVersion: String,
        @SerialName("interface_id") val interfaceId: String,
        val action: String,
        @SerialName("public_key") val publicKey: String? = null,
        @SerialName("new_public_key") val newPublicKey: String? = null,
        val name: String? = null,
        @SerialName("allowed_ip") val allowedIp: String? = null,
        val keepalive: Int? = null,
        val enabled: Boolean? = null,
        @SerialName("plan_id") val planId: String,
        @SerialName("idempotency_key") val idempotencyKey: String,
    ) {
        companion object {
            fun from(value: CompanionPeerMutation, planId: String, idempotencyKey: String) = PeerApplyBody(
                1, value.stateVersion, value.interfaceId, value.action, value.publicKey, value.newPublicKey,
                value.name, value.allowedIp, value.keepalive, value.enabled, planId, idempotencyKey,
            )
        }
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
        val INTERFACE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9/_-]{0,63}$")
        val PEER_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        val IPV4 = Regex("^(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}$")
        val CIDR = Regex("^(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}/(?:[0-9]|[12][0-9]|3[0-2])$")
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val TERMINAL_RESULTS = setOf("committed", "rolled_back", "rejected", "uncertain")
        val RETRYABLE_LOAD_ERRORS = setOf(
            XkeenErrorCode.NETWORK,
            XkeenErrorCode.TIMEOUT,
            XkeenErrorCode.COMPANION_UNAVAILABLE,
        )
        val LOAD_RETRY_DELAYS = listOf(300L, 900L, 1_800L)
    }
}
