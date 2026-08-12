package ru.anisimov.keenwg.ui.setup

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCopyContractTest {
    @Test fun `primary setup copy uses product language instead of transport jargon`() {
        val resources = String(Files.readAllBytes(Path.of("src/main/res/values-ru/strings.xml")))
        val setupCopy = resources.lineSequence()
            .filter { it.contains("<string name=\"setup_") }
            .joinToString("\n")

        assertFalse(setupCopy.contains("fingerprint", ignoreCase = true))
        assertFalse(setupCopy.contains("SSH-пароль", ignoreCase = true))
        assertFalse(setupCopy.contains("Companion", ignoreCase = true))
        assertFalse(setupCopy.contains("rollback=", ignoreCase = true))
        assertTrue(setupCopy.contains("один раз", ignoreCase = true))
    }
}
