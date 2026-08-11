package ru.anisimov.keenwg.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RouterProfile(
    val schemaVersion: Int = 2,
    val id: String,
    val displayName: String,
    val host: String,
    val rciPort: Int,
    val interfaceId: String,
    val serverPublicKey: String,
    val endpoint: String,
    val subnetBase: String,
    val dns: String,
    val mtu: Int,
    val keepalive: Int,
    val companionUrl: String = "",
    val certificatePin: String = "",
    val collectorUrl: String = "",
) {
    companion object {
        fun fromServerSettings(id: String, displayName: String, settings: ServerSettings) = RouterProfile(
            id = id,
            displayName = displayName,
            host = settings.host,
            rciPort = settings.port,
            interfaceId = settings.interfaceId,
            serverPublicKey = settings.serverPublicKey,
            endpoint = settings.endpoint,
            subnetBase = settings.subnetBase,
            dns = settings.dns,
            mtu = settings.mtu,
            keepalive = settings.keepalive,
            collectorUrl = settings.collectorUrl,
        )
    }

    fun toServerSettings(secrets: RouterSecrets) = ServerSettings(
        host = host,
        port = rciPort,
        login = secrets.rciLogin,
        password = secrets.rciPassword,
        interfaceId = interfaceId,
        serverPublicKey = serverPublicKey,
        endpoint = endpoint,
        subnetBase = subnetBase,
        dns = dns,
        mtu = mtu,
        keepalive = keepalive,
        collectorUrl = collectorUrl,
        collectorToken = secrets.collectorToken,
    )
}

@Serializable
data class RouterSecrets(
    val rciLogin: String = "",
    val rciPassword: String = "",
    val companionToken: String = "",
    val companionDeviceId: String = "",
    val collectorToken: String = "",
) {
    companion object {
        fun fromServerSettings(settings: ServerSettings) = RouterSecrets(
            rciLogin = settings.login,
            rciPassword = settings.password,
            collectorToken = settings.collectorToken,
        )
    }
}
