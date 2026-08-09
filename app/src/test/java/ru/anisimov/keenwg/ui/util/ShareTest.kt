package ru.anisimov.keenwg.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShareTest {
    @Test fun `export filename cannot escape cache directory`() {
        val name = sanitizeConfFileName(" ../../личный/ключ\\phone ")

        assertEquals("phone", name.takeLast(5))
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertFalse(name.contains(".."))
    }

    @Test fun `empty export name has stable fallback and bounded length`() {
        assertEquals("peer", sanitizeConfFileName("..."))
        assertEquals(64, sanitizeConfFileName("a".repeat(100)).length)
    }

    @Test fun `support exports stay inside dedicated cache directory and replace previous files`() {
        val cache = Files.createTempDirectory("keenwg-support-test").toFile()
        val previousDir = File(cache, "support").apply { mkdirs() }
        File(previousDir, "old.json").writeText("old")

        val files = writeSupportExportFiles(
            cache,
            generatedAt = "2026-08-09T05:30:00Z",
            json = "{\"schema_version\":1}",
            text = "KeenWG support report",
        )

        assertEquals(listOf("keenwg-support-20260809-053000.json", "keenwg-support-20260809-053000.txt"), files.map { it.name })
        assertTrue(files.all { it.canonicalPath.startsWith(previousDir.canonicalPath + File.separator) })
        assertFalse(File(previousDir, "old.json").exists())
        assertEquals("{\"schema_version\":1}", files.first().readText())
    }

    @Test fun `support export rejects oversized content before writing`() {
        val cache = Files.createTempDirectory("keenwg-support-limit").toFile()
        val failure = runCatching {
            writeSupportExportFiles(cache, "2026-08-09T05:30:00Z", "x".repeat(65_537), "ok")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(File(cache, "support").listFiles().orEmpty().any())
    }
}
