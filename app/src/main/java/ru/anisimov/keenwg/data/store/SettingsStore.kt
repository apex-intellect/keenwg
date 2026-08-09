package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import ru.anisimov.keenwg.domain.model.RouterProfilesState
import ru.anisimov.keenwg.domain.model.ServerSettings

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsStore internal constructor(
    internal val routerProfiles: RouterProfileStore,
) {
    internal constructor(dataStore: DataStore<Preferences>, cipher: SecretCipher) : this(RouterProfileStore(dataStore, cipher))
    constructor(context: Context, cipher: SecretCipher) : this(context.settingsDataStore, cipher)

    val profileState: Flow<RouterProfilesState> = routerProfiles.state
    val settings: Flow<ServerSettings> = routerProfiles.activeSettings.filterNotNull()

    suspend fun migrateLegacy() = routerProfiles.migrateLegacy()
    suspend fun save(s: ServerSettings) = routerProfiles.saveActive(s)
}
