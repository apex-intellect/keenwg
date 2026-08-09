package ru.anisimov.keenwg.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RootScaffoldInsetsTest {
    @Test
    fun `root scaffold leaves system bars to each screen scaffold`() {
        val method = Class.forName("ru.anisimov.keenwg.ui.KeenWgNavKt")
            .declaredMethods
            .singleOrNull { it.name == "rootScaffoldContentInsets" }

        assertNotNull("KeenWgNav must define the root inset policy", method)
        method!!.isAccessible = true
        val insets = method.invoke(null) as WindowInsets
        val density = Density(1f)

        assertEquals(0, insets.getTop(density))
        assertEquals(0, insets.getBottom(density))
    }
}
