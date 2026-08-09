package ru.anisimov.keenwg.data.capability

import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.model.RouterProfile

class CapabilityRegistry {
    fun resolve(profile: RouterProfile, companion: CapabilityDocument? = null): CapabilityDocument {
        val merged = companion?.capabilities.orEmpty().associateByTo(linkedMapOf()) { it.id }

        if (profile.host.isNotBlank() && profile.rciPort in 1..65535) {
            merged.putIfAbsent("access.wireguard", legacy("access.wireguard", CapabilityAccess.WRITE, "legacy-rci"))
        }
        if (profile.collectorUrl.isNotBlank()) {
            merged.putIfAbsent("history.wireguard", legacy("history.wireguard", CapabilityAccess.READ, "legacy-collector"))
        }
        if (profile.legacyXkeenUrl.isNotBlank()) {
            listOf("connections.xkeen", "routes.domains", "routes.exclusions").forEach { id ->
                merged.putIfAbsent(id, legacy(id, CapabilityAccess.WRITE, "legacy-xkeen"))
            }
        }

        return CapabilityDocument(
            stateVersion = companion?.stateVersion ?: 0u,
            capabilities = merged.values.sortedBy { it.id },
        )
    }

    private fun legacy(id: String, access: CapabilityAccess, transport: String) = Capability(
        id = id,
        access = access,
        available = true,
        transport = transport,
    )
}
