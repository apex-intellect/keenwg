package ru.anisimov.keenwg.domain

/** Picks the lowest free host address in a /24, skipping reserved octets (network, router, broadcast). */
object IpAllocator {
    fun nextFreeIp(
        subnetBase: String,
        taken: Set<String>,
        reserved: Set<Int> = setOf(0, 1, 255),
    ): String? {
        for (host in 2..254) {
            if (host in reserved) continue
            val ip = "$subnetBase$host"
            if (ip !in taken) return ip
        }
        return null
    }
}
