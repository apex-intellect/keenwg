package ru.anisimov.keenwg.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.confDataStore by preferencesDataStore("confs")

/** Keeps a staged private configuration encrypted until its single explicit reveal or cleanup. */
class PeerConfStore(private val context: Context, private val cipher: SecretCipher) : ConfStore {
    private val revealLock = Mutex()

    override suspend fun put(pubkey: String, conf: String) {
        context.confDataStore.edit { it[stringPreferencesKey(pubkey)] = cipher.encrypt(conf) }
    }

    override suspend fun get(pubkey: String): String? {
        val enc = context.confDataStore.data.first()[stringPreferencesKey(pubkey)] ?: return null
        return runCatching { cipher.decrypt(enc) }.getOrNull()
    }

    override suspend fun remove(pubkey: String) {
        context.confDataStore.edit { it.remove(stringPreferencesKey(pubkey)) }
    }

    override suspend fun take(pubkey: String): String? = revealLock.withLock {
        val value = get(pubkey) ?: return@withLock null
        remove(pubkey)
        value
    }

    override suspend fun replace(oldPublicKey: String, newPublicKey: String, conf: String) {
        context.confDataStore.edit {
            it[stringPreferencesKey(newPublicKey)] = cipher.encrypt(conf)
            it.remove(stringPreferencesKey(oldPublicKey))
        }
    }
}
