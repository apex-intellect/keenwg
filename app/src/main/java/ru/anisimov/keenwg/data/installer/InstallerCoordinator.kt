package ru.anisimov.keenwg.data.installer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.store.RouterProfileStore

interface InstallerProfileGateway {
    suspend fun active(profileId: String): ActiveRouterProfile
    suspend fun saveCompanion(profileId: String, baseUrl: String, certificatePin: String, deviceToken: String, deviceId: String)
}

interface InstallerWorkflow {
    suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation
    suspend fun prepare(
        profileId: String,
        endpoint: SshEndpoint,
        hostKey: HostKeyObservation,
        password: ByteArray,
    ): InstallPreparation
    suspend fun install(
        preparation: InstallPreparation,
        password: ByteArray,
        deviceLabel: String,
        onPhase: suspend (InstallPhase) -> Unit = {},
    ): InstallReport
}

class RouterProfileInstallerGateway(
    private val store: RouterProfileStore,
) : InstallerProfileGateway {
    override suspend fun active(profileId: String): ActiveRouterProfile {
        val active = store.activeProfile.first() ?: error("Profile store is locked")
        require(active.profile.id == profileId) { "Selected profile changed" }
        return active
    }

    override suspend fun saveCompanion(profileId: String, baseUrl: String, certificatePin: String, deviceToken: String, deviceId: String) {
        store.saveCompanion(profileId, baseUrl, certificatePin, deviceToken, deviceId)
    }
}

