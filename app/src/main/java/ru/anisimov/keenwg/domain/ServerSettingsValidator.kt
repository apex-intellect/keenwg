package ru.anisimov.keenwg.domain

import com.wireguard.crypto.Key
import ru.anisimov.keenwg.domain.model.ServerSettings
import java.net.Inet6Address
import java.net.InetAddress

data class ValidationIssue(val field: String, val message: String)

class SettingsValidationException(val issues: List<ValidationIssue>) :
    IllegalArgumentException(issues.firstOrNull()?.message ?: "Настройки не прошли проверку")

object ServerSettingsValidator {
    private val hostLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val interfaceId = Regex("^[A-Za-z0-9/_-]{1,64}$")

    fun validateForMutation(settings: ServerSettings): List<ValidationIssue> = buildList {
        if (!isIpv4(settings.host) && !isHostName(settings.host)) add(issue("host", "Некорректный адрес роутера"))
        if (settings.port !in 1..65535) add(issue("port", "Некорректный порт роутера"))
        if (settings.login.isBlank()) add(issue("login", "Укажите логин роутера"))
        if (settings.password.isBlank()) add(issue("password", "Укажите пароль роутера"))
        if (!interfaceId.matches(settings.interfaceId)) add(issue("interfaceId", "Некорректный WG-интерфейс"))
        if (!isCanonicalKey(settings.serverPublicKey)) add(issue("serverPublicKey", "Некорректный публичный ключ сервера"))
        if (!isEndpoint(settings.endpoint)) add(issue("endpoint", "Укажите endpoint с UDP-портом"))
        if (!isSubnetBase(settings.subnetBase)) add(issue("subnetBase", "Некорректная подсеть клиентов"))
        if (!isIpv4(settings.dns)) add(issue("dns", "Некорректный DNS"))
        if (settings.mtu !in 576..9000) add(issue("mtu", "MTU должен быть от 576 до 9000"))
        if (settings.keepalive != 0 && settings.keepalive !in 3..3600) add(issue("keepalive", "Некорректный keepalive"))
    }

    fun validateForSave(settings: ServerSettings): List<ValidationIssue> = validateForMutation(settings)

    fun requireForMutation(settings: ServerSettings) {
        val issues = validateForMutation(settings)
        if (issues.isNotEmpty()) throw SettingsValidationException(issues)
    }

    fun requireForSave(settings: ServerSettings) {
        val issues = validateForSave(settings)
        if (issues.isNotEmpty()) throw SettingsValidationException(issues)
    }

    fun isCanonicalKey(value: String): Boolean =
        value.length == 44 && runCatching { Key.fromBase64(value).toBase64() == value }.getOrDefault(false)

    fun isEndpoint(value: String): Boolean {
        if (value.isBlank()) return false
        val match = Regex("^(?:\\[([^]]+)\\]|([^:]+)):(\\d{1,5})$").matchEntire(value) ?: return false
        val host = match.groupValues[1].ifBlank { match.groupValues[2] }
        val port = match.groupValues[3].toIntOrNull() ?: return false
        val bracketed = match.groupValues[1].isNotBlank()
        val validHost = if (bracketed) isIpv6Literal(host) else isIpv4(host) || isHostName(host)
        return validHost && port in 1..65535
    }

    private fun isSubnetBase(value: String): Boolean =
        value.endsWith('.') && isIpv4(value + "0")

    internal fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && !(part.length > 1 && part.startsWith('0')) && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    internal fun isPrivateIpv4(value: String): Boolean {
        if (!isIpv4(value)) return false
        val p = value.split('.').map(String::toInt)
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
    }

    internal fun isPublicWanCandidate(value: String): Boolean {
        if (!isIpv4(value) || isPrivateIpv4(value)) return false
        val p = value.split('.').map(String::toInt)
        return p[0] !in listOf(0, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255) &&
            p[0] != 127 && !(p[0] == 169 && p[1] == 254) &&
            !(p[0] == 192 && p[1] == 0 && p[2] == 2) &&
            !(p[0] == 198 && p[1] == 51 && p[2] == 100) &&
            !(p[0] == 203 && p[1] == 0 && p[2] == 113) &&
            !(p[0] == 198 && p[1] in 18..19)
    }

    private fun isIpv6Literal(value: String): Boolean {
        if (!value.contains(':') || '%' in value || !Regex("^[0-9A-Fa-f:]+$").matches(value)) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }

    private fun isHostName(value: String): Boolean =
        value.length in 1..253 && value.split('.').all { it.length in 1..63 && hostLabel.matches(it) }

    private fun issue(field: String, message: String) = ValidationIssue(field, message)
}
