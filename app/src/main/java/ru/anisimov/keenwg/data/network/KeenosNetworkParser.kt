package ru.anisimov.keenwg.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object KeenosNetworkParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val macPattern = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    private val staticPattern = Regex("^ip dhcp host ([0-9a-fA-F:]{17}) ((?:\\d{1,3}\\.){3}\\d{1,3})$")

    fun devices(hotspotJson: String, leasesJson: String, runningConfigJson: String): List<NetworkDevice> {
        val hotspots = array(hotspotJson, "host").mapNotNull(::hotspot)
        val leases = array(leasesJson, "lease").mapNotNull(::lease).associateBy { it.mac }
        val reservations = staticReservations(runningConfigJson)
        val macs = linkedSetOf<String>().apply {
            hotspots.forEach { add(it.mac) }
            leases.keys.forEach(::add)
            reservations.keys.forEach(::add)
        }
        val hotspotByMac = hotspots.associateBy { it.mac }
        return macs.map { mac ->
            val host = hotspotByMac[mac]
            val lease = leases[mac]
            val reserved = reservations[mac]
            NetworkDevice(
                mac = mac,
                name = host?.name?.takeIf(String::isNotBlank) ?: lease?.name?.takeIf(String::isNotBlank)
                    ?: host?.hostname?.takeIf(String::isNotBlank) ?: lease?.hostname?.takeIf(String::isNotBlank) ?: mac,
                hostname = host?.hostname?.takeIf(String::isNotBlank) ?: lease?.hostname?.takeIf(String::isNotBlank),
                ip = host?.ip?.takeUnless { it == "0.0.0.0" } ?: lease?.ip ?: reserved,
                reservedIp = reserved,
                online = host?.online == true,
                staticReservation = reserved != null || lease?.infinite == true,
                interfaceName = host?.interfaceName,
                rssi = host?.rssi,
            )
        }.sortedWith(compareByDescending<NetworkDevice> { it.online }.thenBy { it.name.lowercase() })
    }

    private fun array(source: String, key: String): List<JsonObject> = runCatching {
        val value = json.parseToJsonElement(source).jsonObject[key] ?: return emptyList()
        when (value) {
            is JsonArray -> value.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(value)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun hotspot(value: JsonObject): Hotspot? {
        val mac = value.text("mac")?.lowercase()?.takeIf(macPattern::matches) ?: return null
        return Hotspot(
            mac, value.text("name").orEmpty(), value.text("hostname").orEmpty(), value.text("ip"),
            value["active"]?.jsonPrimitive?.booleanOrNull ?: value.text("active").equals("yes", true),
            (value["interface"] as? JsonObject)?.text("name"), value["rssi"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun lease(value: JsonObject): Lease? {
        val mac = value.text("mac")?.lowercase()?.takeIf(macPattern::matches) ?: return null
        return Lease(mac, value.text("name").orEmpty(), value.text("hostname").orEmpty(), value.text("ip"), value.text("expires") == "infinity")
    }

    private fun staticReservations(source: String): Map<String, String> = runCatching {
        val root = json.parseToJsonElement(source).jsonObject
        val messages = root["message"] as? JsonArray ?: return emptyMap()
        buildMap {
            messages.forEach { element ->
                staticPattern.matchEntire(element.jsonPrimitive.content.trim())?.let { match ->
                    val mac = match.groupValues[1].lowercase()
                    if (macPattern.matches(mac)) put(mac, match.groupValues[2])
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private data class Hotspot(val mac: String, val name: String, val hostname: String, val ip: String?, val online: Boolean, val interfaceName: String?, val rssi: Int?)
    private data class Lease(val mac: String, val name: String, val hostname: String, val ip: String?, val infinite: Boolean)
}
