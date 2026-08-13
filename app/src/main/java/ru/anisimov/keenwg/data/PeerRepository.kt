package ru.anisimov.keenwg.data

import com.wireguard.config.Config
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.anisimov.keenwg.data.crypto.ConfBuilder
import ru.anisimov.keenwg.data.crypto.WgKeyPair
import ru.anisimov.keenwg.data.crypto.WgKeys
import ru.anisimov.keenwg.data.rci.ConfiguredPeer
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.data.rci.RciCommands
import ru.anisimov.keenwg.data.rci.RciException
import ru.anisimov.keenwg.data.rci.RciResponse
import ru.anisimov.keenwg.data.store.ConfStore
import ru.anisimov.keenwg.data.store.EmptyLineageStore
import ru.anisimov.keenwg.data.store.AccessPolicyStore
import ru.anisimov.keenwg.data.store.EmptyAccessPolicyStore
import ru.anisimov.keenwg.data.store.LineageStore
import ru.anisimov.keenwg.domain.IpAllocator
import ru.anisimov.keenwg.domain.PeerInputException
import ru.anisimov.keenwg.domain.PeerInputValidator
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.HandshakeNormalizer
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.AccessPolicyValidator
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

data class PrivateConfigReveal(val content: String, val remainingReveals: Int = 1)

data class AddResult(val peer: Peer, val reveal: PrivateConfigReveal) {
    constructor(peer: Peer, conf: String) : this(peer, PrivateConfigReveal(conf))
    val conf: String get() = reveal.content
}

fun interface KeyGenerator {
    fun generate(): WgKeyPair
}

fun interface AccessClock { fun nowEpochSeconds(): Long }

interface PeerRepositoryGateway {
    val cachedPeers: StateFlow<List<Peer>>
    suspend fun cached(settings: ServerSettings): List<Peer> = emptyList()
    suspend fun list(settings: ServerSettings): List<Peer>
    suspend fun add(settings: ServerSettings, name: String, ip: String? = null, policy: AccessPolicy? = null): AddResult
    suspend fun regenerate(settings: ServerSettings, publicKey: String): AddResult
    suspend fun remove(settings: ServerSettings, publicKey: String)
    suspend fun rename(settings: ServerSettings, publicKey: String, newName: String)
    suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean)
    suspend fun confFor(publicKey: String): String?
    suspend fun accessPolicyFor(publicKey: String): AccessPolicy?
}

