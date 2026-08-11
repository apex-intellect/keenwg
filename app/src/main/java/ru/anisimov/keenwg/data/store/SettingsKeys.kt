package ru.anisimov.keenwg.data.store

import androidx.datastore.preferences.core.stringPreferencesKey

internal object SettingsKeys {
    val profiles = stringPreferencesKey("router_profiles_json")
    val secrets = stringPreferencesKey("router_secrets_enc")
}
