package com.mydrop.vpn.ui

import androidx.annotation.StringRes
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ImportConfirmDialog
import com.mydrop.vpn.ui.components.PairingSendDialog
import com.mydrop.vpn.ui.components.PillActionButton
import com.mydrop.vpn.ui.components.PillNavigationBar
import com.mydrop.vpn.ui.components.PillNavigationItem
import com.mydrop.vpn.ui.components.ShapesBackdrop
import com.mydrop.vpn.ui.components.pillIconSize
import com.mydrop.vpn.ui.screens.apps.SplitTunnelScreen
import com.mydrop.vpn.ui.screens.connect.ConnectScreen
import com.mydrop.vpn.ui.screens.failover.NodePickerKind
import com.mydrop.vpn.ui.screens.failover.NodePickerScreen
import com.mydrop.vpn.ui.screens.logs.LogsScreen
import com.mydrop.vpn.ui.screens.remote.RemoteScreen
import com.mydrop.vpn.ui.screens.scan.ScanScreen
import com.mydrop.vpn.ui.screens.servers.PingAllButtonContent
import com.mydrop.vpn.ui.screens.servers.ServersScreen
import com.mydrop.vpn.ui.screens.settings.SettingsScreen
import com.mydrop.vpn.ui.screens.speed.SpeedTestScreen
import com.mydrop.vpn.ui.screens.subscriptions.AddSubscriptionSheet
import com.mydrop.vpn.ui.screens.subscriptions.SubscriptionsScreen
import com.mydrop.vpn.ui.theme.frostSource
import com.mydrop.vpn.ui.theme.rememberFrostState
import kotlinx.coroutines.launch

object Routes {
    /**
     * The four tabs, as one destination: they are pages of a pager, swiped between, rather than
     * four destinations the navigation pill jumps between. A route per tab could only ever be
     * switched by a tap; pages follow the finger.
     */
    const val TABS = "tabs"
    const val LOGS = "logs"
    const val SPEED = "speed"
    const val SPLIT_TUNNEL = "split_tunnel"
    const val SCAN = "scan"
    const val FAILOVER = "failover"
    const val MOBILE_NODES = "mobile-nodes"
    const val REMOTE = "remote"
}

