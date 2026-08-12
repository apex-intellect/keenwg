package ru.anisimov.keenwg.ui.navigation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomIslandInteractionTest {
    @Test fun `material navigation items keep press indication inside the rounded island`() {
        val source = String(Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/navigation/KeenBottomIsland.kt")))
        assertFalse(source.contains("import androidx.compose.foundation.clickable"))
        assertFalse(source.contains("Surface(\n                        onClick"))
        assertTrue(source.contains("NavigationBar("))
        assertTrue(source.contains("NavigationBarItem("))
        assertTrue(source.contains("onClick = { onSelect(destination) }"))
        assertTrue(source.contains(".clip(itemShape)"))
        assertTrue(source.contains("navigationBarsPadding()"))
        assertTrue(source.contains("destinations.forEach"))
    }
}
