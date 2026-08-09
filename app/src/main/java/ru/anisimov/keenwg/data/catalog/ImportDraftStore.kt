package ru.anisimov.keenwg.data.catalog

import android.content.Context
import java.util.Base64
import ru.anisimov.keenwg.data.store.SecretCipher

interface ImportDraftPersistence {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

interface ImportDraftGateway {
    fun put(source: ByteArray)
    fun take(): ByteArray?
    fun clear()
}

class SharedPreferencesImportDraftPersistence(context: Context) : ImportDraftPersistence {
    private val preferences = context.applicationContext.getSharedPreferences("keenwg_import_draft", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString(KEY, null)
    override fun write(value: String) {
        check(preferences.edit().putString(KEY, value).commit()) { "Draft storage unavailable" }
    }
    override fun clear() {
        check(preferences.edit().remove(KEY).commit()) { "Draft storage unavailable" }
    }
    private companion object { const val KEY = "encrypted_draft_v1" }
}

class ImportDraftStore(
    private val persistence: ImportDraftPersistence,
    private val cipher: SecretCipher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ImportDraftGateway {
    @Synchronized
    override fun put(source: ByteArray) {
        try {
            require(source.isNotEmpty() && source.size <= MAX_BYTES) { "Invalid import draft" }
            val encoded = Base64.getEncoder().encodeToString(source)
            val encrypted = cipher.encrypt(encoded)
            persistence.write("${nowMillis()}\n$encrypted")
        } finally {
            source.fill(0)
        }
    }

    @Synchronized
    override fun take(): ByteArray? {
        val stored = persistence.read() ?: return null
        persistence.clear()
        val split = stored.indexOf('\n')
        if (split <= 0) return null
        val createdAt = stored.substring(0, split).toLongOrNull() ?: return null
        val age = nowMillis() - createdAt
        if (age < 0 || age > TTL_MILLIS) return null
        return try {
            Base64.getDecoder().decode(cipher.decrypt(stored.substring(split + 1))).takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized override fun clear() = persistence.clear()

    private companion object {
        const val MAX_BYTES = 1_000_000
        const val TTL_MILLIS = 10 * 60 * 1_000L
    }
}
