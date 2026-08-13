package ru.anisimov.keenwg.domain.model

/**
 * All values verified against the live router (2026-06-23): see the design spec §12.
 * Defaults are conservative community-safe values for a typical local Keenetic setup.
 */
data class ServerSettings(
    val host: String = "192.168.1.1",
    val port: Int = 80,
    val login: String = "admin",
    val password: String = "",
    val interfaceId: String = "Wireguard0",
    val serverPublicKey: String = "",
    val endpoint: String = "",
    val subnetBase: String = "10.8.0.",
    val dns: String = "192.168.1.1",
    val mtu: Int = 1380,
    val keepalive: Int = 25,
) {
    val baseUrl: String get() = "http://$host:$port"
}
