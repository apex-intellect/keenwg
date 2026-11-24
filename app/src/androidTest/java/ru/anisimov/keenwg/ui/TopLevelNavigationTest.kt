package ru.anisimov.keenwg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.ui.navigation.KeenFloatingNavigationHost
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination
import ru.anisimov.keenwg.ui.theme.KeenWgTheme

class TopLevelNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController

    private fun content() {
        compose.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val selected = when {
                entry?.destination?.hasRoute<ConnectionsRoute>() == true -> TopLevelDestination.CONNECTIONS
                entry?.destination?.hasRoute<RoutesRoute>() == true -> TopLevelDestination.ROUTES
                entry?.destination?.hasRoute<AccessRoute>() == true -> TopLevelDestination.ACCESS
                entry?.destination?.hasRoute<SystemRoute>() == true -> TopLevelDestination.SYSTEM
                else -> TopLevelDestination.OVERVIEW
            }
            KeenWgTheme {
                KeenFloatingNavigationHost(selected, TopLevelDestination.entries, nav::openTopLevel) {
                    NavHost(nav, startDestination = OverviewRoute) {
                        composable<OverviewRoute> {
                            Column {
                                TopLevelDestination.entries.forEach { target ->
                                    Button(onClick = { nav.openTopLevel(target) }, modifier = Modifier.testTag("tile-$target")) { Text(target.name) }
                                }
                            }
                        }
                        composable<ConnectionsRoute> {
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Button(onClick = { count++ }, modifier = Modifier.testTag("increment")) { Text("Count $count") }
                        }
                        composable<RoutesRoute> { Text("Rules") }
                        composable<AccessRoute> { Text("Access") }
                        composable<SystemRoute> { Text("System") }
                    }
                }
            }
        }
    }

    @Test fun overviewShortcutThenHomeAlwaysReturnsHomeAcrossRepeatedTabSwitches() {
        content()
        repeat(3) {
            TopLevelDestination.entries.filter { it != TopLevelDestination.OVERVIEW }.forEach { target ->
                compose.onNodeWithTag("tile-$target").performClick()
                compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.nav_overview_description)).performClick()
                compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<OverviewRoute>()) }
            }
        }
    }

    @Test fun switchingTabsKeepsTheSectionStateWithoutRestoringItAsHome() {
        content()
        compose.onNodeWithTag("tile-CONNECTIONS").performClick()
        compose.onNodeWithTag("increment").performClick()
        compose.runOnIdle { nav.openTopLevel(TopLevelDestination.OVERVIEW) }
        compose.onNodeWithTag("tile-ACCESS").performClick()
        compose.runOnIdle { nav.openTopLevel(TopLevelDestination.OVERVIEW) }
        compose.onNodeWithTag("tile-CONNECTIONS").performClick()
        compose.onNodeWithText("Count 1").assertExists()
    }

    @Test fun homeRecoversFromTheLegacySavedStackAlias() {
        content()
        compose.runOnIdle {
            nav.navigate(ConnectionsRoute)
            // Reproduce 2.3.1's overview-shortcut / Home sequence using its old options.
            nav.navigate(OverviewRoute) {
                popUpTo<OverviewRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            assertTrue("Legacy options reproduce the reported wrong destination", nav.currentDestination!!.hasRoute<ConnectionsRoute>())
            nav.openTopLevel(TopLevelDestination.OVERVIEW)
            assertTrue(nav.currentDestination!!.hasRoute<OverviewRoute>())
            nav.openTopLevel(TopLevelDestination.ACCESS)
            nav.popBackStack()
            assertTrue("System Back must still reach Home", nav.currentDestination!!.hasRoute<OverviewRoute>())
        }
    }
}
