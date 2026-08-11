package ru.anisimov.keenwg.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun initialize() {
        dataStore.edit { preferences ->
            val storedProfiles = preferences[SettingsKeys.profiles]
            val (index, secrets) = if (storedProfiles != null) {
                val storedSecrets = requireNotNull(preferences[SettingsKeys.secrets]) { "Router secrets are missing" }
                RouterProfileCodec.decodeIndex(storedProfiles) to RouterProfileCodec.decodeSecrets(cipher.decrypt(storedSecrets))
            } else {
                defaultSnapshot()
            }
            writeSnapshot(preferences, index, secrets)
            removeObsoletePreferences(preferences)
        }
    }

    suspend fun upsert(profile: RouterProfile, secrets: RouterSecrets, select: Boolean) {
        require(profile.schemaVersion == 2 && profile.id.isNotBlank() && profile.displayName.isNotBlank())
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
            val secrets = RouterSecrets.fromServerSettings(settings).copy(
                companionToken = previousSecrets.companionToken,
                companionDeviceId = previousSecrets.companionDeviceId,
            )
            writeSnapshot(preferences, current.index.copy(profiles = profiles), current.secrets + (profile.id to secrets))
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
            defaultSnapshot()
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

    private fun defaultSnapshot(): Pair<RouterProfileIndex, Map<String, RouterSecrets>> {
        val settings = ServerSettings()
        val id = DEFAULT_PROFILE_ID
        val profile = RouterProfile.fromServerSettings(id, "Keenetic ${settings.host}", settings)
        return RouterProfileIndex(selectedProfileId = id, profiles = listOf(profile)) to
            mapOf(id to RouterSecrets.fromServerSettings(settings))
    }

    private fun writeSnapshot(preferences: androidx.datastore.preferences.core.MutablePreferences, index: RouterProfileIndex, secrets: Map<String, RouterSecrets>) {
        preferences[SettingsKeys.profiles] = RouterProfileCodec.encodeIndex(index)
        preferences[SettingsKeys.secrets] = cipher.encrypt(RouterProfileCodec.encodeSecrets(secrets))
    }

    private fun removeObsoletePreferences(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.asMap().keys.filter { it.name in OBSOLETE_PREFERENCE_NAMES }.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            preferences.remove(key as Preferences.Key<Any>)
        }
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "default-router"
        private val OBSOLETE_PREFERENCE_NAMES = setOf(
            "host", "port", "login", "pass_enc", "iface", "srv_key", "endpoint", "subnet", "dns",
            "mtu", "keepalive", "collector_url", "collector_token_enc", "xkeen_controller_url",
            "xkeen_controller_token_enc", "migration_0_7_review_pending",
        )
    }
}
