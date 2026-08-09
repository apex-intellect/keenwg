package ru.anisimov.keenwg.data.store

/** Stores each peer's generated client .conf (encrypted) for QR re-show. */
interface ConfStore {
    suspend fun put(pubkey: String, conf: String)
    suspend fun get(pubkey: String): String?
    suspend fun remove(pubkey: String)
    suspend fun take(pubkey: String): String? {
        val value = get(pubkey) ?: return null
        remove(pubkey)
        return value
    }
    suspend fun replace(oldPublicKey: String, newPublicKey: String, conf: String) {
        put(newPublicKey, conf)
        remove(oldPublicKey)
    }
}
