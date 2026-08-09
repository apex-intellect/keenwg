package ru.anisimov.keenwg.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.anisimov.keenwg.domain.model.RouterProfile
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.domain.model.RouterSecrets
import ru.anisimov.keenwg.domain.model.ServerSettings

data class ActiveRouterProfile(
    val profile: RouterProfile,
    val secrets: RouterSecrets,
) {
    val settings: ServerSettings get() = profile.toServerSettings(secrets)
}

class RouterProfileStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    private sealed interface Snapshot {
        data class Ready(val index: RouterProfileIndex, val secrets: Map<String, RouterSecrets>) : Snapshot
        data class Locked(val reason: String) : Snapshot
    }

    val state: Flow<RouterProfilesState> = dataStore.data.map(::decodeSnapshot).map { snapshot ->
        when (snapshot) {
            is Snapshot.Ready -> RouterProfilesState.Ready(snapshot.index.profiles, snapshot.index.selectedProfileId)
            is Snapshot.Locked -> RouterProfilesState.Locked(snapshot.reason)
        }
    }.distinctUntilChanged()

    val activeSettings: Flow<ServerSettings?> = dataStore.data.map(::decodeSnapshot).map { snapshot ->
        if (snapshot !is Snapshot.Ready) return@map null
        val profile = snapshot.index.profiles.first { it.id == snapshot.index.selectedProfileId }
        val secrets = snapshot.secrets[profile.id] ?: return@map null
        profile.toServerSettings(secrets)
    }.distinctUntilChanged()

    val activeProfile: Flow<ActiveRouterProfile?> = dataStore.data.map(::decodeSnapshot).map { snapshot ->
        if (snapshot !is Snapshot.Ready) return@map null
        val profile = snapshot.index.profiles.first { it.id == snapshot.index.selectedProfileId }
        val secrets = snapshot.secrets[profile.id] ?: return@map null
        ActiveRouterProfile(profile, secrets)
    }.distinctUntilChanged()

    val migrationReviewPending: Flow<Boolean> = dataStore.data
        .map { it[SettingsKeys.migrationReviewPending] ?: false }
        .distinctUntilChanged()

    suspend fun migrateLegacy() {
        dataStore.edit { preferences ->
            if (preferences[SettingsKeys.profiles] != null) return@edit
            val hasLegacyConfiguration = preferences[SettingsKeys.host] != null ||
                preferences[SettingsKeys.pass] != null ||
                preferences[SettingsKeys.iface] != null ||
                preferences[SettingsKeys.xkeenControllerUrl] != null
            val (index, secrets) = legacySnapshot(preferences)
            writeSnapshot(preferences, index, secrets)
            if (hasLegacyConfiguration) preferences[SettingsKeys.migrationReviewPending] = true
        }
    }

    suspend fun dismissMigrationReview() {
        dataStore.edit { it[SettingsKeys.migrationReviewPending] = false }
    }

    suspend fun upsert(profile: RouterProfile, secrets: RouterSecrets, select: Boolean) {
        require(profile.schemaVersion == 1 && profile.id.isNotBlank() && profile.displayName.isNotBlank())
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            val profiles = current.index.profiles.toMutableList()
            val existing = profiles.indexOfFirst { it.id == profile.id }
            if (existing >= 0) profiles[existing] = profile else profiles += profile
            val updatedSecrets = current.secrets + (profile.id to secrets)
            val selected = if (select) profile.id else current.index.selectedProfileId
            writeSnapshot(preferences, RouterProfileIndex(selectedProfileId = selected, profiles = profiles), updatedSecrets)
        }
    }

    suspend fun select(id: String) {
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            require(current.index.profiles.any { it.id == id }) { "Router profile not found" }
            writeSnapshot(preferences, current.index.copy(selectedProfileId = id), current.secrets)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            require(current.index.profiles.any { it.id == id }) { "Router profile not found" }
            check(current.index.profiles.size > 1) { "The last router profile cannot be deleted" }
            val profiles = current.index.profiles.filterNot { it.id == id }
            val selected = if (current.index.selectedProfileId == id) profiles.first().id else current.index.selectedProfileId
            writeSnapshot(
                preferences,
                RouterProfileIndex(selectedProfileId = selected, profiles = profiles),
                current.secrets - id,
            )
        }
    }

    suspend fun saveActive(settings: ServerSettings) {
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            val selected = current.index.profiles.first { it.id == current.index.selectedProfileId }
            val profile = RouterProfile.fromServerSettings(selected.id, selected.displayName, settings).copy(
                companionUrl = selected.companionUrl,
                certificatePin = selected.certificatePin,
            )
            val profiles = current.index.profiles.map { if (it.id == profile.id) profile else it }
            val previousSecrets = current.secrets[profile.id] ?: RouterSecrets()
            val secrets = RouterSecrets.fromServerSettings(settings).copy(companionToken = previousSecrets.companionToken)
            writeSnapshot(preferences, current.index.copy(profiles = profiles), current.secrets + (profile.id to secrets))
            writeLegacyMirror(preferences, settings)
        }
    }

    suspend fun saveCompanion(profileId: String, baseUrl: String, certificatePin: String, deviceToken: String, deviceId: String) {
        require(baseUrl.startsWith("https://") && certificatePin.startsWith("sha256/") && deviceToken.isNotBlank() && deviceId.isNotBlank())
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            require(current.index.selectedProfileId == profileId) { "Selected router profile changed" }
            val profiles = current.index.profiles.map { profile ->
                if (profile.id == profileId) profile.copy(companionUrl = baseUrl, certificatePin = certificatePin) else profile
            }
            val secrets = current.secrets.toMutableMap()
            val existing = requireNotNull(secrets[profileId]) { "Router profile secrets are missing" }
            secrets[profileId] = existing.copy(companionToken = deviceToken, companionDeviceId = deviceId)
            writeSnapshot(preferences, current.index.copy(profiles = profiles), secrets)
        }
    }

    suspend fun clearCompanion(profileId: String) {
        dataStore.edit { preferences ->
            val current = mutableSnapshot(preferences)
            val profiles = current.index.profiles.map { profile ->
                if (profile.id == profileId) profile.copy(companionUrl = "", certificatePin = "") else profile
            }
            val secrets = current.secrets.toMutableMap()
            val existing = requireNotNull(secrets[profileId]) { "Router profile secrets are missing" }
            secrets[profileId] = existing.copy(companionToken = "", companionDeviceId = "")
            writeSnapshot(preferences, current.index.copy(profiles = profiles), secrets)
        }
    }

    private fun decodeSnapshot(preferences: Preferences): Snapshot = runCatching {
        val rawIndex = preferences[SettingsKeys.profiles]
        val pair = if (rawIndex == null) {
            legacySnapshot(preferences)
        } else {
            val encryptedSecrets = requireNotNull(preferences[SettingsKeys.secrets]) { "Router secrets are missing" }
            RouterProfileCodec.decodeIndex(rawIndex) to RouterProfileCodec.decodeSecrets(cipher.decrypt(encryptedSecrets))
        }
        require(pair.first.profiles.all { pair.second.containsKey(it.id) }) { "Router profile secrets are incomplete" }
        Snapshot.Ready(pair.first, pair.second)
    }.getOrElse { Snapshot.Locked("profile_store_locked") }

    private fun mutableSnapshot(preferences: Preferences): Snapshot.Ready {
        return when (val snapshot = decodeSnapshot(preferences)) {
            is Snapshot.Ready -> snapshot
            is Snapshot.Locked -> error("Router profile store is locked")
        }
    }

    private fun legacySnapshot(preferences: Preferences): Pair<RouterProfileIndex, Map<String, RouterSecrets>> {
        val settings = readLegacy(preferences)
        val id = legacyProfileId(settings.host, settings.port)
        val profile = RouterProfile.fromServerSettings(id, "Keenetic ${settings.host}", settings)
        return RouterProfileIndex(selectedProfileId = id, profiles = listOf(profile)) to
            mapOf(id to RouterSecrets.fromServerSettings(settings))
    }

    private fun readLegacy(p: Preferences): ServerSettings {
        val defaults = ServerSettings()
        return ServerSettings(
            host = p[SettingsKeys.host] ?: defaults.host,
            port = p[SettingsKeys.port] ?: defaults.port,
            login = p[SettingsKeys.login] ?: defaults.login,
            password = p[SettingsKeys.pass]?.let(cipher::decrypt).orEmpty(),
            interfaceId = p[SettingsKeys.iface] ?: defaults.interfaceId,
            serverPublicKey = p[SettingsKeys.serverKey] ?: defaults.serverPublicKey,
            endpoint = p[SettingsKeys.endpoint] ?: defaults.endpoint,
            subnetBase = p[SettingsKeys.subnet] ?: defaults.subnetBase,
            dns = p[SettingsKeys.dns] ?: defaults.dns,
            mtu = p[SettingsKeys.mtu] ?: defaults.mtu,
            keepalive = p[SettingsKeys.keepalive] ?: defaults.keepalive,
            collectorUrl = p[SettingsKeys.collectorUrl] ?: defaults.collectorUrl,
            collectorToken = p[SettingsKeys.collectorToken]?.let(cipher::decrypt).orEmpty(),
            xkeenControllerUrl = p[SettingsKeys.xkeenControllerUrl] ?: defaults.xkeenControllerUrl,
            xkeenControllerToken = p[SettingsKeys.xkeenControllerToken]?.let(cipher::decrypt).orEmpty(),
        )
    }

    private fun writeSnapshot(preferences: androidx.datastore.preferences.core.MutablePreferences, index: RouterProfileIndex, secrets: Map<String, RouterSecrets>) {
        preferences[SettingsKeys.profiles] = RouterProfileCodec.encodeIndex(index)
        preferences[SettingsKeys.secrets] = cipher.encrypt(RouterProfileCodec.encodeSecrets(secrets))
    }

    private fun writeLegacyMirror(p: androidx.datastore.preferences.core.MutablePreferences, s: ServerSettings) {
        p[SettingsKeys.host] = s.host
        p[SettingsKeys.port] = s.port
        p[SettingsKeys.login] = s.login
        p[SettingsKeys.pass] = cipher.encrypt(s.password)
        p[SettingsKeys.iface] = s.interfaceId
        p[SettingsKeys.serverKey] = s.serverPublicKey
        p[SettingsKeys.endpoint] = s.endpoint
        p[SettingsKeys.subnet] = s.subnetBase
        p[SettingsKeys.dns] = s.dns
        p[SettingsKeys.mtu] = s.mtu
        p[SettingsKeys.keepalive] = s.keepalive
        p[SettingsKeys.collectorUrl] = s.collectorUrl
        p[SettingsKeys.collectorToken] = cipher.encrypt(s.collectorToken)
        p[SettingsKeys.xkeenControllerUrl] = s.xkeenControllerUrl
        p[SettingsKeys.xkeenControllerToken] = cipher.encrypt(s.xkeenControllerToken)
    }

    companion object {
        fun legacyProfileId(host: String, port: Int): String {
            val input = "keenwg-legacy\n${host.lowercase()}\n$port".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(input).take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
