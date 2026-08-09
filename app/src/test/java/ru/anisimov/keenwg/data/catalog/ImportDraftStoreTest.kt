package ru.anisimov.keenwg.data.catalog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.data.store.SecretCipher

class ImportDraftStoreTest {
    @Test fun `draft is encrypted zeroed and consumed once`() {
        val persistence = MemoryDraftPersistence()
        val store = ImportDraftStore(persistence, PrefixCipher(), nowMillis = { 1_000L })
        val raw = "vless://private-user@vpn.example:443".toByteArray()
        val expected = raw.copyOf()

        store.put(raw)

        assertTrue(raw.all { it == 0.toByte() })
        assertFalse(persistence.value.orEmpty().contains("private-user"))
        assertArrayEquals(expected, store.take())
        assertNull(store.take())
        assertNull(persistence.value)
    }

    @Test fun `draft expires after ten minutes and input is zeroed on encryption failure`() {
        var now = 5_000L
        val persistence = MemoryDraftPersistence()
        val store = ImportDraftStore(persistence, PrefixCipher(), nowMillis = { now })
        val raw = "trojan://credential@vpn.example:443".toByteArray()
        store.put(raw)
        now += 10 * 60 * 1_000 + 1
        assertNull(store.take())
        assertNull(persistence.value)

        val rejected = "secret".toByteArray()
        runCatching { ImportDraftStore(persistence, FailingCipher()).put(rejected) }
        assertTrue(rejected.all { it == 0.toByte() })
    }

    private class MemoryDraftPersistence : ImportDraftPersistence {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private class PrefixCipher : SecretCipher {
        override fun encrypt(plain: String) = "encrypted:${plain.reversed()}"
        override fun decrypt(blob: String) = blob.removePrefix("encrypted:").reversed()
    }

    private class FailingCipher : SecretCipher {
        override fun encrypt(plain: String): String = error("failed")
        override fun decrypt(blob: String): String = error("failed")
    }
}
