package ru.anisimov.keenwg.ui.navigation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomIslandInteractionTest {
    @Test fun `rounded item explicitly clips its bounded press indication`() {
        val source = String(Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/navigation/KeenBottomIsland.kt")))
        assertFalse(source.contains("import androidx.compose.foundation.clickable"))
        assertTrue(source.contains("onClick = { onSelect(destination) }"))
        assertTrue(source.contains(".clip(itemShape)"))
        assertTrue(source.contains("destinations.forEach"))
    }
}
