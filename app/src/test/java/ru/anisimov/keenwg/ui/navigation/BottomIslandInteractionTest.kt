package ru.anisimov.keenwg.ui.navigation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomIslandInteractionTest {
    @Test fun `navigation items keep press indication inside their rounded black surfaces`() {
        val source = String(Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/navigation/KeenBottomIsland.kt")))
        assertFalse(source.contains("import androidx.compose.foundation.clickable"))
        assertFalse(source.contains("NavigationBarItem("))
        assertTrue(source.contains("Surface("))
        assertTrue(source.contains("onClick = { onSelect(destination) }"))
        assertTrue(source.contains("shape = itemShape"))
        assertTrue(source.contains("KeenNavigationBlack"))
        assertTrue(source.contains("navigationBarsPadding()"))
        assertTrue(source.contains("destinations.forEach"))
    }

    @Test fun `screen cards do not install unbounded foundation click indications`() {
        val root = Path.of("src/main/java/ru/anisimov/keenwg/ui")
        val offenders = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { String(Files.readAllBytes(it)).contains("import androidx.compose.foundation.clickable") }
                .map { root.relativize(it).toString() }
                .toList()
        }

        assertTrue("Use shaped Material Card/Surface click handlers instead: $offenders", offenders.isEmpty())
    }
}
