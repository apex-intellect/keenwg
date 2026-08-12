package ru.anisimov.keenwg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable
import ru.anisimov.keenwg.ui.add.AddPeerScreen
import ru.anisimov.keenwg.ui.backup.BackupScreen
import ru.anisimov.keenwg.ui.detail.PeerDetailScreen
import ru.anisimov.keenwg.ui.connections.ConnectionsScreen
import ru.anisimov.keenwg.ui.navigation.KeenBottomIsland
import ru.anisimov.keenwg.ui.navigation.TopLevelDestination
import ru.anisimov.keenwg.ui.navigation.preserveTopLevelDestination
import ru.anisimov.keenwg.ui.network.NetworkScreen
import ru.anisimov.keenwg.ui.overview.OverviewScreen
import ru.anisimov.keenwg.ui.overview.OverviewViewModel
import ru.anisimov.keenwg.ui.peers.PeerListScreen
import ru.anisimov.keenwg.ui.settings.SettingsScreen
import ru.anisimov.keenwg.ui.setup.SetupScreen
import ru.anisimov.keenwg.ui.system.DevicesScreen
import ru.anisimov.keenwg.ui.system.SystemScreen
import ru.anisimov.keenwg.ui.support.SupportScreen
import ru.anisimov.keenwg.ui.xkeen.XkeenScreen

@Serializable data object OverviewRoute
@Serializable data object ConnectionsRoute
@Serializable data object RoutesRoute
@Serializable data object AccessRoute
@Serializable data object SystemRoute
@Serializable data object AdvancedSettingsRoute
@Serializable data object SetupRoute
@Serializable data object DevicesRoute
@Serializable data object SupportRoute
@Serializable data object BackupRoute
@Serializable data object AddPeerRoute
@Serializable data class PeerDetailRoute(val publicKey: String)

internal fun rootScaffoldContentInsets(): WindowInsets = WindowInsets(0)

@Composable
fun KeenWgNav() {
    val nav = rememberNavController()
    val overviewViewModel: OverviewViewModel = viewModel()
    val overviewState by overviewViewModel.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val selected = when {
        destination?.hasRoute<ConnectionsRoute>() == true -> TopLevelDestination.CONNECTIONS
        destination?.hasRoute<RoutesRoute>() == true -> TopLevelDestination.ROUTES
        destination?.hasRoute<AccessRoute>() == true -> TopLevelDestination.ACCESS
        destination?.hasRoute<SystemRoute>() == true -> TopLevelDestination.SYSTEM
        else -> TopLevelDestination.OVERVIEW
    }
    val isTopLevel = destination == null || destination.hasRoute<OverviewRoute>() || destination.hasRoute<ConnectionsRoute>() ||
        destination.hasRoute<RoutesRoute>() || destination.hasRoute<AccessRoute>() || destination.hasRoute<SystemRoute>()

    LaunchedEffect(selected, overviewState.destinations, isTopLevel) {
        if (isTopLevel && preserveTopLevelDestination(selected, overviewState.destinations) != selected) {
            nav.navigate(OverviewRoute) {
                popUpTo<OverviewRoute> { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        contentWindowInsets = rootScaffoldContentInsets(),
        bottomBar = {
            if (isTopLevel) {
                KeenBottomIsland(selected = selected, destinations = overviewState.destinations, onSelect = { target ->
                    val route: Any = when (target) {
                        TopLevelDestination.OVERVIEW -> OverviewRoute
                        TopLevelDestination.CONNECTIONS -> ConnectionsRoute
                        TopLevelDestination.ROUTES -> RoutesRoute
                        TopLevelDestination.ACCESS -> AccessRoute
                        TopLevelDestination.SYSTEM -> SystemRoute
                    }
                    nav.navigate(route) {
                        popUpTo<OverviewRoute> { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { rootPadding ->
        Box(Modifier.padding(rootPadding)) {
            NavHost(navController = nav, startDestination = OverviewRoute) {
                composable<OverviewRoute> {
                    OverviewScreen(
                        state = overviewState,
                        onRefresh = { overviewViewModel.refresh() },
                        onSelectProfile = { overviewViewModel.selectProfile(it) },
                        onOpenSystem = { nav.navigate(SystemRoute) },
                        onSetup = { nav.navigate(SetupRoute) },
                    )
                }
                composable<AccessRoute> {
                    PeerListScreen(
                        onSettings = { nav.navigate(SystemRoute) },
                        onAdd = { nav.navigate(AddPeerRoute) },
                        onPeer = { publicKey -> nav.navigate(PeerDetailRoute(publicKey)) },
                    )
                }
                composable<AddPeerRoute> {
                    AddPeerScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                }
                composable<SystemRoute> {
                    SystemScreen(
                        state = overviewState,
                        onSetup = { nav.navigate(SetupRoute) },
                        onTrustedDevices = { nav.navigate(DevicesRoute) },
                        onDiagnostics = { nav.navigate(SupportRoute) },
                        onBackup = { nav.navigate(BackupRoute) },
                        onAdvancedSettings = { nav.navigate(AdvancedSettingsRoute) },
                    )
                }
                composable<AdvancedSettingsRoute> {
                    SettingsScreen(
                        productState = overviewState,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<SupportRoute> {
                    SupportScreen(
                        onBack = { nav.popBackStack() },
                        onSetupCompanion = { nav.navigate(SetupRoute) },
                    )
                }
                composable<BackupRoute> { BackupScreen(onBack = { nav.popBackStack() }) }
                composable<DevicesRoute> {
                    DevicesScreen(onBack = { nav.popBackStack() })
                }
                composable<SetupRoute> {
                    SetupScreen(
                        onBack = { nav.popBackStack() },
                        onCompleted = {
                            overviewViewModel.refresh()
                            nav.navigate(OverviewRoute) {
                                popUpTo<OverviewRoute> { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable<ConnectionsRoute> {
                    val hasCatalog = overviewState.capabilities?.capabilities.orEmpty().any { it.id == "connections.catalog" && it.available }
                    if (hasCatalog) ConnectionsScreen(onSettings = { nav.navigate(SystemRoute) })
                    else XkeenScreen(onSetupCompanion = { nav.navigate(SetupRoute) })
                }
                composable<RoutesRoute> { NetworkScreen() }
                composable<PeerDetailRoute> { routeEntry ->
                    val route = routeEntry.toRoute<PeerDetailRoute>()
                    PeerDetailScreen(
                        pub = route.publicKey,
                        onBack = { nav.popBackStack() },
                        onNavigateToPeer = { newPublicKey ->
                            nav.popBackStack()
                            nav.navigate(PeerDetailRoute(newPublicKey)) { launchSingleTop = true }
                        },
                    )
                }
            }
        }
    }
}
