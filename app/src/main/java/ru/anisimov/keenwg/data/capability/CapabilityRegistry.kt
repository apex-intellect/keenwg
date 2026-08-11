package ru.anisimov.keenwg.data.capability

import ru.anisimov.keenwg.data.companion.Capability
import ru.anisimov.keenwg.data.companion.CapabilityAccess
import ru.anisimov.keenwg.data.companion.CapabilityDocument
import ru.anisimov.keenwg.domain.model.RouterProfile

class CapabilityRegistry {
    fun resolve(profile: RouterProfile, companion: CapabilityDocument? = null): CapabilityDocument {
        val merged = companion?.capabilities.orEmpty().associateByTo(linkedMapOf()) { it.id }

        if (profile.host.isNotBlank() && profile.rciPort in 1..65535) {
            merged.putIfAbsent("access.wireguard", optional("access.wireguard", CapabilityAccess.WRITE, "rci"))
        }
        if (profile.collectorUrl.isNotBlank()) {
            merged.putIfAbsent("history.wireguard", optional("history.wireguard", CapabilityAccess.READ, "collector"))
        }

        return CapabilityDocument(
            stateVersion = companion?.stateVersion ?: 0u,
            capabilities = merged.values.sortedBy { it.id },
        )
    }

    private fun optional(id: String, access: CapabilityAccess, transport: String) = Capability(
        id = id,
        access = access,
        available = true,
        transport = transport,
    )
}
