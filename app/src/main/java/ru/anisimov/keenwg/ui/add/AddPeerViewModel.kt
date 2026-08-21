package ru.anisimov.keenwg.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.anisimov.keenwg.data.AddResult
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.domain.IpAllocator
import ru.anisimov.keenwg.domain.model.Peer
import ru.anisimov.keenwg.domain.model.ServerSettings
import ru.anisimov.keenwg.domain.normalizePeerName
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.AccessPolicyValidator
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.R

interface AddPeerGateway {
    suspend fun list(settings: ServerSettings): List<Peer>
    suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy): AddResult
}

interface AddPeerSettingsGateway {
    suspend fun settings(): ServerSettings
}

enum class AddPeerStage {
    FORM,
    PREPARING,
    APPLYING_AND_VERIFYING,
    REVIEW,
    SUCCESS,
}

data class AddPeerUiState(
    val name: String = "",
    val ip: String = "",
    val stage: AddPeerStage = AddPeerStage.FORM,
    val preparing: Boolean = false,
    val busy: Boolean = false,
    val errorResource: Int? = null,
    val result: AddResult? = null,
    val allowedNetworks: String = "0.0.0.0/0",
    val dnsServers: String = "",
    val expiryDays: String = "",
    val historyEnabled: Boolean = true,
    val reviewedPolicy: AccessPolicy? = null,
    val companionUpdateRequired: Boolean = false,
)

class AddPeerViewModel @JvmOverloads constructor(
    private val gateway: AddPeerGateway = serviceAddPeerGateway(),
    private val settingsGateway: AddPeerSettingsGateway = object : AddPeerSettingsGateway {
        override suspend fun settings() = ServiceLocator.settingsStore.settings.first()
    },
) : ViewModel() {
    private val _state = MutableStateFlow(AddPeerUiState())
    val state: StateFlow<AddPeerUiState> = _state.asStateFlow()

    fun onNameChange(value: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(name = value, errorResource = null, stage = AddPeerStage.FORM, reviewedPolicy = null, companionUpdateRequired = false)
    }

    fun onIpChange(value: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(ip = value, errorResource = null, stage = AddPeerStage.FORM, reviewedPolicy = null, companionUpdateRequired = false)
    }

    fun onAllowedNetworksChange(value: String) = editPolicy { copy(allowedNetworks = value) }
    fun onDnsServersChange(value: String) = editPolicy { copy(dnsServers = value) }
    fun onExpiryDaysChange(value: String) = editPolicy { copy(expiryDays = value.filter(Char::isDigit).take(4)) }
    fun onHistoryEnabledChange(value: Boolean) = editPolicy { copy(historyEnabled = value) }

    fun review(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L) {
        val submitted = _state.value
        if (submitted.busy || submitted.result != null) return
        if (submitted.name.isBlank()) {
            _state.value = submitted.copy(errorResource = R.string.add_error_name_required)
            return
        }
        if (submitted.expiryDays.isNotBlank() && submitted.expiryDays.toLongOrNull() == null) {
            _state.value = submitted.copy(errorResource = R.string.add_error_expiry_invalid)
            return
        }
        val requestedDays = submitted.expiryDays.takeIf(String::isNotBlank)?.toLongOrNull()
        if (requestedDays != null && requestedDays !in 1..3650) {
            _state.value = submitted.copy(errorResource = R.string.add_error_expiry_range)
            return
        }
        runCatching {
            AccessPolicy(
                allowedNetworks = splitPolicyValues(submitted.allowedNetworks),
                dnsServers = splitPolicyValues(submitted.dnsServers),
                expiresAtEpochSeconds = requestedDays?.let { nowEpochSeconds + it * 86_400L },
                historyEnabled = submitted.historyEnabled,
            ).also { AccessPolicyValidator.requireValid(it, nowEpochSeconds) }
        }.onSuccess { policy ->
            _state.value = submitted.copy(stage = AddPeerStage.REVIEW, reviewedPolicy = policy, errorResource = null)
        }.onFailure {
            _state.value = submitted.copy(stage = AddPeerStage.FORM, reviewedPolicy = null, errorResource = R.string.add_error_policy_invalid)
        }
    }

    fun cancelReview() {
        if (!_state.value.busy) _state.value = _state.value.copy(stage = AddPeerStage.FORM, reviewedPolicy = null)
    }

    fun prepare(): Job = viewModelScope.launch {
        if (_state.value.preparing || _state.value.busy || _state.value.result != null) return@launch
        _state.value = _state.value.copy(
            preparing = true,
            stage = AddPeerStage.PREPARING,
            errorResource = null,
            companionUpdateRequired = false,
        )
        runCatching {
            val settings = settingsGateway.settings()
            val peers = gateway.list(settings)
            val taken = peers.mapNotNull(Peer::ip).toSet()
            IpAllocator.nextFreeIp(settings.subnetBase, taken)
                ?: throw NoFreeWireGuardAddress()
        }.onSuccess { suggested ->
            _state.value = _state.value.copy(
                ip = _state.value.ip.ifBlank { suggested },
                preparing = false,
                stage = AddPeerStage.FORM,
                companionUpdateRequired = false,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                preparing = false,
                stage = AddPeerStage.FORM,
                errorResource = if (error is NoFreeWireGuardAddress) R.string.add_error_no_free_ip else R.string.add_error_prepare_failed,
            )
        }
    }

    fun create(): Job = viewModelScope.launch {
        val submitted = _state.value
        if (submitted.busy || submitted.result != null || submitted.stage != AddPeerStage.REVIEW) return@launch
        val policy = submitted.reviewedPolicy ?: return@launch
        _state.value = submitted.copy(
            busy = true,
            stage = AddPeerStage.APPLYING_AND_VERIFYING,
            errorResource = null,
        )
        runCatching {
            val settings = settingsGateway.settings()
            gateway.add(
                settings = settings,
                name = normalizePeerName(submitted.name),
                ip = submitted.ip.ifBlank { null },
                policy = policy,
            )
        }.onSuccess { result ->
            _state.value = _state.value.copy(
                busy = false,
                stage = AddPeerStage.SUCCESS,
                result = result,
                companionUpdateRequired = false,
            )
        }.onFailure { failure ->
            val updateRequired = failure is XkeenException && failure.code == XkeenErrorCode.NOT_FOUND
            _state.value = _state.value.copy(
                busy = false,
                stage = AddPeerStage.FORM,
                errorResource = if (updateRequired) R.string.add_error_companion_update_required else R.string.add_error_create_failed,
                companionUpdateRequired = updateRequired,
            )
        }
    }

    fun reset() {
        if (_state.value.busy) return
        _state.value = AddPeerUiState()
    }

    private fun editPolicy(transform: AddPeerUiState.() -> AddPeerUiState) {
        if (_state.value.busy) return
        _state.value = _state.value.transform().copy(
            stage = AddPeerStage.FORM,
            reviewedPolicy = null,
            errorResource = null,
            companionUpdateRequired = false,
        )
    }
}

private fun splitPolicyValues(value: String): List<String> = value
    .split(',', '\n', ' ')
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun serviceAddPeerGateway(): AddPeerGateway = object : AddPeerGateway {
    override suspend fun list(settings: ServerSettings) = ServiceLocator.repository.list(settings)
    override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy) =
        ServiceLocator.repository.add(settings, name, ip, policy)
}

private class NoFreeWireGuardAddress : IllegalStateException()
