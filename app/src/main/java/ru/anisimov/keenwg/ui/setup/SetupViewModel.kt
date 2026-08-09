package ru.anisimov.keenwg.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()
    val suggestedHost: StateFlow<String> = activeProfileFlow.map { it?.profile?.host.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private var endpoint: SshEndpoint? = null
    private var hostKey: HostKeyObservation? = null
    private var preparation: InstallPreparation? = null

    fun observeHostKey(candidate: SshEndpoint): Job = viewModelScope.launch {
        if (_state.value is SetupState.Probing || _state.value is SetupState.Installing) return@launch
        _state.value = SetupState.Probing("Получаем ключ SSH без входа в систему")
        try {
            val observed = withContext(dispatcher) { workflow.observeHostKey(candidate) }
            endpoint = candidate
            hostKey = observed
            preparation = null
            _state.value = SetupState.HostKeyApproval(observed)
        } catch (_: Exception) {
            _state.value = SetupState.Failed(
                InstallPhase.CONNECT,
                "Не удалось получить ключ SSH роутера",
                rollbackVerified = true,
            )
        }
    }

    fun approveHostKey(password: ByteArray): Job = viewModelScope.launch {
        val active = activeProfileFlow.first()
        val approvedEndpoint = endpoint
        val approvedKey = hostKey
        if (_state.value !is SetupState.HostKeyApproval || active == null || approvedEndpoint == null || approvedKey == null) {
            password.fill(0)
            _state.value = SetupState.Failed(InstallPhase.PROBE, "Активный профиль роутера недоступен", true)
            return@launch
        }
        _state.value = SetupState.Probing("Проверяем архитектуру, Entware и свободное место")
        try {
            val ready = withContext(dispatcher) {
                workflow.prepare(active.profile.id, approvedEndpoint, approvedKey, password)
            }
            preparation = ready
            _state.value = SetupState.Review(ready.probe, ready.plan)
        } catch (failure: InstallerException) {
            _state.value = failure.toSetupState()
        } catch (_: Exception) {
            _state.value = SetupState.Failed(InstallPhase.PROBE, "Проверка роутера не завершена", true)
        } finally {
            password.fill(0)
        }
    }

    fun confirmInstall(password: ByteArray, deviceLabel: String): Job = viewModelScope.launch {
        val ready = preparation
        if (_state.value !is SetupState.Review || ready == null) {
            password.fill(0)
            return@launch
        }
        _state.value = SetupState.Installing(InstallPhase.VERIFY_ASSET, phaseProgress(InstallPhase.VERIFY_ASSET))
        try {
            val report = withContext(dispatcher) {
                workflow.install(ready, password, deviceLabel) { phase ->
                    _state.value = SetupState.Installing(phase, phaseProgress(phase))
                }
            }
            _state.value = SetupState.Completed(ready.profileId, report)
        } catch (failure: InstallerException) {
            _state.value = failure.toSetupState()
        } catch (_: Exception) {
            _state.value = SetupState.Failed(InstallPhase.INSTALL, "Установка не завершена", false)
        } finally {
            password.fill(0)
        }
    }

    fun reset() {
        if (_state.value is SetupState.Installing) return
        endpoint = null
        hostKey = null
        preparation = null
        _state.value = SetupState.Idle
    }

    private fun InstallerException.toSetupState() = SetupState.Failed(phase, safeMessage, rollbackVerified)

    private fun phaseProgress(phase: InstallPhase): Float =
        (phase.ordinal + 1).toFloat() / InstallPhase.entries.size.toFloat()
}
