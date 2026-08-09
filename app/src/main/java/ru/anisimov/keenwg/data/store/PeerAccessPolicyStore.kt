package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.anisimov.keenwg.domain.model.AccessPolicy

private val Context.accessPolicyDataStore by preferencesDataStore("access_policies")

class PeerAccessPolicyStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) : AccessPolicyStore {
    override suspend fun put(publicKey: String, policy: AccessPolicy) {
        context.accessPolicyDataStore.edit { it[stringPreferencesKey(publicKey)] = json.encodeToString(policy) }
    }

    override suspend fun get(publicKey: String): AccessPolicy? {
        val value = context.accessPolicyDataStore.data.first()[stringPreferencesKey(publicKey)] ?: return null
        return runCatching { json.decodeFromString<AccessPolicy>(value) }.getOrNull()
    }

    override suspend fun rotate(oldPublicKey: String, newPublicKey: String, policy: AccessPolicy) {
        context.accessPolicyDataStore.edit {
            it.remove(stringPreferencesKey(oldPublicKey))
            it[stringPreferencesKey(newPublicKey)] = json.encodeToString(policy)
        }
    }

    override suspend fun remove(publicKey: String) {
        context.accessPolicyDataStore.edit { it.remove(stringPreferencesKey(publicKey)) }
    }
}
