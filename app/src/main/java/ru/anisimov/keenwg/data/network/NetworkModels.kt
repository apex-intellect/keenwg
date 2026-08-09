package ru.anisimov.keenwg.data.network

data class NetworkDevice(
    val mac: String,
    val name: String,
    val hostname: String?,
    val ip: String?,
    val reservedIp: String?,
    val online: Boolean,
    val staticReservation: Boolean,
    val interfaceName: String?,
    val rssi: Int?,
)
