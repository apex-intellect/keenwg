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
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
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
)

class CompanionUpdateViewModel(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val gateway: CompanionUpdateGateway,
    private val loadAsset: () -> VerifiedCompanionAsset,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    constructor() : this(
        ServiceLocator.routerProfileStore.activeProfile,
        ServiceLocator.companionUpdateGateway,
        ServiceLocator.companionAssetVerifier::load,
    )

    private val _state = MutableStateFlow(CompanionUpdateUiState())
    val state: StateFlow<CompanionUpdateUiState> = _state.asStateFlow()
    private var operation: Job? = null

    init { check() }

    fun check(): Job = startOperation {
        _state.value = CompanionUpdateUiState(UpdatePhase.LOADING)
        val endpoint = activeProfile.first()?.let { runCatching { it.requireCompanionEndpoint() }.getOrNull() }
        if (endpoint == null) {
            _state.value = CompanionUpdateUiState(UpdatePhase.ERROR)
            return@startOperation
        }
        val bundledVersion = loadBundledVersion() ?: return@startOperation
        val status = try {
            gateway.status(endpoint)
        } catch (failure: CompanionUpdateException) {
            _state.value = CompanionUpdateUiState(
                if (failure.code == CompanionUpdateError.UNSUPPORTED) UpdatePhase.NEEDS_PASSWORD else UpdatePhase.ERROR,
                targetVersion = bundledVersion,
            )
            return@startOperation
        } catch (_: Exception) {
            _state.value = CompanionUpdateUiState(UpdatePhase.ERROR, targetVersion = bundledVersion)
            return@startOperation
        }
        if (!status.supported) {
            _state.value = CompanionUpdateUiState(UpdatePhase.NEEDS_PASSWORD, status.currentVersion, bundledVersion)
            return@startOperation
        }
        val phase = if (compareUpdateVersions(bundledVersion, status.currentVersion) > 0) {
            UpdatePhase.AVAILABLE
        } else {
            UpdatePhase.UP_TO_DATE
        }
        _state.value = CompanionUpdateUiState(phase, status.currentVersion, bundledVersion)
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
                    _state.value = _state.value.copy(phase = UpdatePhase.SUCCESS, currentVersion = expected)
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
