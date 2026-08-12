package ru.anisimov.keenwg.data.wireguard

import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.anisimov.keenwg.data.AccessClock
import ru.anisimov.keenwg.data.AddResult
import ru.anisimov.keenwg.data.KeyGenerator
import ru.anisimov.keenwg.data.RouterMutationError
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.crypto.ConfBuilder
import ru.anisimov.keenwg.data.crypto.WgKeyPair
import ru.anisimov.keenwg.data.crypto.WgKeys
import ru.anisimov.keenwg.data.store.AccessPolicyStore
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.store.ConfStore
import ru.anisimov.keenwg.data.store.EmptyAccessPolicyStore
import ru.anisimov.keenwg.data.store.EmptyLineageStore
import ru.anisimov.keenwg.data.store.LineageStore
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.IpAllocator
import ru.anisimov.keenwg.domain.PeerInputException
import ru.anisimov.keenwg.domain.PeerInputValidator
import ru.anisimov.keenwg.domain.ValidationIssue
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.AccessPolicyValidator
import ru.anisimov.keenwg.domain.model.HandshakeNormalizer
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings

interface CompanionPeerGateway {
    val cachedPeers: StateFlow<List<Peer>>
    suspend fun list(active: ActiveRouterProfile, settings: ServerSettings): List<Peer>
    suspend fun add(active: ActiveRouterProfile, settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy?): AddResult
    suspend fun regenerate(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String): AddResult
    suspend fun remove(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String)
    suspend fun rename(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, newName: String)
    suspend fun setEnabled(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, enabled: Boolean)
    suspend fun confFor(publicKey: String): String?
    suspend fun accessPolicyFor(publicKey: String): AccessPolicy?
}

