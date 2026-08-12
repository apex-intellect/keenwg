package ru.anisimov.keenwg.data.capability

import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterSecrets

class CapabilityRegistry {
    fun resolve(
        profile: RouterProfile,
        secrets: RouterSecrets,
        companion: CapabilityDocument? = null,
    ): CapabilityDocument {
        val merged = companion?.capabilities.orEmpty().associateByTo(linkedMapOf()) { it.id }
        val settings = profile.toServerSettings(secrets)

        val rciReady = ServerSettingsValidator.validateForMutation(settings).isEmpty()
        merged.putIfAbsent(
            "access.wireguard",
            direct(
                id = "access.wireguard",
                access = CapabilityAccess.WRITE,
                transport = "rci",
                available = rciReady,
                unavailableReason = "rci_not_configured",
            ),
        )

        val collectorReady = profile.collectorUrl.isNotBlank() &&
            secrets.collectorToken.isNotBlank() &&
            ServerSettingsValidator.validateCollectorUrl(profile.collectorUrl) == null
        merged.putIfAbsent(
            "history.wireguard",
            direct(
                id = "history.wireguard",
                access = CapabilityAccess.READ,
                transport = "collector",
                available = collectorReady,
                unavailableReason = "collector_not_configured",
            ),
        )

        return CapabilityDocument(
            stateVersion = companion?.stateVersion ?: 0u,
            capabilities = merged.values.sortedBy { it.id },
        )
    }

    private fun direct(
        id: String,
        access: CapabilityAccess,
        transport: String,
        available: Boolean,
        unavailableReason: String,
    ) = Capability(
        id = id,
        access = if (available) access else CapabilityAccess.NONE,
        available = available,
        transport = transport,
        reason = unavailableReason.takeUnless { available },
    )
}
