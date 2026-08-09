package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.xkeenPreferenceDataStore by preferencesDataStore("xkeen_preferences")

data class XkeenPreferences(
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
)

interface XkeenPreferenceGateway {
    val preferences: Flow<XkeenPreferences>
    suspend fun toggleFavorite(identity: String)
    suspend fun recordSelected(identity: String)
}

fun serverIdentity(host: String, port: Int): String {
    require(port in 1..65535)
    val normalized = host.trim().lowercase()
    require(normalized.isNotBlank() && normalized.none { it.isWhitespace() || it == '|' || it == '\n' })
    return "$normalized:$port"
}

fun updatedRecent(current: List<String>, identity: String): List<String> =
    (listOf(identity) + current.filterNot { it == identity }).take(5)

class XkeenPreferenceStore internal constructor(private val dataStore: DataStore<Preferences>) : XkeenPreferenceGateway {
    constructor(context: Context) : this(context.xkeenPreferenceDataStore)

    private object Keys {
        val favorites = stringSetPreferencesKey("favorites")
        val recent = stringPreferencesKey("recent")
    }

    override val preferences: Flow<XkeenPreferences> = dataStore.data.map { values ->
        XkeenPreferences(
            favorites = values[Keys.favorites]?.toSet().orEmpty(),
            recent = values[Keys.recent].orEmpty().lineSequence().filter(String::isNotBlank).take(5).toList(),
        )
    }

    override suspend fun toggleFavorite(identity: String) {
        requireValidIdentity(identity)
        dataStore.edit { values ->
            val current = values[Keys.favorites]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(identity)) current.remove(identity)
            values[Keys.favorites] = current
        }
    }

    override suspend fun recordSelected(identity: String) {
        requireValidIdentity(identity)
        dataStore.edit { values ->
            val current = values[Keys.recent].orEmpty().lineSequence().filter(String::isNotBlank).toList()
            values[Keys.recent] = updatedRecent(current, identity).joinToString("\n")
        }
    }

    private fun requireValidIdentity(identity: String) {
        require(identity.length in 3..320 && identity.none { it.isWhitespace() || it == '|' || it == '\n' })
    }
}
