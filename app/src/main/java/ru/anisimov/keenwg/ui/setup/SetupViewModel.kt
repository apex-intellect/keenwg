package ru.anisimov.keenwg.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.installer.HostKeyObservation
import ru.anisimov.keenwg.data.installer.InstallMode
import ru.anisimov.keenwg.data.installer.InstallPhase
import ru.anisimov.keenwg.data.installer.InstallPreparation
import ru.anisimov.keenwg.data.installer.InstallerException
import ru.anisimov.keenwg.data.installer.InstallerWorkflow
import ru.anisimov.keenwg.data.installer.SshEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile

class SetupViewModel(
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val workflow: InstallerWorkflow,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    constructor() : this(
        activeProfileFlow = ServiceLocator.routerProfileStore.activeProfile,
        workflow = ServiceLocator.installerCoordinator,
    )

    private val _state = MutableStateFlow<SetupState>(SetupState.Credentials)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    val suggestedHost: StateFlow<String> = activeProfileFlow.map {
        it?.profile?.let { profile -> profile.sshHost.ifBlank { profile.host } }.orEmpty()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val suggestedPort: StateFlow<Int> = activeProfileFlow.map { it?.profile?.sshPort ?: 222 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 222)
    val suggestedUsername: StateFlow<String> = activeProfileFlow.map { it?.profile?.sshUsername ?: "root" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "root")

    private var credential: ByteArray? = null
    private var endpoint: SshEndpoint? = null
    private var deviceLabel: String = ""
    private var observedKey: HostKeyObservation? = null
    private var operation: Job? = null

    fun connect(candidate: SshEndpoint, password: ByteArray, label: String): Job {
        if (operation?.isActive == true) {
            password.fill(0)
            return requireNotNull(operation)
        }
        clearCredential()
        credential = password.copyOf()
        password.fill(0)
        endpoint = candidate
        deviceLabel = label.take(80)
        observedKey = null
        _state.value = SetupState.Checking(SetupProgress.CONNECTING, InstallPhase.CONNECT)
        return viewModelScope.launch { observeAndContinue() }.also { operation = it }
    }

    fun retryPrerequisites(): Job? {
        if (_state.value !is SetupState.PrerequisiteMissing || credential == null || operation?.isActive == true) return null
        _state.value = SetupState.Checking(SetupProgress.CONNECTING, InstallPhase.CONNECT)
        return viewModelScope.launch { observeAndContinue() }.also { operation = it }
    }

    fun acceptChangedHostKey(): Job? {
        val changed = _state.value as? SetupState.HostKeyChanged ?: return null
        if (credential == null || operation?.isActive == true) return null
        observedKey = changed.observed
        _state.value = SetupState.Checking(SetupProgress.CHECKING_ROUTER, InstallPhase.PROBE)
        return viewModelScope.launch { prepareAndInstall(changed.observed) }.also { operation = it }
    }

    fun reset() {
        operation?.cancel()
        operation = null
        clearCredential()
        endpoint = null
        deviceLabel = ""
        observedKey = null
        _state.value = SetupState.Credentials
    }

    private suspend fun observeAndContinue() {
        val active = activeProfileFlow.first()
        val candidate = endpoint
        if (active == null || candidate == null || credential == null) {
            fail(InstallPhase.CONNECT, true)
            return
        }
        try {
            val observed = withContext(dispatcher) { workflow.observeHostKey(candidate) }
            observedKey = observed
            val expected = active.profile.pinnedHostKey()
            if (expected != null && expected != observed) {
                _state.value = SetupState.HostKeyChanged(expected, observed)
                return
            }
            prepareAndInstall(observed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InstallerException) {
            fail(failure.phase, failure.rollbackVerified)
        } catch (_: Exception) {
            fail(InstallPhase.CONNECT, true)
        }
    }

    private suspend fun prepareAndInstall(hostKey: HostKeyObservation) {
        val active = activeProfileFlow.first()
        val candidate = endpoint
        val secret = credential
        if (active == null || candidate == null || secret == null) {
            fail(InstallPhase.PROBE, true)
            return
        }
        _state.value = SetupState.Checking(SetupProgress.CHECKING_ROUTER, InstallPhase.PROBE)
        try {
            val preparation = withContext(dispatcher) {
                workflow.prepare(active.profile.id, candidate, hostKey, secret.copyOf())
            }
            val missing = preparation.missingPrerequisites()
            if (missing.isNotEmpty()) {
                _state.value = SetupState.PrerequisiteMissing(preparation.probe, missing)
                return
            }
            _state.value = SetupState.Checking(SetupProgress.PREPARING_ACCESS, InstallPhase.VERIFY_ASSET)
            val report = withContext(dispatcher) {
                workflow.install(preparation, secret.copyOf(), deviceLabel) { phase ->
                    _state.value = SetupState.Checking(phase.toSetupProgress(), phase)
                }
            }
            clearCredential()
            _state.value = SetupState.Completed(active.profile.id, report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InstallerException) {
            fail(failure.phase, failure.rollbackVerified)
        } catch (_: Exception) {
            fail(InstallPhase.PROBE, true)
        }
    }

    private fun fail(phase: InstallPhase, rollbackVerified: Boolean) {
        clearCredential()
        _state.value = SetupState.Failed(phase, rollbackVerified)
    }

    private fun clearCredential() {
        credential?.fill(0)
        credential = null
    }

    override fun onCleared() {
        clearCredential()
        super.onCleared()
    }
}

private fun ru.anisimov.keenwg.domain.model.RouterProfile.pinnedHostKey(): HostKeyObservation? {
    if (sshHostKeyAlgorithm.isBlank() || sshHostKeySha256.isBlank()) return null
    return HostKeyObservation(sshHostKeyAlgorithm, sshHostKeySha256)
}

private fun InstallPreparation.missingPrerequisites(): Set<SetupPrerequisite> = buildSet {
    if (plan.mode != InstallMode.PAIR_ONLY) {
        if (!probe.entwarePresent) add(SetupPrerequisite.ENTWARE)
        if (probe.optFreeBytes < plan.requiredBytes) add(SetupPrerequisite.STORAGE)
    }
}
