package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import ru.anisimov.keenwg.data.collector.PeerId

interface LineageStore {
    suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String)
    suspend fun idsFor(publicKey: String): List<String>
    suspend fun remove(publicKey: String)
}

private val Context.lineageDataStore by preferencesDataStore("peer_lineage")

class PeerLineageStore(private val context: Context) : LineageStore {
    override suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String) {
        context.lineageDataStore.edit { preferences ->
            val inherited = preferences[stringPreferencesKey(oldPublicKey)]
                ?.split(',')?.filter(String::isNotBlank).orEmpty()
            val ids = (inherited + PeerId.compute(interfaceId, oldPublicKey) + PeerId.compute(interfaceId, newPublicKey)).distinct()
            preferences[stringPreferencesKey(newPublicKey)] = ids.joinToString(",")
            preferences.remove(stringPreferencesKey(oldPublicKey))
        }
    }

    override suspend fun idsFor(publicKey: String): List<String> =
        context.lineageDataStore.data.first()[stringPreferencesKey(publicKey)]
            ?.split(',')?.filter(String::isNotBlank).orEmpty()

    override suspend fun remove(publicKey: String) {
        context.lineageDataStore.edit { it.remove(stringPreferencesKey(publicKey)) }
    }
}

object EmptyLineageStore : LineageStore {
    override suspend fun recordRotation(interfaceId: String, oldPublicKey: String, newPublicKey: String) = Unit
    override suspend fun idsFor(publicKey: String) = emptyList<String>()
    override suspend fun remove(publicKey: String) = Unit
}
