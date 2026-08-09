package ru.anisimov.keenwg.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class XkeenPreferenceStoreTest {
    @Test fun `server identity normalizes hostname and keeps port`() {
        assertEquals("nl.example:443", serverIdentity(" NL.Example ", 443))
    }

    @Test fun `recent history deduplicates newest and stays bounded`() {
        var recent = emptyList<String>()
        listOf("a:1", "b:1", "c:1", "d:1", "e:1", "f:1", "c:1").forEach {
            recent = updatedRecent(recent, it)
        }
        assertEquals(listOf("c:1", "f:1", "e:1", "d:1", "b:1"), recent)
    }
}
