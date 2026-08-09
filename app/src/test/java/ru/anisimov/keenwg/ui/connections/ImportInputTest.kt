package ru.anisimov.keenwg.ui.connections

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportInputTest {
    @Test fun `bounded reader stops after parser limit plus sentinel byte`() {
        val input = ByteArrayInputStream(ByteArray(1_048_900) { 7 })

        val result = readImportBytes(input)

        assertEquals(1_048_577, result.size)
    }
}
