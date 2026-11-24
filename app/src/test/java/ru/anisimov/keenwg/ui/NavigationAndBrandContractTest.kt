package ru.anisimov.keenwg.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndBrandContractTest {
    private fun read(path: String) = String(Files.readAllBytes(Path.of("src/main/$path")))

    @Test fun `overview shortcuts and menu share the same navigation policy`() {
        val nav = read("java/ru/anisimov/keenwg/ui/KeenWgNav.kt")
        assertTrue(nav.contains("onSelect = nav::openTopLevel"))
        assertTrue(nav.contains("onOpenDestination = nav::openTopLevel"))
        assertFalse(nav.contains("restoreState = true"))
        val policy = read("java/ru/anisimov/keenwg/ui/TopLevelNavigation.kt")
        assertTrue(policy.contains("restoreState = target != TopLevelDestination.OVERVIEW"))
        assertTrue(policy.contains("saveState = true"))
    }

    @Test fun `launcher and about use the same new mark and company follows theme`() {
        val mark = read("res/drawable/ic_launcher_foreground.xml")
        val mono = read("res/drawable/ic_launcher_monochrome.xml")
        val path = Regex("android:pathData=\"([^\"]+)\"")
        assertTrue(path.find(mark)!!.groupValues[1] == path.find(mono)!!.groupValues[1])
        assertTrue(mark.contains("@color/brand_graphite"))
        assertFalse(mark.contains("#FF00"))
        val about = read("java/ru/anisimov/keenwg/ui/about/AboutScreen.kt")
        assertTrue(about.contains("painterResource(R.drawable.ic_launcher_foreground)"))
        assertTrue(about.substringAfter("private fun CompanyCard").contains("tint = MaterialTheme.colorScheme.onSurface"))
    }
}
