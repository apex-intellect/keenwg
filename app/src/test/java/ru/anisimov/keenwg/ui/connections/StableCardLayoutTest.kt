package ru.anisimov.keenwg.ui.connections

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableCardLayoutTest {
    @Test fun `route diagnostics stay in a compact trailing slot`() {
        val connections = source("connections/ConnectionsScreen.kt")
        val xkeen = source("xkeen/XkeenScreen.kt")

        assertTrue(connections.contains("ConnectionCardTrailing"))
        assertFalse(connections.contains("Box(Modifier.fillMaxWidth().height(20.dp)"))
        assertFalse(connections.contains("heightIn(min = 158.dp)"))
        assertTrue(connections.contains("heightIn(min = 96.dp)"))
        assertTrue(xkeen.contains("height(22.dp)"))
        assertTrue(connections.contains("maxLines = 1"))
        assertTrue(xkeen.contains("maxLines = 1"))
        assertFalse(connections.contains("animateContentSize"))
        assertFalse(xkeen.contains("animateContentSize"))
    }

    @Test fun `top level actions are grouped instead of stacked full width`() {
        val connections = source("connections/ConnectionsScreen.kt")

        assertTrue(connections.contains("ConnectionToolbar"))
        assertTrue(connections.contains("SubscriptionSources"))
        assertTrue(connections.contains("heightIn(min = 72.dp)"))
        assertFalse(connections.contains("item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()"))
    }

    @Test fun `active route uses a strong selected surface`() {
        val connections = source("connections/ConnectionsScreen.kt")

        assertTrue(connections.contains("containerColor = if (card.active) MaterialTheme.colorScheme.primary"))
        assertFalse(connections.contains("card.active) MaterialTheme.colorScheme.tertiaryContainer"))
    }

    private fun source(relative: String): String = String(
        Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/$relative")),
    )
}
