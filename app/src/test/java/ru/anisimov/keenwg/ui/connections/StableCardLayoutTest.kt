package ru.anisimov.keenwg.ui.connections

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableCardLayoutTest {
    @Test fun `asynchronous route results use reserved single-line slots`() {
        val connections = source("connections/ConnectionsScreen.kt")
        val xkeen = source("xkeen/XkeenScreen.kt")

        assertTrue(connections.contains("height(20.dp)"))
        assertTrue(xkeen.contains("height(22.dp)"))
        assertTrue(connections.contains("maxLines = 1"))
        assertTrue(xkeen.contains("maxLines = 1"))
        assertFalse(connections.contains("animateContentSize"))
        assertFalse(xkeen.contains("animateContentSize"))
    }

    private fun source(relative: String): String = String(
        Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/$relative")),
    )
}
