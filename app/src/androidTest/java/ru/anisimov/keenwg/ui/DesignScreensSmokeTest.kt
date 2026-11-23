package ru.anisimov.keenwg.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.about.AboutScreen
import ru.anisimov.keenwg.ui.add.AddPeerScreen
import ru.anisimov.keenwg.ui.backup.BackupScreen
import ru.anisimov.keenwg.ui.connections.ConnectionsScreen
import ru.anisimov.keenwg.ui.detail.PeerDetailScreen
import ru.anisimov.keenwg.ui.navigation.KeenBottomIsland
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination
import ru.anisimov.keenwg.ui.network.NetworkScreen
import ru.anisimov.keenwg.ui.overview.OverviewHealth
import ru.anisimov.keenwg.ui.overview.OverviewScreen
import ru.anisimov.keenwg.ui.overview.OverviewState
import ru.anisimov.keenwg.ui.peers.PeerListScreen
import ru.anisimov.keenwg.ui.settings.SettingsScreen
import ru.anisimov.keenwg.ui.setup.SetupScreen
import ru.anisimov.keenwg.ui.support.SupportScreen
import ru.anisimov.keenwg.ui.system.DevicesScreen
import ru.anisimov.keenwg.ui.system.RouterConnectionScreen
import ru.anisimov.keenwg.ui.system.SystemScreen
import ru.anisimov.keenwg.ui.theme.KeenWgTheme
import ru.anisimov.keenwg.ui.update.CompanionUpdateScreen

/** Real screens in their unpaired state: no fixture router, credentials or fabricated telemetry. */
class DesignScreensSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun initialize() { ServiceLocator.init(context) }

    @Test fun largeNavigationKeepsEveryDestinationSelectable() {
        val selected = mutableStateOf(TopLevelDestination.OVERVIEW)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                KeenWgTheme {
                    KeenBottomIsland(selected.value, TopLevelDestination.entries, { selected.value = it })
                }
            }
        }
        listOf(
            R.string.nav_overview_description, R.string.nav_connections_description,
            R.string.nav_routes_description, R.string.nav_access_description, R.string.nav_system_description,
        ).forEach { label ->
            compose.onNodeWithContentDescription(context.getString(label))
                .assertIsDisplayed().performClick().assertIsSelected()
        }
    }

    @Test fun allScreensLightDarkAndLargeText() {
        val page = mutableStateOf("overview")
        val dark = mutableStateOf(false)
        val large = mutableStateOf(false)
        val state = OverviewState(loading = false, health = OverviewHealth.SETUP_REQUIRED)
        val roots = listOf("overview", "connections", "rules", "access", "system")
        val pages = roots + listOf("connection", "companion", "setup", "devices", "support", "backup", "about", "settings", "add", "peer")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 2f else 1f)) {
                KeenWgTheme(darkTheme = dark.value) {
                    Scaffold(
                        contentWindowInsets = rootScaffoldContentInsets(),
                        bottomBar = {
                            if (page.value in roots) KeenBottomIsland(
                                TopLevelDestination.entries[roots.indexOf(page.value)],
                                TopLevelDestination.entries,
                                {},
                            )
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            key(page.value) {
                                when (page.value) {
                                    "overview" -> OverviewScreen(state, {}, {}, {}, {})
                                    "connections" -> ConnectionsScreen({})
                                    "rules" -> NetworkScreen()
                                    "access" -> PeerListScreen({}, {}, {}, writable = false)
                                    "system" -> SystemScreen(state, {}, {}, {}, {}, {}, {})
                                    "connection" -> RouterConnectionScreen(state, {}, {}, {}, {})
                                    "companion" -> CompanionUpdateScreen({}, {}, {})
                                    "setup" -> SetupScreen({}, {})
                                    "devices" -> DevicesScreen({})
                                    "support" -> SupportScreen({}, {})
                                    "backup" -> BackupScreen({})
                                    "about" -> AboutScreen({}, {}, {})
                                    "settings" -> SettingsScreen(onBack = {})
                                    "add" -> AddPeerScreen({}, {}, {})
                                    "peer" -> PeerDetailScreen("unpaired-test-peer", {}, writable = false)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (mode in listOf("light", "dark", "large")) {
            compose.runOnIdle { dark.value = mode == "dark"; large.value = mode == "large" }
            for (name in pages) {
                compose.runOnIdle { page.value = name }
                compose.waitForIdle()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val file = File(context.getExternalFilesDir(null), "design-qa/$name-$mode.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
