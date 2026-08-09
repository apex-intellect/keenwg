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
import ru.anisimov.keenwg.domain.model.AccessPolicy
import ru.anisimov.keenwg.domain.model.AccessPolicyValidator

interface AddPeerGateway {
    suspend fun list(settings: ServerSettings): List<Peer>
    suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy): AddResult
}

fun interface AddPeerSettingsGateway {
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
    val error: String? = null,
    val result: AddResult? = null,
    val allowedNetworks: String = "0.0.0.0/0",
    val dnsServers: String = "",
    val expiryDays: String = "",
    val historyEnabled: Boolean = true,
    val reviewedPolicy: AccessPolicy? = null,
)

class AddPeerViewModel @JvmOverloads constructor(
    private val gateway: AddPeerGateway = serviceAddPeerGateway(),
    private val settingsGateway: AddPeerSettingsGateway = AddPeerSettingsGateway {
        ServiceLocator.settingsStore.settings.first()
    },
) : ViewModel() {
    private val _state = MutableStateFlow(AddPeerUiState())
    val state: StateFlow<AddPeerUiState> = _state.asStateFlow()

    fun onNameChange(value: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(name = value, error = null, stage = AddPeerStage.FORM, reviewedPolicy = null)
    }

    fun onIpChange(value: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(ip = value, error = null, stage = AddPeerStage.FORM, reviewedPolicy = null)
    }

    fun onAllowedNetworksChange(value: String) = editPolicy { copy(allowedNetworks = value) }
    fun onDnsServersChange(value: String) = editPolicy { copy(dnsServers = value) }
    fun onExpiryDaysChange(value: String) = editPolicy { copy(expiryDays = value.filter(Char::isDigit).take(4)) }
    fun onHistoryEnabledChange(value: Boolean) = editPolicy { copy(historyEnabled = value) }

    fun review(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L) {
        val submitted = _state.value
        if (submitted.busy || submitted.result != null) return
        if (submitted.name.isBlank()) {
            _state.value = submitted.copy(error = "Укажите название устройства")
            return
        }
        runCatching {
            val days = submitted.expiryDays.takeIf(String::isNotBlank)?.toLongOrNull()
                ?: if (submitted.expiryDays.isBlank()) null else error("Некорректный срок действия")
            if (days != null) require(days in 1..3650) { "Срок действия должен быть от 1 до 3650 дней" }
            AccessPolicy(
                allowedNetworks = splitPolicyValues(submitted.allowedNetworks),
                dnsServers = splitPolicyValues(submitted.dnsServers),
                expiresAtEpochSeconds = days?.let { nowEpochSeconds + it * 86_400L },
                historyEnabled = submitted.historyEnabled,
            ).also { AccessPolicyValidator.requireValid(it, nowEpochSeconds) }
        }.onSuccess { policy ->
            _state.value = submitted.copy(stage = AddPeerStage.REVIEW, reviewedPolicy = policy, error = null)
        }.onFailure { error ->
            _state.value = submitted.copy(stage = AddPeerStage.FORM, reviewedPolicy = null, error = error.safeMessage())
        }
    }

    fun cancelReview() {
        if (!_state.value.busy) _state.value = _state.value.copy(stage = AddPeerStage.FORM, reviewedPolicy = null)
    }

    fun prepare(): Job = viewModelScope.launch {
        if (_state.value.preparing || _state.value.busy || _state.value.result != null) return@launch
        _state.value = _state.value.copy(preparing = true, stage = AddPeerStage.PREPARING, error = null)
        runCatching {
            val settings = settingsGateway.settings()
            val taken = gateway.list(settings).mapNotNull(Peer::ip).toSet()
            IpAllocator.nextFreeIp(settings.subnetBase, taken)
                ?: error("В подсети WireGuard нет свободного адреса")
        }.onSuccess { suggested ->
            _state.value = _state.value.copy(
                ip = _state.value.ip.ifBlank { suggested },
                preparing = false,
                stage = AddPeerStage.FORM,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                preparing = false,
                stage = AddPeerStage.FORM,
                error = error.safeMessage(),
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
            error = null,
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
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                busy = false,
                stage = AddPeerStage.FORM,
                error = error.safeMessage(),
            )
        }
    }

    fun reset() {
        if (_state.value.busy) return
        _state.value = AddPeerUiState()
    }

    private fun editPolicy(transform: AddPeerUiState.() -> AddPeerUiState) {
        if (_state.value.busy) return
        _state.value = _state.value.transform().copy(stage = AddPeerStage.FORM, reviewedPolicy = null, error = null)
    }
}

private fun splitPolicyValues(value: String): List<String> = value
    .split(',', '\n', ' ')
    .map(String::trim)
    .filter(String::isNotEmpty)

/** Converts a human label into KeenOS' conservative ASCII peer-name format. */
internal fun normalizePeerName(value: String): String {
    val transliterated = buildString {
        value.trim().lowercase().forEach { char ->
            append(CYRILLIC_TO_LATIN[char] ?: char)
        }
    }
    return transliterated
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .replace(Regex("[-_]{2,}"), "-")
        .trim('-', '_')
        .take(64)
        .trimEnd('-', '_')
        .ifBlank { "device" }
}

private val CYRILLIC_TO_LATIN = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
    'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
)

private fun serviceAddPeerGateway(): AddPeerGateway = object : AddPeerGateway {
    override suspend fun list(settings: ServerSettings) = ServiceLocator.repository.list(settings)
    override suspend fun add(settings: ServerSettings, name: String, ip: String?, policy: AccessPolicy) =
        ServiceLocator.repository.add(settings, name, ip, policy)
}

private fun Throwable.safeMessage(): String = message ?: "Не удалось создать доступ"
