package ru.anisimov.keenwg.data.rci

import kotlinx.serialization.json.*
import ru.anisimov.keenwg.domain.ServerSettingsValidator

data class RciStatus(val status: String, val code: String?, val message: String?)

data class PeerStatusDto(
    val publicKey: String,
    val description: String,
    val online: Boolean,
    val enabled: Boolean,
    val lastHandshakeSec: Long?,
    val rxBytes: Long,
    val txBytes: Long,
)

data class ConfiguredPeer(
    val publicKey: String,
    val name: String,
    val allowIp: String?,
    val keepalive: Int,
    val enabled: Boolean,
    val restoreSuffixes: List<String>,
)

class UnsupportedPeerConfigException(message: String) : IllegalArgumentException(message)

/** Parsers for the RCI JSON shapes captured live from the router (2026-06-23). */
object RciResponse {
    private val json = Json { ignoreUnknownKeys = true }

    fun statuses(jsonStr: String): List<RciStatus> {
        val root = json.parseToJsonElement(jsonStr)
        val out = ArrayList<RciStatus>()
        collectStatuses(root, out)
        return out
    }

    private fun collectStatuses(element: JsonElement, out: MutableList<RciStatus>) {
        when (element) {
            is JsonArray -> element.forEach { collectStatuses(it, out) }
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "status" && value is JsonArray) {
                    value.forEach { statusElement ->
                        val status = statusElement as? JsonObject ?: return@forEach
                        status["status"]?.jsonPrimitive?.contentOrNull?.let { kind ->
                            out += RciStatus(
                                status = kind,
                                code = status["code"]?.jsonPrimitive?.contentOrNull,
                                message = status["message"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    }
                } else {
                    collectStatuses(value, out)
                }
            }
            else -> Unit
        }
    }

    fun firstError(jsonStr: String): RciStatus? =
        statuses(jsonStr).firstOrNull { it.status == "error" }

    fun peers(showInterfaceJson: String): List<PeerStatusDto> {
        val root = json.parseToJsonElement(showInterfaceJson).jsonObject
        val peerArr = root["wireguard"]?.jsonObject?.get("peer")?.jsonArray ?: return emptyList()
        return peerArr.map { it.jsonObject }.map { p ->
            PeerStatusDto(
                publicKey = p["public-key"]?.jsonPrimitive?.contentOrNull ?: "",
                description = p["description"]?.jsonPrimitive?.contentOrNull ?: "",
                online = p["online"]?.jsonPrimitive?.booleanOrNull ?: false,
                enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                lastHandshakeSec = p["last-handshake"]?.jsonPrimitive?.longOrNull,
                rxBytes = p["rxbytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                txBytes = p["txbytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    /** Tunnel IPs are NOT in show/interface — read them from running-config lines. */
    fun allowIpsByPubkey(runningConfigJson: String): Map<String, String> {
        val root = json.parseToJsonElement(runningConfigJson).jsonObject
        val lines = root["message"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        var cur: String? = null
        val peerRe = Regex("""^\s*wireguard peer (\S+)""")
        val ipRe = Regex("""^\s*allow-ips (\S+)\s""")
        for (line in lines) {
            peerRe.find(line)?.let { cur = it.groupValues[1] }
            ipRe.find(line)?.let { m -> cur?.let { map[it] = m.groupValues[1] } }
        }
        return map
    }

    fun configuredPeers(runningConfigJson: String, interfaceId: String): List<ConfiguredPeer> {
        val root = json.parseToJsonElement(runningConfigJson).jsonObject
        val linesElement = root["message"] ?: throw UnsupportedPeerConfigException("Running-config не содержит ожидаемый список команд")
        val lines = runCatching { linesElement.jsonArray.map { it.jsonPrimitive.content } }
            .getOrElse { throw UnsupportedPeerConfigException("Running-config имеет неподдерживаемую форму") }
        val peers = mutableListOf<ConfiguredPeer>()
        var currentInterface: String? = null
        var builder: ConfiguredPeerBuilder? = null

        fun finishPeer() {
            builder?.let { peers += it.build() }
            builder = null
        }

        lines.forEach { raw ->
            val line = raw.trim()
            Regex("^interface (\\S+)$").matchEntire(line)?.let { match ->
                finishPeer()
                currentInterface = match.groupValues[1]
                return@forEach
            }
            if (currentInterface != interfaceId) return@forEach

            Regex("^wireguard peer (\\S+)(?: !(\\S+))?$").matchEntire(line)?.let { match ->
                finishPeer()
                require(ServerSettingsValidator.isCanonicalKey(match.groupValues[1])) { "Некорректный публичный ключ доступа" }
                require(Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(match.groupValues[2])) { "Некорректное имя доступа" }
                builder = ConfiguredPeerBuilder(match.groupValues[1], match.groupValues[2])
                return@forEach
            }
            if (line.startsWith("wireguard peer ")) {
                throw UnsupportedPeerConfigException("Неподдерживаемый заголовок доступа WireGuard")
            }
            val peer = builder ?: return@forEach
            when {
                line == "!" -> finishPeer()
                Regex("^allow-ips (\\S+) 255\\.255\\.255\\.255$").matches(line) -> {
                    peer.allowIp = line.split(' ')[1]
                    peer.restoreSuffixes += line
                }
                Regex("^keepalive-interval (\\d+)$").matches(line) -> {
                    peer.keepalive = line.substringAfterLast(' ').toInt()
                    peer.restoreSuffixes += line
                }
                line == "connect" -> {
                    peer.enabled = true
                    peer.restoreSuffixes += line
                }
                line == "no connect" -> {
                    peer.enabled = false
                    peer.restoreSuffixes += line
                }
                line.isNotBlank() -> throw UnsupportedPeerConfigException("Конфигурация доступа содержит неподдерживаемую команду")
            }
        }
        finishPeer()
        return peers
    }

    private data class ConfiguredPeerBuilder(
        val publicKey: String,
        val name: String,
        var allowIp: String? = null,
        var keepalive: Int = 0,
        var enabled: Boolean = false,
        val restoreSuffixes: MutableList<String> = mutableListOf(),
    ) {
        fun build() = ConfiguredPeer(publicKey, name, allowIp, keepalive, enabled, restoreSuffixes.toList())
    }
}
