package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.uiPreferencesDataStore by preferencesDataStore("ui_preferences")

class ExpertModeStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.uiPreferencesDataStore)

    val enabled: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[EXPERT_MODE] ?: false }
        .distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[EXPERT_MODE] = enabled }
    }

    private companion object {
        val EXPERT_MODE = booleanPreferencesKey("expert_mode")
    }
}
