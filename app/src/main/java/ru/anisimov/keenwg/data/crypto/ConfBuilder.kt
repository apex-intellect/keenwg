package ru.anisimov.keenwg.data.crypto

/** Builds the client-side WireGuard .conf text (spec §7.3). */
object ConfBuilder {
    fun build(
        privateKey: String,
        address: String,
        dns: String,
        mtu: Int,
        serverPublicKey: String,
        endpoint: String,
        keepalive: Int,
    ): String = build(privateKey, address, listOf(dns), mtu, serverPublicKey, endpoint, keepalive, listOf("0.0.0.0/0"))

    fun build(
        privateKey: String,
        address: String,
        dnsServers: List<String>,
        mtu: Int,
        serverPublicKey: String,
        endpoint: String,
        keepalive: Int,
        allowedNetworks: List<String>,
    ): String = """
        [Interface]
        PrivateKey = $privateKey
        Address = $address
        DNS = ${dnsServers.joinToString(", ")}
        MTU = $mtu

        [Peer]
        PublicKey = $serverPublicKey
        AllowedIPs = ${allowedNetworks.joinToString(", ")}
        Endpoint = $endpoint
        PersistentKeepalive = $keepalive
    """.trimIndent()
}
