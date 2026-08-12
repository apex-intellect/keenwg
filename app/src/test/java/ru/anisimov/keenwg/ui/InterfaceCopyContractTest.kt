package ru.anisimov.keenwg.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceCopyContractTest {
    private val russian = String(Files.readAllBytes(Path.of("src/main/res/values-ru/strings.xml")))

    @Test fun `top level navigation uses task language`() {
        listOf("Главная", "VPN", "Правила", "Доступ", "Настройки").forEach { label ->
            assertTrue("missing navigation label: $label", russian.contains(">$label<"))
        }
        listOf("Обзор", "Связи", "Маршруты", "Система").forEach { oldLabel ->
            val navLines = russian.lineSequence()
                .filter { it.contains("<string name=\"nav_") }
                .joinToString("\n")
            assertFalse("obsolete navigation label: $oldLabel", navLines.contains(">$oldLabel<"))
        }
    }
}
