package ru.anisimov.keenwg.data.rci

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.anisimov.keenwg.domain.ServerSettingsValidator

/**
 * Builds the JSON bodies POSTed to /rci/. Uses the {"parse":"<cli>"} escape hatch so the
 * exact canonical Keenetic CLI strings (verified live 2026-06-23) are reused verbatim.
 */
object RciCommands {
    private fun parseArr(vararg cmds: String): String =
        buildJsonArray {
            cmds.forEach { c -> add(buildJsonObject { put("parse", c) }) }
        }.toString()

    fun addPeer(wg: String, pub: String, name: String, ip: String, keepalive: Int): String {
        validate(wg, pub, name, ip, keepalive)
        return parseArr(
            "interface $wg wireguard peer $pub !$name",
            "interface $wg wireguard peer $pub allow-ips $ip 255.255.255.255",
            "interface $wg wireguard peer $pub keepalive-interval $keepalive",
            "interface $wg wireguard peer $pub connect",
        )
    }

    fun createPeer(wg: String, pub: String, name: String, keepalive: Int): String {
        validate(wg, pub, name, null, keepalive)
        return parseArr(
            "interface $wg wireguard peer $pub !$name",
            "interface $wg wireguard peer $pub keepalive-interval $keepalive",
        )
    }

    fun cutoverPeer(wg: String, old: ConfiguredPeer, newPublicKey: String): String {
        val ip = requireNotNull(old.allowIp) { "Для перевыпуска требуется IP доступа" }
        validateConfiguredPeer(wg, old)
        validate(wg, newPublicKey, old.name, ip, old.keepalive)
        val commands = mutableListOf(
            "interface $wg no wireguard peer ${old.publicKey}",
            "interface $wg wireguard peer $newPublicKey !${old.name}",
            "interface $wg wireguard peer $newPublicKey allow-ips $ip 255.255.255.255",
            "interface $wg wireguard peer $newPublicKey keepalive-interval ${old.keepalive}",
        )
        if (old.enabled) commands += "interface $wg wireguard peer $newPublicKey connect"
        return parseArr(*commands.toTypedArray())
    }

    fun removePeer(wg: String, publicKey: String): String {
        validate(wg, publicKey, "peer", null, 0)
        return parseArr("interface $wg no wireguard peer $publicKey")
    }

    fun restorePeer(wg: String, peer: ConfiguredPeer): String {
        validateConfiguredPeer(wg, peer)
        val commands = mutableListOf("interface $wg wireguard peer ${peer.publicKey} !${peer.name}")
        commands += peer.restoreSuffixes.map { suffix -> "interface $wg wireguard peer ${peer.publicKey} $suffix" }
        return parseArr(*commands.toTypedArray())
    }

    fun rename(wg: String, pub: String, name: String): String =
        parseArr(validate(wg, pub, name, null, 0).let { "interface $wg wireguard peer $pub !$name" })

    fun setEnabled(wg: String, pub: String, on: Boolean): String =
        parseArr(validate(wg, pub, "peer", null, 0).let { "interface $wg wireguard peer $pub " + if (on) "connect" else "no connect" })

    fun remove(wg: String, pub: String): String =
        removePeer(wg, pub)

    fun regenerate(wg: String, oldPub: String, newPub: String, name: String, ip: String, keepalive: Int): String =
        validate(wg, oldPub, name, ip, keepalive).let {
            validate(wg, newPub, name, ip, keepalive)
            parseArr(
            "interface $wg no wireguard peer $oldPub",
            "interface $wg wireguard peer $newPub !$name",
            "interface $wg wireguard peer $newPub allow-ips $ip 255.255.255.255",
            "interface $wg wireguard peer $newPub keepalive-interval $keepalive",
            "interface $wg wireguard peer $newPub connect",
            )
        }

    const val save: String = """[{"system":{"configuration":{"save":true}}}]"""

    fun setDhcpHost(mac: String, ip: String): String {
        val normalizedMac = validateMac(mac)
        validateIpv4(ip)
        return parseArr("ip dhcp host $normalizedMac $ip")
    }

    fun removeDhcpHost(mac: String): String = parseArr("no ip dhcp host ${validateMac(mac)}")

    private fun validateMac(value: String): String {
        val normalized = value.lowercase()
        require(Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$").matches(normalized)) { "Некорректный MAC-адрес" }
        return normalized
    }

    private fun validateIpv4(value: String) {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 && part == part.toInt().toString() }) { "Некорректный IPv4-адрес" }
    }

    private fun validate(wg: String, pub: String, name: String, ip: String?, keepalive: Int) {
        require(Regex("^[A-Za-z0-9/_-]{1,64}$").matches(wg)) { "Некорректный интерфейс WireGuard" }
        require(ServerSettingsValidator.isCanonicalKey(pub)) { "Некорректный публичный ключ" }
        require(Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(name)) { "Некорректное имя доступа" }
        require(keepalive in 0..3600) { "Некорректный интервал keepalive" }
        if (ip != null) require(ip.split('.').size == 4 && ip.split('.').all { it.toIntOrNull() in 0..255 }) { "Некорректный IP доступа" }
    }

    private fun validateConfiguredPeer(wg: String, peer: ConfiguredPeer) {
        validate(wg, peer.publicKey, peer.name, peer.allowIp, peer.keepalive)
        peer.restoreSuffixes.forEach { suffix ->
            require(
                suffix == "connect" || suffix == "no connect" ||
                    Regex("^allow-ips (?:\\d{1,3}\\.){3}\\d{1,3} 255\\.255\\.255\\.255$").matches(suffix) ||
                    Regex("^keepalive-interval (?:0|[1-9]\\d{0,3})$").matches(suffix),
            ) { "Неподдерживаемая команда восстановления доступа" }
        }
    }
}
