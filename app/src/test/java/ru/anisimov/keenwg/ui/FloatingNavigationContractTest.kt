package ru.anisimov.keenwg.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingNavigationContractTest {
    private fun source(name: String) = String(Files.readAllBytes(Path.of("src/main/java/ru/anisimov/keenwg/$name")))

    @Test fun `root navigation overlays the viewport instead of reserving a bottom bar`() {
        val host = source("ui/navigation/KeenFloatingNavigationHost.kt")
        assertTrue(host.contains("Alignment.BottomCenter"))
        assertTrue(host.contains("keenBackdropSource(backdrop)"))
        assertTrue(host.contains("LocalKeenBottomClearance provides clearance"))
        assertFalse(host.contains("Scaffold("))
        assertFalse(source("ui/KeenWgNav.kt").contains("bottomBar ="))
    }

    @Test fun `glass blurs background before drawing sharp controls and has a legacy fallback`() {
        val glass = source("ui/navigation/KeenBackdrop.kt")
        assertTrue(glass.contains("Build.VERSION_CODES.S"))
        assertTrue(glass.contains("layer.renderEffect = BlurEffect"))
        assertTrue(glass.contains("KeenGlass.fallbackTint"))
        assertTrue(glass.indexOf("layer.renderEffect = BlurEffect") < glass.lastIndexOf("drawContent()"))
        assertTrue(glass.contains("it.invalidateDraw()"))
    }

    @Test fun `system navigation contrast does not add an opaque rectangle`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)"))
        assertTrue(activity.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(activity.contains("isAppearanceLightNavigationBars = !dark"))
    }
}
