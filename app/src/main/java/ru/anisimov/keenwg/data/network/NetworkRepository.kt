package ru.anisimov.keenwg.data.network

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.anisimov.keenwg.data.rci.RciClient
import ru.anisimov.keenwg.data.rci.RciCommands
import ru.anisimov.keenwg.data.rci.RciException
import ru.anisimov.keenwg.data.rci.RciResponse
import ru.anisimov.keenwg.domain.model.ServerSettings

interface NetworkDataSource {
    suspend fun hotspot(settings: ServerSettings): String
    suspend fun leases(settings: ServerSettings): String
    suspend fun runningConfig(settings: ServerSettings): String
    suspend fun setReservation(settings: ServerSettings, mac: String, ip: String?)
    suspend fun save(settings: ServerSettings)
}

interface NetworkGateway {
    suspend fun load(settings: ServerSettings): List<NetworkDevice>
    suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String)
    suspend fun removeStaticReservation(settings: ServerSettings, mac: String)
}

class RciNetworkDataSource(private val client: RciClient = RciClient()) : NetworkDataSource {
    override suspend fun hotspot(settings: ServerSettings) = client.get(settings, "show/ip/hotspot")
    override suspend fun leases(settings: ServerSettings) = client.get(settings, "show/ip/dhcp/bindings")
    override suspend fun runningConfig(settings: ServerSettings) = client.get(settings, "show/running-config")
    override suspend fun setReservation(settings: ServerSettings, mac: String, ip: String?) {
        checkResponse(client.post(settings, if (ip == null) RciCommands.removeDhcpHost(mac) else RciCommands.setDhcpHost(mac, ip)))
    }
    override suspend fun save(settings: ServerSettings) { checkResponse(client.post(settings, RciCommands.save)) }

    private fun checkResponse(response: String) {
        RciResponse.firstError(response)?.let { throw RciException(it.message ?: "KeenOS отклонил изменение") }
    }
}

class NetworkRepository(private val source: NetworkDataSource) : NetworkGateway {
    constructor() : this(RciNetworkDataSource())
    private val mutationMutex = Mutex()

    override suspend fun load(settings: ServerSettings): List<NetworkDevice> = KeenosNetworkParser.devices(
        source.hotspot(settings), source.leases(settings), source.runningConfig(settings),
    )

    override suspend fun setStaticReservation(settings: ServerSettings, mac: String, ip: String) = mutationMutex.withLock {
        val before = load(settings)
        validateAddress(settings, before, mac, ip)
        val old = before.firstOrNull { it.mac == mac.lowercase() }?.reservedIp
        mutateAndVerify(settings, mac.lowercase(), old, ip)
    }

    override suspend fun removeStaticReservation(settings: ServerSettings, mac: String) = mutationMutex.withLock {
        val canonical = mac.lowercase()
        val old = load(settings).firstOrNull { it.mac == canonical }?.reservedIp ?: return@withLock
        mutateAndVerify(settings, canonical, old, null)
    }

    private suspend fun mutateAndVerify(settings: ServerSettings, mac: String, old: String?, target: String?) {
        try {
            source.setReservation(settings, mac, target)
            verify(settings, mac, target)
            source.save(settings)
            verify(settings, mac, target)
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    source.setReservation(settings, mac, old)
                    source.save(settings)
                    verify(settings, mac, old)
                }
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
                throw IllegalStateException("Состояние статического адреса требует проверки", failure)
            }
            throw IllegalStateException("Изменение не применено; прежний адрес восстановлен", failure)
        }
    }

    private suspend fun verify(settings: ServerSettings, mac: String, expected: String?) {
        val actual = KeenosNetworkParser.devices("{}", "{}", source.runningConfig(settings))
            .firstOrNull { it.mac == mac }?.reservedIp
        check(actual == expected) { "Статический адрес не подтверждён после записи" }
    }

    private fun validateAddress(settings: ServerSettings, devices: List<NetworkDevice>, mac: String, ip: String) {
        require(Regex("^[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}$").matches(mac)) { "Некорректный MAC-адрес" }
        val parts = ip.split('.').mapNotNull(String::toIntOrNull)
        require(parts.size == 4 && parts.all { it in 0..255 }) { "Некорректный IPv4-адрес" }
        val gateway = settings.dns.split('.').mapNotNull(String::toIntOrNull)
        require(gateway.size == 4 && parts.take(3) == gateway.take(3) && parts.last() !in setOf(0, gateway.last(), 255)) {
            "Адрес должен быть свободным адресом домашней сети"
        }
        require(devices.none { it.mac != mac.lowercase() && (it.ip == ip || it.reservedIp == ip) }) { "Этот IP уже занят другим устройством" }
    }
}
