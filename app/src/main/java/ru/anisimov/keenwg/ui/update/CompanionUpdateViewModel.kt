package ru.anisimov.keenwg.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionErrorCode
import ru.anisimov.keenwg.data.companion.CompanionException
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.companion.requireCompanionTarget
import ru.anisimov.keenwg.data.installer.VerifiedCompanionAsset
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.update.CompanionUpdateError
import ru.anisimov.keenwg.data.update.CompanionUpdateException
import ru.anisimov.keenwg.data.update.CompanionUpdateGateway
import ru.anisimov.keenwg.data.update.CompanionUpdateStatus

data class CompanionUpdateUiState(
    val phase: UpdatePhase = UpdatePhase.LOADING,
    val currentVersion: String? = null,
    val targetVersion: String? = null,
    val checks: List<CompanionStatusCheck> = CompanionCheckId.entries.map {
        CompanionStatusCheck(it, CompanionCheckState.CHECKING)
    },
)

class CompanionUpdateViewModel(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val gateway: CompanionUpdateGateway,
    private val loadAsset: () -> VerifiedCompanionAsset,
    private val companion: CompanionClient,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    constructor() : this(
        ServiceLocator.routerProfileStore.activeProfile,
        ServiceLocator.companionUpdateGateway,
        ServiceLocator.companionAssetVerifier::load,
        ServiceLocator.companionClient,
    )

    private val _state = MutableStateFlow(CompanionUpdateUiState())
    val state: StateFlow<CompanionUpdateUiState> = _state.asStateFlow()
    private var operation: Job? = null

    init { check() }

    fun check(): Job = startOperation {
        _state.value = CompanionUpdateUiState(UpdatePhase.LOADING)
        val bundledVersion = loadBundledVersion() ?: return@startOperation
        val active = activeProfile.first()
        if (active == null || runCatching { active.profile.requireCompanionTarget() }.isFailure) {
            publish(
                CompanionStatusFacts(
                    endpointConfigured = false,
                    serviceReachable = null,
                    storageReady = null,
                    authorizationValid = null,
                    apiCompatible = null,
                    installedVersion = null,
                    bundledVersion = bundledVersion,
                ),
                updaterSupported = null,
            )
            return@startOperation
        }
        _state.value = CompanionUpdateUiState(
            phase = UpdatePhase.LOADING,
            targetVersion = bundledVersion,
            checks = loadingChecks(),
        )
        val health = try {
            companion.health(active.profile)
        } catch (failure: CompanionException) {
            val incompatible = failure.code == CompanionErrorCode.UNSUPPORTED_SCHEMA ||
                failure.code == CompanionErrorCode.PROTOCOL
            publish(
                CompanionStatusFacts(
                    endpointConfigured = true,
                    serviceReachable = if (incompatible) true else false,
                    storageReady = null,
                    authorizationValid = null,
                    apiCompatible = if (incompatible) false else null,
                    installedVersion = null,
                    bundledVersion = bundledVersion,
                ),
                updaterSupported = null,
                phaseOverride = if (incompatible) UpdatePhase.INCOMPATIBLE else UpdatePhase.UNREACHABLE,
            )
            return@startOperation
        } catch (_: Exception) {
            publish(unreachableFacts(bundledVersion), updaterSupported = null, phaseOverride = UpdatePhase.UNREACHABLE)
            return@startOperation
        }

        val baseFacts = CompanionStatusFacts(
            endpointConfigured = true,
            serviceReachable = true,
            storageReady = health.storage == "ok",
            authorizationValid = null,
            apiCompatible = null,
            installedVersion = health.version,
            bundledVersion = bundledVersion,
        )
        val endpoint = runCatching { active.requireCompanionEndpoint() }.getOrNull()
        if (endpoint == null) {
            publish(baseFacts.copy(authorizationValid = false), updaterSupported = null)
            return@startOperation
        }
        val authorizedFacts = try {
            companion.capabilities(active.profile, endpoint.deviceToken)
            baseFacts.copy(authorizationValid = true, apiCompatible = true)
        } catch (failure: CompanionException) {
            when (failure.code) {
                CompanionErrorCode.UNAUTHORIZED,
                CompanionErrorCode.FORBIDDEN,
                -> publish(baseFacts.copy(authorizationValid = false), updaterSupported = null)

                CompanionErrorCode.UNSUPPORTED_SCHEMA,
                CompanionErrorCode.PROTOCOL,
                -> publish(
                    baseFacts.copy(authorizationValid = true, apiCompatible = false),
                    updaterSupported = null,
                )

                else -> publish(
                    baseFacts,
                    updaterSupported = null,
                    phaseOverride = UpdatePhase.CHECK_FAILED,
                )
            }
            return@startOperation
        } catch (_: Exception) {
            publish(baseFacts, updaterSupported = null, phaseOverride = UpdatePhase.CHECK_FAILED)
            return@startOperation
        }

        val status = try {
            gateway.status(endpoint)
        } catch (failure: CompanionUpdateException) {
            val needsPairing = failure.code == CompanionUpdateError.UNAUTHORIZED ||
                failure.code == CompanionUpdateError.FORBIDDEN
            publish(
                if (needsPairing) authorizedFacts.copy(authorizationValid = false) else authorizedFacts,
                updaterSupported = if (failure.code == CompanionUpdateError.UNSUPPORTED) false else null,
                phaseOverride = when {
                    needsPairing -> UpdatePhase.PAIRING_REQUIRED
                    failure.code == CompanionUpdateError.UNSUPPORTED -> UpdatePhase.NEEDS_PASSWORD
                    else -> UpdatePhase.CHECK_FAILED
                },
                updateNeedsAttention = !needsPairing,
            )
            return@startOperation
        } catch (_: Exception) {
            publish(
                authorizedFacts,
                updaterSupported = null,
                phaseOverride = UpdatePhase.CHECK_FAILED,
                updateNeedsAttention = true,
            )
            return@startOperation
        }
        publish(
            authorizedFacts.copy(installedVersion = status.currentVersion),
            updaterSupported = status.supported,
            updateNeedsAttention = !status.supported,
        )
    }

    fun install(): Job = startOperation {
        val active = activeProfile.first()
        val endpoint = active?.let { runCatching { it.requireCompanionEndpoint() }.getOrNull() }
        val expected = _state.value.targetVersion
        if (endpoint == null || expected == null) {
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR)
            return@startOperation
        }
        _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING)
        val asset = try { loadAsset() } catch (_: Exception) {
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR)
            return@startOperation
        }
        try {
            if (asset.manifest.version != expected) {
                _state.value = _state.value.copy(phase = UpdatePhase.ERROR)
                return@startOperation
            }
            _state.value = _state.value.copy(phase = UpdatePhase.UPLOADING)
            val accepted = gateway.install(endpoint, asset)
            if (accepted.targetVersion != expected) {
                _state.value = _state.value.copy(phase = UpdatePhase.UNCERTAIN)
                return@startOperation
            }
        } catch (_: CompanionUpdateException) {
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR)
            return@startOperation
        } catch (_: Exception) {
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR)
            return@startOperation
        } finally {
            asset.bytes.fill(0)
        }
        _state.value = _state.value.copy(phase = UpdatePhase.INSTALLING)
        pollUntilFinished(endpoint, expected)
    }

    private suspend fun pollUntilFinished(
        endpoint: ru.anisimov.keenwg.data.companion.CompanionEndpoint,
        expected: String,
    ) {
        var lastStatus: CompanionUpdateStatus? = null
        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            delayMillis(pollDelay(attempt))
            val status = runCatching { gateway.status(endpoint) }.getOrNull()
            if (status == null) {
                _state.value = _state.value.copy(phase = UpdatePhase.RECONNECTING)
                continue
            }
            lastStatus = status
            when {
                status.result == "failed" -> {
                    _state.value = _state.value.copy(phase = UpdatePhase.ROLLED_BACK, currentVersion = status.currentVersion)
                    return
                }
                status.currentVersion == expected && status.result == "installed" -> {
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.SUCCESS,
                        currentVersion = expected,
                        checks = _state.value.checks.map {
                            if (it.id == CompanionCheckId.UPDATE) it.copy(state = CompanionCheckState.OK) else it
                        },
                    )
                    return
                }
                else -> _state.value = _state.value.copy(phase = UpdatePhase.INSTALLING, currentVersion = status.currentVersion)
            }
        }
        _state.value = _state.value.copy(
            phase = UpdatePhase.UNCERTAIN,
            currentVersion = lastStatus?.currentVersion ?: _state.value.currentVersion,
        )
    }

    private fun loadBundledVersion(): String? {
        val asset = try { loadAsset() } catch (_: Exception) {
            _state.value = CompanionUpdateUiState(UpdatePhase.ERROR)
            return null
        }
        return try { asset.manifest.version } finally { asset.bytes.fill(0) }
    }

    private fun publish(
        facts: CompanionStatusFacts,
        updaterSupported: Boolean?,
        phaseOverride: UpdatePhase? = null,
        updateNeedsAttention: Boolean = false,
    ) {
        val checks = companionStatusChecks(facts).map {
            if (updateNeedsAttention && it.id == CompanionCheckId.UPDATE) {
                it.copy(state = CompanionCheckState.ATTENTION)
            } else {
                it
            }
        }
        _state.value = CompanionUpdateUiState(
            phase = phaseOverride ?: companionUpdatePhase(facts, updaterSupported),
            currentVersion = facts.installedVersion,
            targetVersion = facts.bundledVersion,
            checks = checks,
        )
    }

    private fun unreachableFacts(bundledVersion: String) = CompanionStatusFacts(
        endpointConfigured = true,
        serviceReachable = false,
        storageReady = null,
        authorizationValid = null,
        apiCompatible = null,
        installedVersion = null,
        bundledVersion = bundledVersion,
    )

    private fun loadingChecks(): List<CompanionStatusCheck> = CompanionCheckId.entries.map {
        CompanionStatusCheck(
            id = it,
            state = if (it == CompanionCheckId.CONFIGURATION) {
                CompanionCheckState.OK
            } else {
                CompanionCheckState.CHECKING
            },
        )
    }

    private fun startOperation(block: suspend () -> Unit): Job {
        operation?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch { block() }.also { operation = it }
    }

    private fun pollDelay(attempt: Int): Long = when {
        attempt < 2 -> 500L
        attempt < 6 -> 1_000L
        else -> 5_000L
    }

    companion object { private const val MAX_POLL_ATTEMPTS = 28 }
}