/** The tabs, in the order they are swiped through. */
private enum class TopLevel(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Connect(R.string.nav_tunnel, Icons.Rounded.Shield),
    Servers(R.string.nav_servers, Icons.Rounded.Dns),
    Subscriptions(R.string.nav_subscriptions, Icons.Rounded.Cloud),
    Settings(R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
fun MyDropApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val speedTest by viewModel.speedTest.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val geoAssets by viewModel.geoAssets.collectAsStateWithLifecycle()
    val ipv6 by viewModel.ipv6.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    val remote by viewModel.remoteState.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val pairingInvite by viewModel.pairingInvite.collectAsStateWithLifecycle()
    val pairingSending by viewModel.pairingSending.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.TABS
    val showNavigationPill = currentRoute == Routes.TABS

    val pager = rememberPagerState { TopLevel.entries.size }
    val scope = rememberCoroutineScope()
    val currentTab = TopLevel.entries[pager.currentPage]
    val openTab: (TopLevel) -> Unit = { tab -> scope.launch { pager.animateScrollToPage(tab.ordinal) } }

    // Back from any tab but the first goes to the first, as it did when a tab switch popped the
    // stack to the start destination; only from there does it leave the app.
    BackHandler(enabled = showNavigationPill && pager.currentPage != 0) { openTab(TopLevel.Connect) }

    // What the navigation pill blurs: the whole screen under it, backdrop and all. Nothing at all
    // with the transparency effects off — then the pill is solid, and recording the screen for a
    // blur nobody draws would be the cost without the effect.
    val frostState = rememberFrostState()
    val frost = frostState.takeIf { state.settings.glassEffects }
    val remoteAvailable = remote.bonds.isNotEmpty() || remote.sightings.isNotEmpty()

    // No app bars anywhere: every screen opens with its own poster headline in the body, which is
    // both the visual signature and the end of the empty-collapsed-bar problem.
    Scaffold(
        // Transparent, because the ground is the backdrop drawn under the screens below and the
        // Scaffold's own flat surface would cover it.
        containerColor = Color.Transparent,
        // Named, because it cannot be derived. The Scaffold picks its content colour from its
        // container, and for a transparent container that is whatever colour is already in force —
        // which at the root of the app is none, so black. Every headline that did not name a
        // colour of its own came out black on the dark ground.
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            // The pill floats, so it animates in and out vertically rather than just fading.
            AnimatedVisibility(
                visible = showNavigationPill,
                enter = slideInVertically(tween(240)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(120)),
            ) {
                // Each tab's own actions, beside the pill: the tunnel's speed test and remote, the
                // list's measure-everything, the subscriptions' add.
                val speedTest: @Composable () -> Unit = {
                    PillActionButton(
                        onClick = { navController.navigate(Routes.SPEED) },
                        contentDescription = stringResource(R.string.connect_speed_test),
                        frost = frost,
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(pillIconSize(26.dp)))
                    }
                }
                val openRemote: @Composable () -> Unit = {
                    PillActionButton(
                        onClick = { navController.navigate(Routes.REMOTE) },
                        contentDescription = stringResource(R.string.remote_open),
                        frost = frost,
                    ) {
                        Icon(Icons.Rounded.SettingsRemote, contentDescription = null, modifier = Modifier.size(pillIconSize(26.dp)))
                    }
                }
                val pingAll: @Composable () -> Unit = {
                    PillActionButton(
                        onClick = viewModel::pingAll,
                        contentDescription = stringResource(R.string.servers_ping_all),
                        frost = frost,
                    ) {
                        PingAllButtonContent(isBusy = state.pingingNodeIds.isNotEmpty())
                    }
                }
                val addSubscription: @Composable () -> Unit = {
                    PillActionButton(
                        onClick = { showAddSheet = true },
                        contentDescription = stringResource(R.string.action_add),
                        frost = frost,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(pillIconSize(28.dp)))
                    }
                }
                val actions: List<@Composable () -> Unit> = when (currentTab) {
                    // The remote only while there is a television to drive — one linked, or one
                    // heard on this network. On a phone that has never been in the same house as a
                    // Yumi television it could only ever say "nothing found".
                    TopLevel.Connect -> listOfNotNull(openRemote.takeIf { remoteAvailable }, speedTest)
                    TopLevel.Servers -> listOf(pingAll)
                    TopLevel.Subscriptions -> listOf(addSubscription)
                    TopLevel.Settings -> emptyList()
                }
                PillNavigationBar(
                    itemCount = TopLevel.entries.size,
                    frost = frost,
                    actionCount = actions.size,
                    actions = { actions.forEach { it() } },
                ) {
                    TopLevel.entries.forEach { tab ->
                        PillNavigationItem(
                            selected = currentTab == tab,
                            onClick = { openTab(tab) },
                            icon = tab.icon,
                            label = stringResource(tab.labelRes),
                        )
                    }
                }
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

        // The backdrop sits inside the frost source rather than under the Scaffold, so the pill's
        // blur carries the light of the room and not only whatever text happens to be under it.
        Box(Modifier.fillMaxSize().then(if (frost != null) Modifier.frostSource(frost) else Modifier)) {
            // Still when "live backdrop" is off: no drift, no accelerometer, drawn once.
            ShapesBackdrop(
                motionEnabled = state.settings.backgroundMotion,
                animated = state.settings.backgroundMotion,
            )

            NavHost(
                navController = navController,
                startDestination = Routes.TABS,
                modifier = Modifier.fillMaxSize(),
                // All four transitions read the tab order rather than the back stack — see
                // [movingForward] for why the stack is the wrong thing to ask.
                enterTransition = { lateralEnter(movingForward()) },
                exitTransition = { lateralExit(movingForward()) },
                popEnterTransition = { lateralEnter(movingForward()) },
                popExitTransition = { lateralExit(movingForward()) },
            ) {
                composable(Routes.TABS) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        key = { TopLevel.entries[it].name },
                    ) { page ->
                        when (TopLevel.entries[page]) {
                            TopLevel.Connect -> {
                                // One broadcast, and only while there is nothing linked yet: a phone
                                // that already knows a television shows the door regardless, and
                                // shouting at the network on every visit would buy nothing.
                                LaunchedEffect(remote.bonds.isEmpty()) {
                                    if (remote.bonds.isEmpty()) viewModel.lookForTelevisions()
                                }
                                ConnectScreen(
                                    state = state,
                                    onToggleConnection = viewModel::toggleConnection,
                                    onPickServer = { openTab(TopLevel.Servers) },
                                    onRoutingModeChange = viewModel::setRoutingMode,
                                    modifier = Modifier.padding(contentPadding),
                                )
                            }

                            TopLevel.Servers -> ServersScreen(
                                state = state,
                                onSelect = viewModel::selectNode,
                                onPing = viewModel::pingNode,
                                onRemove = viewModel::removeNode,
                                onSetTlsInsecure = viewModel::setTlsInsecure,
                                onToggleGroup = viewModel::toggleServerGroup,
                                contentPadding = contentPadding,
                            )

                            TopLevel.Subscriptions -> SubscriptionsScreen(
                                state = state,
                                onRefresh = viewModel::refreshSubscription,
                                onRemove = viewModel::removeSubscription,
                                onSetEnabled = viewModel::setSubscriptionEnabled,
                                contentPadding = contentPadding,
                            )

                            TopLevel.Settings -> SettingsScreen(
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
                                onOpenMobileNodes = { navController.navigate(Routes.MOBILE_NODES) },
                                geoAssets = geoAssets,
                                onRefreshGeo = viewModel::refreshGeoAssets,
                                ipv6 = ipv6,
                                updates = updates,
                                onCheckUpdate = viewModel::checkForUpdate,
                                onDownloadUpdate = viewModel::downloadUpdate,
                                onInstallUpdate = viewModel::installUpdate,
                                onDismissUpdate = viewModel::dismissUpdate,
                                contentPadding = contentPadding,
                            )
                        }
                    }
                }

                composable(Routes.REMOTE) {
                    // Opened with the screen and closed with it. The console itself outlives both,
                    // so what is kept between visits is the bond and whatever was last heard on
                    // the network — not a socket held open behind a screen nobody is looking at.
                    DisposableEffect(Unit) {
                        viewModel.openRemote()
                        onDispose(viewModel::closeRemote)
                    }
                    RemoteScreen(
                        state = remote,
                        onBack = { navController.popBackStack() },
                        onScan = { navController.navigate(Routes.SCAN) },
                        onConnect = { viewModel.sendToRemote(RemoteCommand.Connect) },
                        onDisconnect = { viewModel.sendToRemote(RemoteCommand.Disconnect) },
                        onSelect = { viewModel.sendToRemote(RemoteCommand.Select(it)) },
                        onForget = viewModel::forgetRemote,
                        contentPadding = contentPadding,
                    )
                }

                composable(Routes.SPEED) {
                    SpeedTestScreen(
                        state = speedTest,
                        // Asked on every composition rather than remembered. It is one cheap query
                        // to ConnectivityManager, and `remember` with no key cached the answer for
                        // the life of the screen — so the warning about spending mobile data never
                        // appeared for the one person it exists for: somebody who started on Wi-Fi
                        // and lost it.
                        isMetered = viewModel.speedTestIsMetered(),
                        onStart = viewModel::startSpeedTest,
                        onStop = viewModel::stopSpeedTest,
                        onBack = { navController.popBackStack() },
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

                composable(Routes.MOBILE_NODES) {
                    NodePickerScreen(
                        kind = NodePickerKind.Mobile,
                        settings = state.settings,
                        nodes = state.nodes,
                        latencies = state.latencies,
                        onUpdate = viewModel::updateSettings,
                        onBack = { navController.popBackStack() },
                        contentPadding = contentPadding,
                    )
                }

                composable(Routes.FAILOVER) {
                    NodePickerScreen(
                        kind = NodePickerKind.Failover,
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
    }

    pendingImport?.let { pending ->
        ImportConfirmDialog(
            pending = pending,
            onConfirm = viewModel::confirmPendingImport,
            onDismiss = viewModel::dismissPendingImport,
        )
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

    pairingInvite?.let { invite ->
        PairingSendDialog(
            invite = invite,
            subscriptions = state.subscriptions,
            sending = pairingSending,
            onSend = viewModel::sendSubscriptionToTv,
            onDismiss = viewModel::dismissPairingInvite,
        )
    }
}

/**
 * Which way the screen travelled: a detail screen always enters from the right and leaves back to
 * the right. The tabs are one destination now, so the order between them is the pager's business.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.movingForward(): Boolean =
    depth(targetState.destination.route) >= depth(initialState.destination.route)

private fun depth(route: String?): Int = if (route == Routes.TABS) 0 else 1

private fun lateralEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(280)) { width -> if (forward) width / 6 else -width / 6 } +
        fadeIn(tween(220))

private fun lateralExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(220)) { width -> if (forward) -width / 8 else width / 8 } +
        fadeOut(tween(160))
