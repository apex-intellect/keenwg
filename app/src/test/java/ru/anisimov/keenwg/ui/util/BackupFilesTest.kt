package ru.anisimov.keenwg.ui.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilesTest {
    @Test fun `encrypted backup export stays in dedicated cache path and replaces old files`() {
        val cache = Files.createTempDirectory("keenwg-backup-test").toFile()
        val directory = File(cache, "backup").apply { mkdirs() }
        File(directory, "old.kwgb").writeText("old")

        val file = writeBackupExportFile(cache, "encrypted".toByteArray())

        assertTrue(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        assertTrue(file.readBytes().contentEquals("encrypted".toByteArray()))
        assertFalse(File(directory, "old.kwgb").exists())
    }

    @Test fun `empty or oversized backup is rejected before writing`() {
        val cache = Files.createTempDirectory("keenwg-backup-limit").toFile()

        assertTrue(runCatching { writeBackupExportFile(cache, byteArrayOf()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { writeBackupExportFile(cache, ByteArray(4 * 1024 * 1024 + 1)) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertFalse(File(cache, "backup").listFiles().orEmpty().any())
    }
}
