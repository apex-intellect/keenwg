package ru.anisimov.keenwg.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CompanionClient
import ru.anisimov.keenwg.data.companion.CompanionErrorCode
import ru.anisimov.keenwg.data.companion.CompanionException
import ru.anisimov.keenwg.data.companion.DeviceScope
import ru.anisimov.keenwg.data.companion.PairingOffer
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.R

data class DeviceItem(
    val id: String,
    val label: String,
    val scope: DeviceScope,
    val createdAt: String,
    val lastUsed: String?,
    val current: Boolean,
)

data class VisiblePairingOffer(
    val id: String,
    val expiresAt: Instant,
    val qrPayload: String,
)

data class RevokeConfirmation(
    val device: DeviceItem,
    val finalWarning: Boolean,
)

data class DevicesUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val access: CapabilityAccess = CapabilityAccess.NONE,
    val companionVersion: String = "",
    val pinSuffix: String = "",
    val apiStateResource: Int = R.string.devices_api_not_checked,
    val devices: List<DeviceItem> = emptyList(),
    val offer: VisiblePairingOffer? = null,
    val revokeConfirmation: RevokeConfirmation? = null,
    val errorResource: Int? = null,
    val messageResource: Int? = null,
)

class DevicesViewModel(
    private val activeProfileFlow: Flow<ActiveRouterProfile?>,
    private val companion: CompanionClient,
    private val now: () -> Instant = Instant::now,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onCurrentDeviceRevoked: suspend (String) -> Unit = {},
) : ViewModel() {
    constructor() : this(
        activeProfileFlow = ServiceLocator.routerProfileStore.activeProfile,
        companion = ServiceLocator.companionClient,
        onCurrentDeviceRevoked = ServiceLocator.routerProfileStore::clearCompanion,
    )

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()
    private var active: ActiveRouterProfile? = null
    private var rawOffer: PairingOffer? = null

    fun refresh(): Job = viewModelScope.launch {
        val selected = activeProfileFlow.first()
        active = selected
        if (selected == null || selected.profile.companionUrl.isBlank() || selected.secrets.companionToken.isBlank()) {
            _state.value = DevicesUiState(errorResource = R.string.devices_error_not_configured)
            return@launch
        }
        _state.value = _state.value.copy(loading = true, errorResource = null, messageResource = null)
        try {
            val snapshot = withContext(dispatcher) {
                val health = companion.health(selected.profile)
                val capabilities = companion.capabilities(selected.profile, selected.secrets.companionToken)
                val capability = capabilities.capabilities.firstOrNull { it.id == "system.devices" && it.available }
                val access = capability?.access ?: CapabilityAccess.NONE
                val devices = if (access == CapabilityAccess.NONE) emptyList() else {
                    companion.devices(selected.profile, selected.secrets.companionToken)
                }
                Triple(health, access, devices)
            }
            _state.value = _state.value.copy(
                loading = false,
                access = snapshot.second,
                companionVersion = snapshot.first.version,
                pinSuffix = selected.profile.certificatePin.takeLast(10),
                apiStateResource = R.string.devices_api_available,
                devices = snapshot.third.map { device ->
                    DeviceItem(
                        id = device.id,
                        label = device.label,
                        scope = device.scope,
                        createdAt = device.createdAt,
                        lastUsed = device.lastUsed,
                        current = device.id == selected.secrets.companionDeviceId,
                    )
                },
                errorResource = null,
            )
        } catch (failure: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                apiStateResource = R.string.devices_api_error,
                errorResource = safeMessageResource(failure),
            )
        }
    }

    fun createViewerOffer(): Job = viewModelScope.launch {
        val selected = active ?: activeProfileFlow.first()
        if (selected == null || _state.value.access != CapabilityAccess.WRITE || _state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, errorResource = null, messageResource = null)
        try {
            val offer = withContext(dispatcher) {
                companion.createOffer(selected.profile, selected.secrets.companionToken, DeviceScope.VIEWER)
            }
            val expiry = Instant.parse(offer.expiresAt)
            require(expiry.isAfter(now())) { "Pairing offer is already expired" }
            rawOffer = offer
            _state.value = _state.value.copy(
                busy = false,
                offer = VisiblePairingOffer(offer.offerId, expiry, pairingQrPayload(selected.profile, offer)),
            )
        } catch (failure: Exception) {
            rawOffer = null
            _state.value = _state.value.copy(busy = false, errorResource = safeMessageResource(failure))
        }
    }

    fun dismissOffer(): Job = revokeVisibleOffer(R.string.devices_message_offer_revoked)

    fun expireOfferIfNeeded(): Job {
        val offer = _state.value.offer
        if (offer == null || now().isBefore(offer.expiresAt)) return viewModelScope.launch { }
        return revokeVisibleOffer(R.string.devices_message_offer_expired)
    }

    private fun revokeVisibleOffer(messageResource: Int): Job = viewModelScope.launch {
        val selected = active ?: return@launch
        val offer = rawOffer ?: return@launch
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, errorResource = null)
        try {
            withContext(dispatcher) { companion.revokeOffer(selected.profile, selected.secrets.companionToken, offer.offerId) }
            rawOffer = null
            _state.value = _state.value.copy(busy = false, offer = null, messageResource = messageResource)
        } catch (failure: CompanionException) {
            if (failure.code == CompanionErrorCode.NOT_FOUND) {
                rawOffer = null
                _state.value = _state.value.copy(busy = false, offer = null, messageResource = messageResource)
            } else {
                _state.value = _state.value.copy(busy = false, errorResource = safeMessageResource(failure))
            }
        } catch (failure: Exception) {
            _state.value = _state.value.copy(busy = false, errorResource = safeMessageResource(failure))
        }
    }

    fun requestRevoke(deviceId: String) {
        if (_state.value.access != CapabilityAccess.WRITE || _state.value.busy) return
        val device = _state.value.devices.firstOrNull { it.id == deviceId } ?: return
        _state.value = _state.value.copy(revokeConfirmation = RevokeConfirmation(device, finalWarning = false), errorResource = null)
    }

    fun cancelRevoke() {
        if (!_state.value.busy) _state.value = _state.value.copy(revokeConfirmation = null)
    }

    fun confirmRevoke(): Job = viewModelScope.launch {
        val confirmation = _state.value.revokeConfirmation ?: return@launch
        if (confirmation.device.current && !confirmation.finalWarning) {
            _state.value = _state.value.copy(revokeConfirmation = confirmation.copy(finalWarning = true))
            return@launch
        }
        val selected = active ?: return@launch
        _state.value = _state.value.copy(busy = true, errorResource = null)
        try {
            withContext(dispatcher) {
                companion.revokeDevice(selected.profile, selected.secrets.companionToken, confirmation.device.id)
                if (confirmation.device.current) onCurrentDeviceRevoked(selected.profile.id)
            }
            _state.value = _state.value.copy(
                busy = false,
                revokeConfirmation = null,
                devices = _state.value.devices.filterNot { it.id == confirmation.device.id },
                messageResource = R.string.devices_message_access_revoked,
            )
        } catch (failure: CompanionException) {
            val errorResource = if (failure.code == CompanionErrorCode.CONFLICT) {
                R.string.devices_error_last_owner
            } else safeMessageResource(failure)
            _state.value = _state.value.copy(busy = false, revokeConfirmation = null, errorResource = errorResource)
        } catch (failure: Exception) {
            _state.value = _state.value.copy(busy = false, revokeConfirmation = null, errorResource = safeMessageResource(failure))
        }
    }

    private fun safeMessageResource(failure: Exception): Int = when (failure) {
        is CompanionException -> when (failure.code) {
            CompanionErrorCode.UNAUTHORIZED -> R.string.devices_error_phone_revoked
            CompanionErrorCode.FORBIDDEN -> R.string.devices_error_owner_permission
            CompanionErrorCode.UNAVAILABLE -> R.string.devices_error_unavailable
            CompanionErrorCode.CONFLICT -> R.string.devices_error_conflict
            else -> R.string.devices_error_generic
        }
        else -> R.string.devices_error_generic
    }
}