class InstallerCoordinator(
    private val assets: CompanionAssetVerifier,
    private val ssh: SshTransport,
    private val companion: CompanionClient,
    private val profiles: InstallerProfileGateway,
    private val nonce: () -> String = ::secureNonce,
) : InstallerWorkflow {
    private val operation = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun observeHostKey(endpoint: SshEndpoint): HostKeyObservation = ssh.observeHostKey(endpoint)

    override suspend fun prepare(
        profileId: String,
        endpoint: SshEndpoint,
        hostKey: HostKeyObservation,
        password: ByteArray,
    ): InstallPreparation = guarded(InstallPhase.PROBE) {
        var session: SshSession? = null
        try {
            val asset = verifyAsset()
            val active = profiles.active(profileId)
            session = ssh.connect(endpoint, password, hostKey)
            val probe = InstallProbeParser.parse(session.exec(FixedCommand.Probe))
            val mode = selectInstallMode(probe, asset.manifest.version)
            validateProbe(probe, asset, mode)
            val baseUrl = if (mode == InstallMode.PAIR_ONLY) null else secureBaseUrl(active.profile.host)
            InstallPreparation(
                profileId = profileId,
                endpoint = endpoint,
                hostKey = hostKey,
                probe = probe,
                plan = InstallPlan(
                    version = asset.manifest.version,
                    mode = mode,
                    secureBaseUrl = baseUrl,
                    requiredBytes = asset.bytes.size.toLong() + MIN_FREE_MARGIN_BYTES,
                    effects = buildEffects(probe, asset.manifest.version),
                ),
            )
        } finally {
            password.fill(0)
            session?.close()
        }
    }

    override suspend fun install(
        preparation: InstallPreparation,
        password: ByteArray,
        deviceLabel: String,
        onPhase: suspend (InstallPhase) -> Unit,
    ): InstallReport = guarded(InstallPhase.INSTALL) {
        require(deviceLabel.isNotBlank() && deviceLabel.length <= 80) { "Invalid device label" }
        var phase = InstallPhase.VERIFY_ASSET
        var session: SshSession? = null
        var requestBytes = ByteArray(0)
        var offerSecret = ByteArray(0)
        var installNonce: String? = null
        var uploaded = false
        var cleaned = false
        var installCommitted = false
        try {
            onPhase(phase)
            val asset = verifyAsset()
            require(asset.manifest.version == preparation.plan.version) { "Bundled version changed" }
            val active = profiles.active(preparation.profileId)
            preparation.plan.secureBaseUrl?.let { expected ->
                require(secureBaseUrl(active.profile.host) == expected) { "Router address changed" }
            }

            phase = InstallPhase.CONNECT
            onPhase(phase)
            session = ssh.connect(preparation.endpoint, password, preparation.hostKey)
            if (preparation.plan.mode != InstallMode.PAIR_ONLY) {
                installNonce = requireNonce(nonce())
                val archivePath = ValidatedTemporaryPath.archive(installNonce)
                val requestPath = ValidatedTemporaryPath.request(installNonce)
                requestBytes = bootstrapRequest(active.profile.host)
                phase = InstallPhase.UPLOAD
                onPhase(phase)
                session.upload(asset.bytes, archivePath)
                uploaded = true
                session.upload(requestBytes, requestPath)

                phase = InstallPhase.INSTALL
                onPhase(phase)
                requireSuccess(session.exec(FixedCommand.install(installNonce)), phase)
                installCommitted = true
            }

            phase = InstallPhase.PAIRING_OFFER
            onPhase(phase)
            val offer = parsePairingOffer(requireSuccess(session.exec(FixedCommand.CreateOwnerPairingOffer), phase).stdout)
            validateCompanionBaseUrl(offer.baseUrl)
            ExactPinTrustManager(offer.certificatePin)
            offerSecret = offer.secret.toByteArray()
            val pairedProfile = active.profile.copy(companionUrl = offer.baseUrl, certificatePin = offer.certificatePin)

            phase = InstallPhase.PAIRING_EXCHANGE
            onPhase(phase)
            val credential = companion.exchange(pairedProfile, offer.offerId, offer.secret, deviceLabel)
            require(credential.deviceId.isNotBlank() && credential.token.isNotBlank()) { "Invalid pairing credential" }
            phase = InstallPhase.HEALTH
            onPhase(phase)
            companion.capabilities(pairedProfile, credential.token)

            phase = InstallPhase.SAVE_PROFILE
            onPhase(phase)
            profiles.saveCompanion(preparation.profileId, offer.baseUrl, offer.certificatePin, credential.token, credential.deviceId)

            phase = InstallPhase.CLEANUP
            onPhase(phase)
            cleaned = installNonce?.let { cleanup(session, it) } ?: true
            val installedVersion = if (preparation.plan.mode == InstallMode.PAIR_ONLY) {
                preparation.probe.companionVersion ?: asset.manifest.version
            } else {
                asset.manifest.version
            }
            InstallReport(installedVersion, offer.baseUrl, credential.deviceId, cleaned)
        } catch (failure: Exception) {
            val rollbackVerified = when {
                installCommitted -> false
                phase == InstallPhase.INSTALL && session != null -> verifyRollback(session, preparation.probe)
                else -> true
            }
            val failedPhase = (failure as? InstallerException)?.phase ?: phase
            throw InstallerException(failedPhase, safeMessage(failedPhase), rollbackVerified, failure)
        } finally {
            if (uploaded && !cleaned && session != null) {
                installNonce?.let { nonce -> runCatching { cleanup(session, nonce) } }
            }
            offerSecret.fill(0)
            requestBytes.fill(0)
            password.fill(0)
            session?.close()
        }
    }

    private suspend fun <T> guarded(defaultPhase: InstallPhase, block: suspend () -> T): T {
        if (!operation.tryLock()) throw InstallerException(defaultPhase, "Другая установка уже выполняется", true)
        try {
            return block()
        } catch (failure: InstallerException) {
            throw failure
        } catch (failure: Exception) {
            throw InstallerException(defaultPhase, safeMessage(defaultPhase), true, failure)
        } finally {
            operation.unlock()
        }
    }

    private fun verifyAsset(): VerifiedCompanionAsset = try {
        assets.load()
    } catch (failure: Exception) {
        throw InstallerException(InstallPhase.VERIFY_ASSET, safeMessage(InstallPhase.VERIFY_ASSET), true, failure)
    }

    private fun selectInstallMode(probe: InstallProbe, bundledVersion: String): InstallMode {
        if (!probe.companionConfigPresent) return InstallMode.CLEAN_INSTALL
        val installed = probe.companionVersion ?: return InstallMode.UPDATE
        return if (compareVersions(installed, bundledVersion) >= 0) InstallMode.PAIR_ONLY else InstallMode.UPDATE
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): Pair<List<Int>, String?> {
            val core = value.substringBefore('-').split('.').map(String::toInt)
            require(core.size == 3)
            return core to value.substringAfter('-', "").ifEmpty { null }
        }
        val (leftCore, leftPre) = parts(left)
        val (rightCore, rightPre) = parts(right)
        for (index in 0..2) {
            leftCore[index].compareTo(rightCore[index]).takeIf { it != 0 }?.let { return it }
        }
        return when {
            leftPre == null && rightPre != null -> 1
            leftPre != null && rightPre == null -> -1
            else -> (leftPre ?: "").compareTo(rightPre ?: "")
        }
    }

    private fun validateProbe(probe: InstallProbe, asset: VerifiedCompanionAsset, mode: InstallMode) {
        require(probe.architecture == "aarch64") { "Unsupported architecture" }
        require(probe.firmware != "unknown" && probe.entwarePresent) { "Keenetic or Entware is unavailable" }
        if (mode != InstallMode.PAIR_ONLY) {
            require(probe.optFreeBytes >= asset.bytes.size.toLong() + MIN_FREE_MARGIN_BYTES) { "Not enough free space" }
        }
    }

    private fun buildEffects(probe: InstallProbe, version: String): List<String> = buildList {
        when (selectInstallMode(probe, version)) {
            InstallMode.CLEAN_INSTALL -> add("Установить Companion $version в /opt/lib/keenwg-companion")
            InstallMode.UPDATE -> add("Обновить Companion ${probe.companionVersion ?: "без версии"} до $version с резервной копией")
            InstallMode.PAIR_ONLY -> add("Привязать этот телефон к установленному Companion ${probe.companionVersion}")
        }
        add("Сохранить существующие XKeen, ASC, Xray и WireGuard без изменений")
        add("Проверить сертификат, права устройства и HTTPS API")
    }

    private fun bootstrapRequest(host: String): ByteArray {
        val address = literalAddress(host)
        val listen = "${address.hostAddress}:18779"
        return json.encodeToString(BootstrapRequest(secureListenAddress = listen)).toByteArray()
    }

    private fun secureBaseUrl(host: String): String {
        val address = literalAddress(host)
        return "https://${address.hostAddress}:18779"
    }

    private fun literalAddress(host: String): InetAddress {
        val octets = host.split('.')
        require(octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true &&
                (part == "0" || !part.startsWith('0'))
        }) { "Companion bind address must be a private IPv4 literal" }
        val address = InetAddress.getByAddress(octets.map(String::toInt).map(Int::toByte).toByteArray())
        require(address is Inet4Address && isPrivateCompanionAddress(address.address)) { "Companion bind address is unsafe" }
        return address
    }

    private fun isPrivateCompanionAddress(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || (first == 100 && second in 64..127)
    }

    private fun validateCompanionBaseUrl(baseUrl: String) {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(uri.port == 18779 && uri.host != null)
        literalAddress(uri.host)
    }

    private fun requireSuccess(result: CommandResult, phase: InstallPhase): CommandResult {
        if (result.exitCode != 0 || result.outputTruncated) {
            throw InstallerException(phase, safeMessage(phase), phase != InstallPhase.INSTALL)
        }
        return result
    }

    private fun parsePairingOffer(body: String): PairingOffer = try {
        require(body.count { it == '\n' } <= 1)
        json.decodeFromString<PairingOffer>(body.trim()).also {
            require(it.baseUrl.startsWith("https://") && it.offerId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(it.secret.isNotBlank() && it.secret.length <= 512 && it.expiresAt.isNotBlank())
        }
    } catch (failure: SerializationException) {
        throw InstallerException(InstallPhase.PAIRING_OFFER, safeMessage(InstallPhase.PAIRING_OFFER), false, failure)
    }

    private suspend fun cleanup(session: SshSession, nonce: String): Boolean =
        runCatching { session.exec(FixedCommand.cleanup(nonce)).exitCode == 0 }.getOrDefault(false)

    private suspend fun verifyRollback(session: SshSession, before: InstallProbe): Boolean = runCatching {
        val after = InstallProbeParser.parse(session.exec(FixedCommand.Probe))
        after.companionConfigPresent == before.companionConfigPresent && after.companionVersion == before.companionVersion
    }.getOrDefault(false)

    @Serializable
    private data class BootstrapRequest(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        @SerialName("secure_listen_address") val secureListenAddress: String,
    )

    @Serializable
    private data class PairingOffer(
        @SerialName("base_url") val baseUrl: String,
        @SerialName("certificate_pin") val certificatePin: String,
        @SerialName("offer_id") val offerId: String,
        val secret: String,
        @SerialName("expires_at") val expiresAt: String,
    )
}

private fun secureNonce(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }.also { bytes.fill(0) }
}

private fun safeMessage(phase: InstallPhase): String = when (phase) {
    InstallPhase.VERIFY_ASSET -> "Встроенный пакет companion повреждён"
    InstallPhase.CONNECT -> "Не удалось подключиться к роутеру по подтверждённому SSH-ключу"
    InstallPhase.PROBE -> "Роутер не прошёл безопасную проверку совместимости"
    InstallPhase.UPLOAD -> "Не удалось передать пакет companion"
    InstallPhase.INSTALL -> "Установка companion не завершена"
    InstallPhase.PAIRING_OFFER -> "Не удалось создать одноразовую привязку телефона"
    InstallPhase.PAIRING_EXCHANGE -> "Не удалось подтвердить сертификат и привязать телефон"
    InstallPhase.HEALTH -> "Новый защищённый API не прошёл проверку"
    InstallPhase.SAVE_PROFILE -> "Companion установлен, но профиль телефона не сохранён"
    InstallPhase.CLEANUP -> "Companion установлен, временные файлы требуют очистки"
}

private const val MIN_FREE_MARGIN_BYTES = 16L * 1024 * 1024
