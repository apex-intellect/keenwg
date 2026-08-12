package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.anisimov.keenwg.data.companion.requireCompanionEndpoint
import ru.anisimov.keenwg.data.store.ActiveRouterProfile
import ru.anisimov.keenwg.data.xkeen.XkeenErrorCode
import ru.anisimov.keenwg.data.xkeen.XkeenException
import ru.anisimov.keenwg.domain.model.ServerSettings

class AdaptiveNetworkRepository(
    private val activeProfile: Flow<ActiveRouterProfile?>,
    private val companion: CompanionHomeDeviceGateway,
    private val legacy: NetworkGateway,
) : NetworkGateway {
    private val mutationMutex = Mutex()

    override suspend fun load(settings: ServerSettings): List<NetworkDevice> {
        val active = activeProfile.first()
        if (!active.isPaired()) return legacy.load(settings)
        return companion.load(requireNotNull(active).requireCompanionEndpoint()).devices.map(CompanionHomeDevice::asNetworkDevice)
    }

    override suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String) {
        mutate(settings, mac, ip)
    }

    override suspend fun removeStaticReservation(settings: ServerSettings, mac: String) {
        mutate(settings, mac, null)
    }

    private suspend fun mutate(settings: ServerSettings, mac: String, reservedIp: String?) = mutationMutex.withLock {
        val active = activeProfile.first()
        if (!active.isPaired()) {
            if (reservedIp == null) legacy.removeStaticReservation(settings, mac)
            else legacy.setStaticReservation(settings, mac, reservedIp)
            return@withLock
        }
        val endpoint = requireNotNull(active).requireCompanionEndpoint()
        val document = companion.load(endpoint)
        val device = document.devices.singleOrNull { it.mac == mac.lowercase() }
            ?: throw XkeenException(XkeenErrorCode.NOT_FOUND, "Home device was not found")
        val plan = companion.review(endpoint, document.stateVersion, device.id, reservedIp)
        val result = companion.apply(endpoint, document.stateVersion, device.id, reservedIp, plan.planId)
        when (result.status) {
            "committed" -> Unit
            "rolled_back" -> throw IllegalStateException("The router restored the previous reservation")
            "rejected" -> throw IllegalStateException("The router rejected the reservation")
            "uncertain" -> throw IllegalStateException("The reservation state requires verification")
            else -> throw IllegalStateException("Unsupported reservation result")
        }
    }
}

internal fun ActiveRouterProfile?.isPaired(): Boolean = this != null &&
    profile.companionUrl.isNotBlank() &&
    profile.certificatePin.isNotBlank() &&
    secrets.companionToken.isNotBlank()
