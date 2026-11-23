package ru.anisimov.keenwg.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import ru.anisimov.keenwg.ui.theme.KeenSize

/** Extra scroll tail, not viewport padding: content can move underneath the floating bar. */
val LocalKeenBottomClearance = staticCompositionLocalOf<Dp> { KeenSize.zero }

@Composable
fun KeenFloatingNavigationHost(
    selected: TopLevelDestination,
    destinations: List<TopLevelDestination>,
    onSelect: (TopLevelDestination) -> Unit,
    showNavigation: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sourceLayer = rememberGraphicsLayer()
    val backdrop = remember(sourceLayer) { KeenBackdrop(sourceLayer) }
    var barHeight by remember { mutableIntStateOf(0) }
    val clearance = with(LocalDensity.current) { if (showNavigation) barHeight.toDp() else KeenSize.zero }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalKeenBottomClearance provides clearance) {
            Box(Modifier.fillMaxSize().keenBackdropSource(backdrop)) { content() }
        }
        if (showNavigation) {
            KeenBottomIsland(
                selected, destinations, onSelect,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = it.height },
                backdrop = backdrop,
            )
        }
    }
}
