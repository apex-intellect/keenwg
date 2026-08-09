package ru.anisimov.keenwg.domain.model

import kotlinx.serialization.Serializable
import java.net.InetAddress

@Serializable
data class AccessPolicy(
    val allowedNetworks: List<String> = listOf("0.0.0.0/0"),
    val dnsServers: List<String> = emptyList(),
    val expiresAtEpochSeconds: Long? = null,
    val historyEnabled: Boolean = true,
) {
    fun expiryAt(nowEpochSeconds: Long): AccessExpiry = when {
        expiresAtEpochSeconds == null -> AccessExpiry.NO_EXPIRY
        nowEpochSeconds >= expiresAtEpochSeconds -> AccessExpiry.EXPIRED_REQUIRES_ACTION
        else -> AccessExpiry.ACTIVE
    }
}

enum class AccessExpiry { NO_EXPIRY, ACTIVE, EXPIRED_REQUIRES_ACTION }

object AccessPolicyValidator {
    fun requireValid(policy: AccessPolicy, nowEpochSeconds: Long) {
        require(policy.allowedNetworks.isNotEmpty() && policy.allowedNetworks.size <= 16)
        require(policy.allowedNetworks.distinct().size == policy.allowedNetworks.size)
        policy.allowedNetworks.forEach(::requireCidr)
        require(policy.dnsServers.size <= 4)
        require(policy.dnsServers.distinct().size == policy.dnsServers.size)
        policy.dnsServers.forEach(::requireIpLiteral)
        policy.expiresAtEpochSeconds?.let { require(it > nowEpochSeconds) { "Expiry must be in the future" } }
    }

    private fun requireCidr(value: String) {
        require(value == value.trim() && value.length <= 64)
        val pieces = value.split('/')
        require(pieces.size == 2)
        val bytes = requireIpLiteral(pieces[0])
        val prefix = pieces[1].toIntOrNull() ?: error("Invalid CIDR prefix")
        require(prefix in 0..(bytes * 8))
    }

    private fun requireIpLiteral(value: String): Int {
        require(value == value.trim() && value.isNotEmpty() && value.length <= 45)
        val ipv4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}").matches(value)
        val ipv6 = ':' in value && Regex("[0-9A-Fa-f:.]+").matches(value)
        require(ipv4 || ipv6) { "Only literal IP addresses are allowed" }
        val address = InetAddress.getByName(value)
        if (ipv4) require(address.address.size == 4 && value.split('.').all { it.toInt() in 0..255 })
        if (ipv6) require(address.address.size == 16)
        return address.address.size
    }
}
