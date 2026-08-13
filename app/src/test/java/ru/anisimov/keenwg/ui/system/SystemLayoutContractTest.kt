package ru.anisimov.keenwg.ui.system

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLayoutContractTest {
    @Test fun `settings and language picker use compact content sized layout`() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/system/SystemScreen.kt")),
        )

        assertTrue(source.contains("padding(horizontal = 12.dp, vertical = 8.dp)"))
        assertTrue(source.contains("Dialog(onDismissRequest = onDismiss)"))
        assertTrue(source.contains("heightIn(min = 48.dp)"))
        assertTrue(source.contains("widthIn(max = 360.dp)"))
        assertFalse(source.contains("AlertDialog("))
    }
}
