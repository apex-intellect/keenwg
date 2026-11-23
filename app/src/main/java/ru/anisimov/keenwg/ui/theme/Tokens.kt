package ru.anisimov.keenwg.ui.theme

import androidx.compose.ui.unit.dp

/** Layout roles shared by every feature. Primitive dimensions belong only here. */
object KeenSpacing {
    val screen = 20.dp
    val card = 18.dp
    val section = 24.dp
    val item = 16.dp
    val group = 12.dp
    val inline = 8.dp
    val tight = 4.dp
    val navigationInset = 14.dp
    val navigationBottom = 12.dp
    val navigationContent = 6.dp
}

object KeenRadius {
    val badge = 8.dp
    val control = 14.dp
    val card = 27.dp
    val sheet = 32.dp
    val navigation = 29.dp
    val navigationItem = 22.dp
}

object KeenSize {
    val hairline = 1.dp
    val touch = 48.dp
    val icon = 22.dp
    val navigationItem = 54.dp
    val navigationShadow = 4.dp
    val zero = 0.dp
}

object KeenOpacity {
    const val glass = 0.92f
    const val glassRim = 0.55f
}

object KeenLayout {
    const val compactWidth = 600
    const val largeTextThreshold = 1.4f
}