class CompanionPeerRepository(
    private val client: CompanionWireGuardGateway,
    private val confStore: ConfStore,
    private val lineageStore: LineageStore = EmptyLineageStore,
    private val keyGenerator: KeyGenerator = KeyGenerator(WgKeys::generate),
    private val accessPolicyStore: AccessPolicyStore = EmptyAccessPolicyStore,
    private val clock: AccessClock = AccessClock { System.currentTimeMillis() / 1_000L },
) : CompanionPeerGateway {
    private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    private val _cachedPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val cachedPeers: StateFlow<List<Peer>> = _cachedPeers.asStateFlow()

    override suspend fun list(active: ActiveRouterProfile, settings: ServerSettings): List<Peer> {
        val (_, iface) = snapshot(active, settings)
        return iface.peers.map(::toPeer).also { _cachedPeers.value = it }
    }

    override suspend fun add(
        active: ActiveRouterProfile,
        settings: ServerSettings,
        name: String,
        ip: String?,
        policy: AccessPolicy?,
    ): AddResult = lockFor(settings).withLock {
        val endpoint = active.requireCompanionEndpoint()
        val (document, iface) = snapshot(active, settings)
        val occupied = iface.peers.mapNotNull { it.allowedIp }.toSet()
        val subnetBase = subnetBase(iface, settings)
        val assigned = ip ?: IpAllocator.nextFreeIp(subnetBase, occupied)
            ?: throw PeerInputException(listOf(ValidationIssue("ip", "No free WireGuard address")))
        val issues = PeerInputValidator.validate(name, assigned, subnetBase, occupied)
        if (issues.isNotEmpty()) throw PeerInputException(issues)
        val effectivePolicy = policy ?: AccessPolicy(dnsServers = listOf(settings.dns))
        AccessPolicyValidator.requireValid(effectivePolicy, clock.nowEpochSeconds())
        val keys = verifiedKeyPair(iface, settings)
        val conf = verifiedConf(keys, assigned, iface, settings, effectivePolicy)
        confStore.put(keys.publicKey, conf)
        val mutation = CompanionPeerMutation(
            stateVersion = document.stateVersion,
            interfaceId = iface.id,
            action = "create",
            publicKey = keys.publicKey,
            name = name,
            allowedIp = assigned,
            keepalive = settings.keepalive,
            enabled = true,
        )
        val committed = try {
            requireCommitted(apply(endpoint, mutation), iface.id, keys.publicKey)
        } catch (failure: Exception) {
            if (failure !is RouterMutationError.Uncertain) confStore.remove(keys.publicKey)
            throw failure
        }
        try {
            accessPolicyStore.put(keys.publicKey, effectivePolicy)
            confStore.remove(keys.publicKey)
        } catch (failure: Exception) {
            throw RouterMutationError.LocalFinalization(keys.publicKey, "Access was created, but local metadata was not fully finalized.", failure)
        }
        _cachedPeers.value = committed
        AddResult(committed.single { it.publicKey == keys.publicKey }, conf)
    }

    override suspend fun regenerate(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String): AddResult =
        lockFor(settings).withLock {
            val endpoint = active.requireCompanionEndpoint()
            val (document, iface) = snapshot(active, settings)
            val old = iface.peers.singleOrNull { it.publicKey == publicKey }
                ?: throw IllegalArgumentException("Access was not found")
            val assigned = old.allowedIp ?: throw IllegalArgumentException("Access has no assigned address")
            val policy = accessPolicyStore.get(publicKey) ?: AccessPolicy(dnsServers = listOf(settings.dns))
            val keys = verifiedKeyPair(iface, settings)
            val conf = verifiedConf(keys, assigned, iface, settings, policy)
            confStore.put(keys.publicKey, conf)
            val mutation = CompanionPeerMutation(
                stateVersion = document.stateVersion,
                interfaceId = iface.id,
                action = "rotate",
                publicKey = publicKey,
                newPublicKey = keys.publicKey,
            )
            val committed = try {
                requireCommitted(apply(endpoint, mutation), iface.id, keys.publicKey)
            } catch (failure: Exception) {
                if (failure !is RouterMutationError.Uncertain) confStore.remove(keys.publicKey)
                throw failure
            }
            try {
                lineageStore.recordRotation(iface.id, publicKey, keys.publicKey)
                accessPolicyStore.rotate(publicKey, keys.publicKey, policy)
                confStore.remove(publicKey)
                confStore.remove(keys.publicKey)
            } catch (failure: Exception) {
                throw RouterMutationError.LocalFinalization(keys.publicKey, "Access was rotated, but local metadata was not fully finalized.", failure)
            }
            _cachedPeers.value = committed
            AddResult(committed.single { it.publicKey == keys.publicKey }, conf)
        }

    override suspend fun remove(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String) =
        mutateExisting(active, settings, publicKey, "revoke") {
            confStore.remove(publicKey)
            lineageStore.remove(publicKey)
            accessPolicyStore.remove(publicKey)
        }

    override suspend fun rename(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, newName: String) {
        val issues = PeerInputValidator.validateName(newName)
        if (issues.isNotEmpty()) throw PeerInputException(issues)
        mutateExisting(active, settings, publicKey, "rename", name = newName)
    }

    override suspend fun setEnabled(active: ActiveRouterProfile, settings: ServerSettings, publicKey: String, enabled: Boolean) {
        mutateExisting(active, settings, publicKey, "set_enabled", enabled = enabled)
    }

    override suspend fun confFor(publicKey: String): String? = confStore.take(publicKey)
    override suspend fun accessPolicyFor(publicKey: String): AccessPolicy? = accessPolicyStore.get(publicKey)

    private suspend fun mutateExisting(
        active: ActiveRouterProfile,
        settings: ServerSettings,
        publicKey: String,
        action: String,
        name: String? = null,
        enabled: Boolean? = null,
        finalize: suspend () -> Unit = {},
    ) = lockFor(settings).withLock {
        val endpoint = active.requireCompanionEndpoint()
        val (document, iface) = snapshot(active, settings)
        if (iface.peers.none { it.publicKey == publicKey }) throw IllegalArgumentException("Access was not found")
        val mutation = CompanionPeerMutation(
            stateVersion = document.stateVersion,
            interfaceId = iface.id,
            action = action,
            publicKey = publicKey,
            name = name,
            enabled = enabled,
        )
        val committed = requireCommitted(apply(endpoint, mutation), iface.id)
        try {
            finalize()
        } catch (failure: Exception) {
            throw RouterMutationError.LocalFinalization(null, "Router changed, but local metadata was not fully finalized.", failure)
        }
        _cachedPeers.value = committed
    }

    private suspend fun apply(endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint, mutation: CompanionPeerMutation): CompanionWireGuardMutationResult {
        val plan = client.review(endpoint, mutation)
        return try {
            client.apply(endpoint, mutation, plan.planId)
        } catch (failure: Exception) {
            if (failure is XkeenException && failure.code in setOf(
                    XkeenErrorCode.UNAUTHORIZED,
                    XkeenErrorCode.NOT_FOUND,
                    XkeenErrorCode.STALE_STATE,
                    XkeenErrorCode.INVALID_SETTINGS,
                )
            ) {
                throw failure
            }
            throw RouterMutationError.Uncertain("WireGuard change was sent, but its final state could not be verified.", failure)
        }
    }

    private fun requireCommitted(
        result: CompanionWireGuardMutationResult,
        interfaceId: String,
        expectedKey: String? = null,
    ): List<Peer> {
        when (result.status) {
            "rolled_back" -> throw RouterMutationError.RolledBack("The router restored the previous WireGuard state.")
            "rejected" -> throw IllegalStateException("The router rejected the WireGuard change.")
            "uncertain" -> throw RouterMutationError.Uncertain("WireGuard state requires verification.")
            "committed" -> Unit
            else -> throw IllegalStateException("Unsupported WireGuard result")
        }
        val document = requireNotNull(result.wireguard)
        val iface = document.interfaces.singleOrNull { it.id == interfaceId }
            ?: throw RouterMutationError.Uncertain("Committed WireGuard interface was not present in the verified inventory.")
        val peers = iface.peers.map(::toPeer)
        if (expectedKey != null && peers.none { it.publicKey == expectedKey }) {
            throw RouterMutationError.Uncertain("Committed access was not present in the verified inventory.")
        }
        return peers
    }

    private suspend fun snapshot(
        active: ActiveRouterProfile,
        settings: ServerSettings,
    ): Pair<CompanionWireGuardDocument, CompanionWireGuardInterface> {
        val document = client.load(active.requireCompanionEndpoint())
        val iface = document.interfaces.firstOrNull { it.id == settings.interfaceId }
            ?: document.interfaces.singleOrNull()
            ?: throw IllegalStateException("WireGuard interface was not found")
        return document to iface
    }

    private fun verifiedKeyPair(iface: CompanionWireGuardInterface, settings: ServerSettings): WgKeyPair {
        val keys = keyGenerator.generate()
        require(WgKeys.publicFrom(keys.privateKey) == keys.publicKey) { "Generated key pair failed verification" }
        require(keys.publicKey != iface.publicKey && keys.publicKey != settings.serverPublicKey &&
            iface.peers.none { it.publicKey == keys.publicKey }
        ) { "Generated duplicate public key" }
        return keys
    }

    private fun verifiedConf(
        keys: WgKeyPair,
        ip: String,
        iface: CompanionWireGuardInterface,
        settings: ServerSettings,
        policy: AccessPolicy,
    ): String {
        val serverPublicKey = iface.publicKey ?: throw IllegalStateException("Router WireGuard public key is unavailable")
        val endpoint = settings.endpoint.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Set the router's public WireGuard endpoint to create or rotate access")
        val conf = ConfBuilder.build(
            keys.privateKey,
            "$ip/32",
            policy.dnsServers.ifEmpty { listOf(settings.dns) },
            iface.mtu ?: settings.mtu,
            serverPublicKey,
            endpoint,
            settings.keepalive,
            policy.allowedNetworks,
        )
        Config.parse(BufferedReader(StringReader(conf)))
        return conf
    }

    private fun subnetBase(iface: CompanionWireGuardInterface, settings: ServerSettings): String {
        val address = iface.addresses.firstOrNull()?.substringBefore('/') ?: return settings.subnetBase
        val lastDot = address.lastIndexOf('.')
        return if (lastDot > 0) address.substring(0, lastDot + 1) else settings.subnetBase
    }

    private fun toPeer(value: CompanionWireGuardPeer) = Peer(
        publicKey = value.publicKey,
        name = value.name,
        ip = value.allowedIp,
        online = value.online,
        handshake = HandshakeNormalizer.normalize(value.online, value.lastHandshakeSec, value.rxBytes, value.txBytes),
        clientUploadBytes = value.rxBytes,
        clientDownloadBytes = value.txBytes,
        enabled = value.enabled,
    )

    private fun lockFor(settings: ServerSettings) = mutationLocks.computeIfAbsent(settings.interfaceId) { Mutex() }
}
