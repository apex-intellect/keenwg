package ru.anisimov.keenwg.data.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.anisimov.keenwg.domain.ServerSettingsValidator
import ru.anisimov.keenwg.domain.model.ServerSettings

data class DiscoveryPreview(
    val interfaceId: String,
    val serverPublicKey: String,
    val reviewedEndpoint: String,
    val endpointCandidate: String?,
) {
    fun applyTo(current: ServerSettings, acceptEndpointCandidate: Boolean = false): ServerSettings = current.copy(
        interfaceId = interfaceId,
        serverPublicKey = serverPublicKey,
        endpoint = when {
            reviewedEndpoint.isNotBlank() -> reviewedEndpoint
            acceptEndpointCandidate -> endpointCandidate.orEmpty()
            else -> current.endpoint
        },
    )
}

object RouterDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    fun discover(showInterfacesJson: String, current: ServerSettings): DiscoveryPreview {
        val root = json.parseToJsonElement(showInterfacesJson).jsonObject
        val interfaces = root.entries.mapNotNull { (fallbackId, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (!type.equals("Wireguard", ignoreCase = true)) return@mapNotNull null
            InterfaceInfo(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: fallbackId,
                address = obj["address"]?.jsonPrimitive?.contentOrNull,
                publicKey = obj["wireguard"]?.jsonObject?.get("public-key")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val selected = interfaces.firstOrNull { it.id == current.interfaceId }
            ?: interfaces.firstOrNull { it.address == "10.8.0.1" }
            ?: error("WireGuard-интерфейс не найден")
        require(ServerSettingsValidator.isCanonicalKey(selected.publicKey)) { "У роутера нет корректного публичного ключа WireGuard" }

        val endpointCandidate = if (current.endpoint.isBlank()) {
            root.values.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                if (obj["defaultgw"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
                obj["address"]?.jsonPrimitive?.contentOrNull
            }.firstOrNull(ServerSettingsValidator::isPublicWanCandidate)
                ?.let { "$it:51820" }
        } else null

        return DiscoveryPreview(selected.id, selected.publicKey, current.endpoint, endpointCandidate)
    }

    private data class InterfaceInfo(val id: String, val address: String?, val publicKey: String)
}
