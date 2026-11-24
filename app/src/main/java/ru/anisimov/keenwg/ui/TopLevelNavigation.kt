package ru.anisimov.keenwg.ui

import androidx.navigation.NavHostController
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination

/** One policy for overview shortcuts and the bottom tabs. */
internal fun NavHostController.openTopLevel(target: TopLevelDestination) {
    val route: Any = when (target) {
        TopLevelDestination.OVERVIEW -> OverviewRoute
        TopLevelDestination.CONNECTIONS -> ConnectionsRoute
        TopLevelDestination.ROUTES -> RoutesRoute
        TopLevelDestination.ACCESS -> AccessRoute
        TopLevelDestination.SYSTEM -> SystemRoute
    }
    navigate(route) {
        popUpTo<OverviewRoute> { saveState = true }
        launchSingleTop = true
        // Non-inclusive popUpTo can associate the popped stack with Overview.
        // Restoring that alias when Home is tapped reopens the section instead.
        // Overview already lives in the stack; only other tabs need restoration.
        restoreState = target != TopLevelDestination.OVERVIEW
    }
}
