package ru.anisimov.keenwg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.anisimov.keenwg.ui.navigation.KeenFloatingNavigationHost
import ru.anisimov.keenwg.ui.navigation.LocalKeenBottomClearance
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination
import ru.anisimov.keenwg.ui.theme.KeenWgTheme

class FloatingNavigationRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backdropBlursTheSourceAndRefreshesWithoutBlurringLabels() {
        val striped = mutableStateOf(true)
        val blue = mutableStateOf(false)
        compose.setContent {
            KeenWgTheme {
                KeenFloatingNavigationHost(TopLevelDestination.OVERVIEW, TopLevelDestination.entries, {}) {
                    Canvas(Modifier.fillMaxSize().testTag("viewport")) {
                        if (striped.value) {
                            for (x in 0 until size.width.toInt() step 4) {
                                drawRect(if ((x / 4) % 2 == 0) Color.Black else Color.White,
                                    Offset(x.toFloat(), 0f), Size(4f, size.height))
                            }
                        } else drawRect(if (blue.value) Color.Blue else Color.Red)
                    }
                }
            }
        }
        val bar = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Content must continue behind the island", viewport.bottom > bar.bottom)
        val y = bar.top.toInt() + 5 // Clear glass above the icon/label hit areas.
        val center = bar.center.x.toInt()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val samples = (center - 24..center + 24).map { pixels[it, y].red }
        assertTrue("Sharp source stripes must be blurred, not merely tinted", samples.max() - samples.min() < .08f)
        compose.runOnIdle { striped.value = false }
        val red = compose.onRoot().captureToImage().toPixelMap()[center, y]
        assertTrue("Backdrop must update after source redraw", red.red - red.blue > .15f)
        compose.runOnIdle { blue.value = true }
        val changed = compose.onRoot().captureToImage().toPixelMap()[center, y]
        assertTrue("Backdrop must not retain a stale display list", changed.blue - changed.red > .15f)
    }

    @Test fun lastScrollableRowRemainsAboveTheFloatingNavigation() {
        compose.setContent {
            KeenWgTheme {
                KeenFloatingNavigationHost(TopLevelDestination.OVERVIEW, TopLevelDestination.entries, {}) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        repeat(40) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                        Text("Last row", Modifier.height(48.dp).testTag("last-row"))
                        Spacer(Modifier.height(LocalKeenBottomClearance.current + 8.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("last-row").performScrollTo()
        // Scroll to the actual end, including the reserved tail.
        compose.onRoot().performTouchInput { swipeUp() }
        val row = compose.onNodeWithTag("last-row").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("The last row must be reachable above the menu", row.bottom <= bar.top)
    }
}
