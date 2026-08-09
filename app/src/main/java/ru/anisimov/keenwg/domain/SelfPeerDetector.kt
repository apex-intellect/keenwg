package ru.anisimov.keenwg.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object SelfPeerDetector {
    fun isUnsafe(
        peerIp: String?,
        routerAddresses: Set<String>?,
        subnetBase: String,
        localAddresses: Set<String>,
    ): Boolean {
        val peer = canonicalIpv4(peerIp ?: return false) ?: return false
        val subnet = subnetPrefix(subnetBase) ?: return false
        val locals = localAddresses.mapNotNull(::canonicalIpv4).toSet()
        if (peer !in locals || peer.take(3) != subnet) return false

        // A local WireGuard address is potentially the management path itself. If a hostname
        // cannot be resolved at the tap boundary, blocking is safer than cutting off the router.
        val routers = routerAddresses?.mapNotNull(::canonicalIpv4).orEmpty()
        return routers.isEmpty() || routers.any { it.take(3) == subnet }
    }
}

fun interface LocalAddressProvider {
    suspend fun ipv4Addresses(): Set<String>
}

fun interface RouterAddressProvider {
    /** Null means that the hostname could not be resolved and must be handled fail-closed. */
    suspend fun ipv4Addresses(host: String): Set<String>?
}

class NetworkInterfaceLocalAddressProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalAddressProvider {
    override suspend fun ipv4Addresses(): Set<String> = withContext(dispatcher) {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@withContext emptySet()
        Collections.list(interfaces)
            .asSequence()
            .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { address -> address.hostAddress }
            .toSet()
    }
}

class NetworkRouterAddressProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RouterAddressProvider {
    override suspend fun ipv4Addresses(host: String): Set<String>? = withContext(dispatcher) {
        canonicalIpv4(host)?.let { return@withContext setOf(it.joinToString(".")) }
        runCatching {
            InetAddress.getAllByName(host)
                .asSequence()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress?.let(::canonicalIpv4)?.joinToString(".") }
                .toSet()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

private fun subnetPrefix(value: String): List<Int>? {
    if (!value.endsWith('.')) return null
    val prefix = canonicalIpv4(value + "0") ?: return null
    return prefix.take(3)
}

private fun canonicalIpv4(value: String): List<Int>? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    return parts.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        if (octet.toString() != part) return null
        octet
    }
}
