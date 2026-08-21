package com.mydrop.vpn.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mydrop.vpn.ui.components.PillNavigationBar
import com.mydrop.vpn.ui.screens.apps.SplitTunnelScreen
import com.mydrop.vpn.ui.screens.connect.ConnectScreen
import com.mydrop.vpn.ui.screens.logs.LogsScreen
import com.mydrop.vpn.ui.screens.servers.PingAllButtonContent
import com.mydrop.vpn.ui.screens.failover.FailoverScreen
import com.mydrop.vpn.ui.screens.scan.ScanScreen
import com.mydrop.vpn.ui.screens.servers.ServersScreen
import com.mydrop.vpn.ui.screens.settings.SettingsScreen
import com.mydrop.vpn.ui.screens.speed.SpeedTestScreen
import com.mydrop.vpn.ui.screens.subscriptions.AddSubscriptionSheet
import com.mydrop.vpn.ui.screens.subscriptions.SubscriptionsScreen

object Routes {
    const val CONNECT = "connect"
    const val SERVERS = "servers"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    const val SPEED = "speed"
    const val SPLIT_TUNNEL = "split_tunnel"
    const val SCAN = "scan"
    const val FAILOVER = "failover"
}

private enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Connect(Routes.CONNECT, "Туннель", Icons.Rounded.Shield),
    Servers(Routes.SERVERS, "Серверы", Icons.Rounded.Dns),
    Subscriptions(Routes.SUBSCRIPTIONS, "Подписки", Icons.Rounded.Cloud),
    Settings(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings),
}

@Composable
fun MyDropApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val speedTest by viewModel.speedTest.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.CONNECT
    val showNavigationPill = TopLevel.entries.any { it.route == currentRoute }

    // No app bars anywhere: every screen opens with its own poster headline in the body, which is
    // both the visual signature and the end of the empty-collapsed-bar problem.
    Scaffold(
        bottomBar = {
            // The pill floats, so it animates in and out vertically rather than just fading.
            AnimatedVisibility(
                visible = showNavigationPill,
                enter = slideInVertically(tween(240)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(120)),
            ) {
                PillNavigationBar {
                    TopLevel.entries.forEach { destination ->
                        ShortNavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (currentRoute) {
                Routes.SERVERS -> FloatingActionButton(onClick = viewModel::pingAll) {
                    PingAllButtonContent(isBusy = state.pingingNodeIds.isNotEmpty())
                }

                Routes.SUBSCRIPTIONS -> ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Добавить") },
                )

                else -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // Screens without the pill take their bottom inset from the system, not from the Scaffold.
        //
        // The pill leaves on an animation, and the Scaffold reports the space it still occupies
        // frame by frame as it goes — so a screen opened on top of it was laid out for a bar that
        // was busy disappearing, and visibly stretched into place a moment later. Reading the
        // system inset instead gives such a screen its final height on its very first frame; the
        // pill slides away over it, which is what it looks like it is doing anyway.
        val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentPadding = if (showNavigationPill) {
            innerPadding
        } else {
            PaddingValues(top = innerPadding.calculateTopPadding(), bottom = systemBottom)
        }

        NavHost(
            navController = navController,
            startDestination = Routes.CONNECT,
            modifier = Modifier.fillMaxSize(),
            // All four transitions read the tab order rather than the back stack — see
            // [movingForward] for why the stack is the wrong thing to ask.
            enterTransition = { lateralEnter(movingForward()) },
            exitTransition = { lateralExit(movingForward()) },
            popEnterTransition = { lateralEnter(movingForward()) },
            popExitTransition = { lateralExit(movingForward()) },
        ) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    state = state,
                    onToggleConnection = viewModel::toggleConnection,
                    onPickServer = { navController.navigateTopLevel(Routes.SERVERS) },
                    onRoutingModeChange = viewModel::setRoutingMode,
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenSpeedTest = { navController.navigate(Routes.SPEED) },
                    modifier = Modifier.padding(contentPadding),
                )
            }

            composable(Routes.SPEED) {
                SpeedTestScreen(
                    state = speedTest,
                    onStart = viewModel::startSpeedTest,
                    onStop = viewModel::stopSpeedTest,
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.SERVERS) {
                ServersScreen(
                    state = state,
                    onSelect = viewModel::selectNode,
                    onPing = viewModel::pingNode,
                    onRemove = viewModel::removeNode,
                    onSetTlsInsecure = viewModel::setTlsInsecure,
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.SUBSCRIPTIONS) {
                SubscriptionsScreen(
                    state = state,
                    onRefresh = viewModel::refreshSubscription,
                    onRemove = viewModel::removeSubscription,
                    onSetEnabled = viewModel::setSubscriptionEnabled,
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = state.settings,
                    splitTunnelAppCount = state.settings.splitTunnelPackages.size,
                    dnsProfiles = state.dnsProfiles,
                    selectedDnsId = state.selectedDnsId,
                    onSelectDns = viewModel::selectDns,
                    onRemoveDns = viewModel::removeDns,
                    onUpdate = viewModel::updateSettings,
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenSplitTunnel = { navController.navigate(Routes.SPLIT_TUNNEL) },
                    onOpenFailover = { navController.navigate(Routes.FAILOVER) },
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.LOGS) {
                LogsScreen(
                    entries = logs,
                    onBack = { navController.popBackStack() },
                    onClear = viewModel::clearLogs,
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.SCAN) {
                ScanScreen(
                    onResult = viewModel::importText,
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }

            composable(Routes.FAILOVER) {
                FailoverScreen(
                    settings = state.settings,
                    nodes = state.nodes,
                    latencies = state.latencies,
                    onUpdate = viewModel::updateSettings,
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }


            composable(Routes.SPLIT_TUNNEL) {
                SplitTunnelScreen(
                    settings = state.settings,
                    onUpdate = viewModel::updateSettings,
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }
        }
    }

    if (showAddSheet) {
        AddSubscriptionSheet(
            onDismiss = { showAddSheet = false },
            onAdd = viewModel::addFromText,
            onScan = {
                showAddSheet = false
                navController.navigate(Routes.SCAN)
            },
        )
    }
}

/**
 * Which way the screen travelled, decided by tab order rather than by the back stack.
 *
 * Navigation picks its push or pop transitions from what happened to the stack, and with
 * `popUpTo(start) { saveState } + restoreState` that has nothing to do with which tab sits left of
 * which: returning to an already-visited tab restores its entry, so going from Подписки back to
 * Серверы ran the push transitions and slid in from the right, as if moving further along.
 *
 * Detail screens are ordered past every tab, so they always enter from the right and leave back
 * to the right.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.movingForward(): Boolean =
    tabOrder(targetState.destination.route) >= tabOrder(initialState.destination.route)

private fun tabOrder(route: String?): Int =
    TopLevel.entries.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: TopLevel.entries.size

private fun lateralEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(280)) { width -> if (forward) width / 6 else -width / 6 } +
        fadeIn(tween(220))

private fun lateralExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(220)) { width -> if (forward) -width / 8 else width / 8 } +
        fadeOut(tween(160))

/** Tab switches replace the tab, they do not stack — otherwise back walks the whole tour. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