class PeerRepository(
    private val client: RciClient,
    private val confStore: ConfStore,
    private val lineageStore: LineageStore = EmptyLineageStore,
    private val keyGenerator: KeyGenerator = KeyGenerator(WgKeys::generate),
    private val accessPolicyStore: AccessPolicyStore = EmptyAccessPolicyStore,
    private val clock: AccessClock = AccessClock { System.currentTimeMillis() / 1_000L },
) : PeerRepositoryGateway {
    private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    private val _cachedPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val cachedPeers: StateFlow<List<Peer>> = _cachedPeers.asStateFlow()

    override suspend fun list(settings: ServerSettings): List<Peer> {
        val dtos = RciResponse.peers(client.get(settings, "show/interface/${settings.interfaceId}"))
        val ips = RciResponse.configuredPeers(client.get(settings, "show/running-config"), settings.interfaceId)
            .associate { it.publicKey to it.allowIp }
        return dtos.map { dto ->
            Peer(
                publicKey = dto.publicKey,
                name = dto.description,
                ip = ips[dto.publicKey],
                online = dto.online,
                handshake = HandshakeNormalizer.normalize(dto.online, dto.lastHandshakeSec, dto.rxBytes, dto.txBytes),
                clientUploadBytes = dto.rxBytes,
                clientDownloadBytes = dto.txBytes,
                enabled = dto.enabled,
            )
        }.also { _cachedPeers.value = it }
    }

    override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy?): AddResult {
        ServerSettingsValidator.requireForMutation(settings)
        val effectivePolicy = policy ?: AccessPolicy(dnsServers = listOf(settings.dns))
        AccessPolicyValidator.requireValid(effectivePolicy, clock.nowEpochSeconds())
        return lockFor(settings).withLock {
            val snapshot = configuredSnapshot(settings)
            val occupied = snapshot.mapNotNull { it.allowIp }.toSet()
            val assigned = ip ?: IpAllocator.nextFreeIp(settings.subnetBase, occupied)
                ?: throw PeerInputException(listOf(ru.anisimov.keenwg.domain.ValidationIssue("ip", "В подсети нет свободных IP")))
            requirePeerInput(name, assigned, settings, occupied)

            val keys = verifiedKeyPair(snapshot, settings)
            val conf = verifiedConf(keys, assigned, settings, effectivePolicy)
            confStore.put(keys.publicKey, conf)
            try {
                checkResponse(client.post(settings, RciCommands.addPeer(settings.interfaceId, keys.publicKey, name, assigned, settings.keepalive)))
                verifyExact(configuredSnapshot(settings), keys.publicKey, name, assigned, settings.keepalive, true)
                checkResponse(client.post(settings, RciCommands.save))
                verifyExact(configuredSnapshot(settings), keys.publicKey, name, assigned, settings.keepalive, true)
                accessPolicyStore.put(keys.publicKey, effectivePolicy)
                confStore.remove(keys.publicKey)
                val peer = newPeer(keys.publicKey, name, assigned, true)
                upsertCached(peer)
                AddResult(peer, conf)
            } catch (failure: Exception) {
                withContext(NonCancellable) { rollbackAdd(settings, keys.publicKey, failure) }
            }
        }
    }

    override suspend fun regenerate(settings: ServerSettings, publicKey: String): AddResult {
        ServerSettingsValidator.requireForMutation(settings)
        return lockFor(settings).withLock {
            val snapshot = configuredSnapshot(settings)
            val old = snapshot.firstOrNull { it.publicKey == publicKey } ?: throw RciException("Доступ не найден")
            val ip = old.allowIp ?: throw RciException("Для доступа не назначен допустимый IP")
            val policy = accessPolicyStore.get(old.publicKey) ?: AccessPolicy(dnsServers = listOf(settings.dns))
            val keys = verifiedKeyPair(snapshot, settings)
            val conf = verifiedConf(keys, ip, settings, policy)
            confStore.put(keys.publicKey, conf)
            try {
                checkResponse(client.post(settings, RciCommands.createPeer(settings.interfaceId, keys.publicKey, old.name, old.keepalive)))
                val phaseOne = configuredSnapshot(settings)
                verifyExact(phaseOne, keys.publicKey, old.name, null, old.keepalive, false)
                check(phaseOne.any { it.publicKey == old.publicKey }) { "Старый доступ исчез до переключения" }

                checkResponse(client.post(settings, RciCommands.cutoverPeer(settings.interfaceId, old, keys.publicKey)))
                verifyExact(configuredSnapshot(settings), keys.publicKey, old.name, ip, old.keepalive, old.enabled)
                checkResponse(client.post(settings, RciCommands.save))
                verifyExact(configuredSnapshot(settings), keys.publicKey, old.name, ip, old.keepalive, old.enabled)

            } catch (failure: Exception) {
                withContext(NonCancellable) { rollbackRotation(settings, old, keys.publicKey, failure) }
            }

            val rotated = newPeer(keys.publicKey, old.name, ip, old.enabled)
            upsertCached(rotated, replacingPublicKey = old.publicKey)
            try {
                lineageStore.recordRotation(settings.interfaceId, old.publicKey, keys.publicKey)
                accessPolicyStore.rotate(old.publicKey, keys.publicKey, policy)
                confStore.remove(old.publicKey)
                confStore.remove(keys.publicKey)
            } catch (failure: Exception) {
                throw RouterMutationError.LocalFinalization(
                    newPublicKey = keys.publicKey,
                    message = "Ключ на роутере перевыпущен, но локальные данные обновлены не полностью. Обе конфигурации сохранены для восстановления.",
                    cause = failure,
                )
            }
            AddResult(rotated, conf)
        }
    }

    override suspend fun remove(settings: ServerSettings, publicKey: String) {
        ServerSettingsValidator.requireForMutation(settings)
        lockFor(settings).withLock {
            val old = configuredSnapshot(settings).firstOrNull { it.publicKey == publicKey } ?: throw RciException("Доступ не найден")
            try {
                checkResponse(client.post(settings, RciCommands.removePeer(settings.interfaceId, publicKey)))
                checkResponse(client.post(settings, RciCommands.save))
                check(configuredSnapshot(settings).none { it.publicKey == publicKey }) { "Доступ всё ещё присутствует после удаления" }
            } catch (failure: Exception) {
                withContext(NonCancellable) { rollbackPeer(settings, old, null, "Удаление не применено. Доступ восстановлен.", failure) }
            }
            removeCached(publicKey)
            try {
                confStore.remove(publicKey)
                lineageStore.remove(publicKey)
                accessPolicyStore.remove(publicKey)
            } catch (failure: Exception) {
                throw RouterMutationError.LocalFinalization(null, "Доступ удалён на роутере, но локальные данные очищены не полностью.", failure)
            }
        }
    }

    override suspend fun rename(settings: ServerSettings, publicKey: String, newName: String) {
        ServerSettingsValidator.requireForMutation(settings)
        lockFor(settings).withLock {
            val peer = configuredSnapshot(settings).firstOrNull { it.publicKey == publicKey } ?: throw RciException("Доступ не найден")
            val issues = PeerInputValidator.validateName(newName)
            if (issues.isNotEmpty()) throw PeerInputException(issues)
            try {
                checkResponse(client.post(settings, RciCommands.rename(settings.interfaceId, publicKey, newName)))
                checkResponse(client.post(settings, RciCommands.save))
                verifyExact(configuredSnapshot(settings), publicKey, newName, peer.allowIp, peer.keepalive, peer.enabled)
            } catch (failure: Exception) {
                withContext(NonCancellable) { rollbackPeer(settings, peer, null, "Переименование не применено. Имя восстановлено.", failure) }
            }
            updateCached(publicKey) { it.copy(name = newName) }
        }
    }

    override suspend fun setEnabled(settings: ServerSettings, publicKey: String, enabled: Boolean) {
        ServerSettingsValidator.requireForMutation(settings)
        lockFor(settings).withLock {
            val peer = configuredSnapshot(settings).firstOrNull { it.publicKey == publicKey } ?: throw RciException("Доступ не найден")
            try {
                checkResponse(client.post(settings, RciCommands.setEnabled(settings.interfaceId, publicKey, enabled)))
                checkResponse(client.post(settings, RciCommands.save))
                verifyExact(configuredSnapshot(settings), publicKey, peer.name, peer.allowIp, peer.keepalive, enabled)
            } catch (failure: Exception) {
                withContext(NonCancellable) { rollbackPeer(settings, peer, null, "Изменение состояния не применено. Доступ восстановлен.", failure) }
            }
            updateCached(publicKey) { it.copy(enabled = enabled) }
        }
    }

    override suspend fun confFor(publicKey: String): String? = confStore.take(publicKey)
    override suspend fun accessPolicyFor(publicKey: String): AccessPolicy? = accessPolicyStore.get(publicKey)

    private fun lockFor(settings: ServerSettings) = mutationLocks.computeIfAbsent(settings.interfaceId) { Mutex() }

    private fun upsertCached(peer: Peer, replacingPublicKey: String = peer.publicKey) {
        _cachedPeers.update { peers ->
            val index = peers.indexOfFirst { it.publicKey == replacingPublicKey || it.publicKey == peer.publicKey }
            val withoutPrevious = peers.filterNot {
                it.publicKey == replacingPublicKey || it.publicKey == peer.publicKey
            }.toMutableList()
            val insertion = if (index < 0) withoutPrevious.size else index.coerceAtMost(withoutPrevious.size)
            withoutPrevious.add(insertion, peer)
            withoutPrevious
        }
    }

    private fun updateCached(publicKey: String, transform: (Peer) -> Peer) {
        _cachedPeers.update { peers -> peers.map { if (it.publicKey == publicKey) transform(it) else it } }
    }

    private fun removeCached(publicKey: String) {
        _cachedPeers.update { peers -> peers.filterNot { it.publicKey == publicKey } }
    }

    private suspend fun configuredSnapshot(settings: ServerSettings): List<ConfiguredPeer> =
        RciResponse.configuredPeers(client.get(settings, "show/running-config"), settings.interfaceId)

    private fun verifiedKeyPair(existing: List<ConfiguredPeer>, settings: ServerSettings): WgKeyPair {
        val keys = keyGenerator.generate()
        require(WgKeys.publicFrom(keys.privateKey) == keys.publicKey) { "Сгенерированная ключевая пара не прошла проверку" }
        require(keys.publicKey != settings.serverPublicKey && existing.none { it.publicKey == keys.publicKey }) { "Сгенерирован дублирующий публичный ключ" }
        return keys
    }

    private fun verifiedConf(keys: WgKeyPair, ip: String, settings: ServerSettings, policy: AccessPolicy): String {
        val conf = ConfBuilder.build(keys.privateKey, "$ip/32", policy.dnsServers.ifEmpty { listOf(settings.dns) }, settings.mtu, settings.serverPublicKey, settings.endpoint, settings.keepalive, policy.allowedNetworks)
        Config.parse(BufferedReader(StringReader(conf)))
        return conf
    }

    private fun requirePeerInput(name: String, ip: String, settings: ServerSettings, occupied: Set<String>) {
        val issues = PeerInputValidator.validate(name, ip, settings.subnetBase, occupied)
        if (issues.isNotEmpty()) throw PeerInputException(issues)
    }

    private fun verifyExact(
        peers: List<ConfiguredPeer>,
        publicKey: String,
        name: String,
        ip: String?,
        keepalive: Int,
        enabled: Boolean,
    ) {
        val actual = peers.firstOrNull { it.publicKey == publicKey } ?: error("Доступ отсутствует при проверке")
        check(actual.name == name && actual.allowIp == ip && actual.keepalive == keepalive && actual.enabled == enabled) {
            "Параметры доступа не совпали при проверке"
        }
    }

    private suspend fun rollbackAdd(settings: ServerSettings, newPublicKey: String, cause: Throwable): Nothing {
        try {
            if (configuredSnapshot(settings).any { it.publicKey == newPublicKey }) {
                checkResponse(client.post(settings, RciCommands.removePeer(settings.interfaceId, newPublicKey)))
                checkResponse(client.post(settings, RciCommands.save))
                check(configuredSnapshot(settings).none { it.publicKey == newPublicKey }) { "Новый доступ остался после отката" }
            }
            confStore.remove(newPublicKey)
            accessPolicyStore.remove(newPublicKey)
        } catch (rollbackFailure: Exception) {
            throw RouterMutationError.Uncertain("Состояние роутера не удалось подтвердить. Сохраните обе конфигурации для восстановления.", rollbackFailure)
        }
        throw RouterMutationError.RolledBack("Новый доступ не применён. Настройки роутера восстановлены.", cause)
    }

    private suspend fun rollbackRotation(settings: ServerSettings, old: ConfiguredPeer, newPublicKey: String, cause: Throwable): Nothing {
        try {
            if (configuredSnapshot(settings).any { it.publicKey == newPublicKey }) {
                checkResponse(client.post(settings, RciCommands.removePeer(settings.interfaceId, newPublicKey)))
            }
            checkResponse(client.post(settings, RciCommands.restorePeer(settings.interfaceId, old)))
            val runningRestored = configuredSnapshot(settings)
            verifyExact(runningRestored, old.publicKey, old.name, old.allowIp, old.keepalive, old.enabled)
            check(runningRestored.none { it.publicKey == newPublicKey }) { "Новый доступ остался после отката" }
            checkResponse(client.post(settings, RciCommands.save))
            val restored = configuredSnapshot(settings)
            verifyExact(restored, old.publicKey, old.name, old.allowIp, old.keepalive, old.enabled)
            check(restored.none { it.publicKey == newPublicKey }) { "Новый доступ остался после сохранения отката" }
            confStore.remove(newPublicKey)
            lineageStore.remove(newPublicKey)
        } catch (rollbackFailure: Exception) {
            throw RouterMutationError.Uncertain("Состояние роутера не удалось подтвердить. Сохраните обе конфигурации для восстановления.", rollbackFailure)
        }
        throw RouterMutationError.RolledBack("Перевыпуск не применён. Старый доступ восстановлен.", cause)
    }

    private suspend fun rollbackPeer(
        settings: ServerSettings,
        old: ConfiguredPeer,
        newPublicKey: String?,
        message: String,
        cause: Throwable,
    ): Nothing {
        try {
            val current = configuredSnapshot(settings)
            if (newPublicKey != null && current.any { it.publicKey == newPublicKey }) {
                checkResponse(client.post(settings, RciCommands.removePeer(settings.interfaceId, newPublicKey)))
            }
            checkResponse(client.post(settings, RciCommands.restorePeer(settings.interfaceId, old)))
            val runningRestored = configuredSnapshot(settings)
            verifyExact(runningRestored, old.publicKey, old.name, old.allowIp, old.keepalive, old.enabled)
            if (newPublicKey != null) check(runningRestored.none { it.publicKey == newPublicKey })
            checkResponse(client.post(settings, RciCommands.save))
            val finalRestored = configuredSnapshot(settings)
            verifyExact(finalRestored, old.publicKey, old.name, old.allowIp, old.keepalive, old.enabled)
            if (newPublicKey != null) check(finalRestored.none { it.publicKey == newPublicKey })
        } catch (rollbackFailure: Exception) {
            throw RouterMutationError.Uncertain("Состояние роутера после ошибки не удалось подтвердить.", rollbackFailure)
        }
        throw RouterMutationError.RolledBack(message, cause)
    }

    private fun newPeer(publicKey: String, name: String, ip: String, enabled: Boolean) = Peer(
        publicKey, name, ip, false, HandshakeNormalizer.normalize(false, null, 0, 0), 0, 0, enabled,
    )

    private fun checkResponse(responseJson: String) {
        RciResponse.firstError(responseJson)?.let { throw RciException(it.message ?: "Ошибка RCI") }
    }
}
