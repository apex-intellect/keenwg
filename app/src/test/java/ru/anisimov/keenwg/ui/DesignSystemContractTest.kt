package ru.anisimov.keenwg.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemContractTest {
    @Test fun `feature layouts use shared dimensions and palette`() {
        val root = Path.of("src/main/java/ru/anisimov/keenwg/ui")
        val literal = Regex("""(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b|Color\(0x""")
        val offenders = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.toString().contains("/theme/") }
                .filter { literal.containsMatchIn(String(Files.readAllBytes(it))) }
                .map { root.relativize(it).toString() }.toList()
        }
        assertTrue("Move visual constants into theme tokens: $offenders", offenders.isEmpty())
    }

    @Test fun `native controls are wrapped without replacing accessibility semantics`() {
        val controls = String(Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/ui/components/KeenControls.kt")))
        assertTrue(controls.contains("modifier.heightIn(min = KeenSize.touch)"))
        assertTrue(controls.contains("ContinuousShape(KeenRadius.control)"))
        assertTrue(controls.contains("enabled: Boolean = true"))
        assertTrue(controls.contains("content: @Composable RowScope.() -> Unit"))
    }
}
