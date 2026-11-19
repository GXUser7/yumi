package com.mydrop.vpn.ui.screens.remote

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.mydrop.vpn.core.format.formatDuration
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.RemoteBond
import com.mydrop.vpn.remote.RemoteSettingsPatch
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteTunnelState
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.PillNavigationBar
import com.mydrop.vpn.ui.components.PixelPlanet
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.components.TrafficWaves
import com.mydrop.vpn.ui.format.formatRate
import com.mydrop.vpn.ui.screens.servers.PingAllButtonContent
import com.mydrop.vpn.ui.screens.servers.CountryFilterMode
import com.mydrop.vpn.ui.screens.servers.ServerFilterState
import com.mydrop.vpn.ui.screens.servers.ServerSort
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle
import com.mydrop.vpn.ui.theme.scheme
import com.mydrop.vpn.ui.theme.toSemanticColors
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RemoteTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Remote(R.string.remote_title, Icons.Rounded.SettingsRemote),
    Servers(R.string.nav_servers, Icons.Rounded.Dns),
    Subscriptions(R.string.nav_subscriptions, Icons.Rounded.Cloud),
    Settings(R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
fun RemoteScreen(
    state: RemoteConsoleState,
    phoneSubscriptions: List<Subscription>,
    darkTheme: Boolean,
    serverFilter: ServerFilterState,
    collapsedGroupIds: Set<String>,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPairCode: (String) -> Unit,
    onSwitchTv: (String) -> Unit,
    onReconnect: () -> Unit,
    onLookForTvs: () -> Unit,
    onRenameTv: (String, String) -> Unit,
    onForgetTv: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectNode: (String) -> Unit,
    onPingNode: (String) -> Unit,
    onPingNodes: (List<String>) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (ServerSort) -> Unit,
    onToggleProtocol: (Protocol) -> Unit,
    onClearProtocols: () -> Unit,
    onToggleCountry: (String) -> Unit,
    onCountryModeChange: (CountryFilterMode) -> Unit,
    onClearCountries: () -> Unit,
    onResetFilters: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onAddSubscription: (url: String, name: String?) -> Unit,
    onRefreshSubscription: (String) -> Unit,
    onRemoveSubscription: (String) -> Unit,
    onToggleSubscription: (String, Boolean) -> Unit,
    onUpdateSettings: (RemoteSettingsPatch) -> Unit,
    onNotifyMessage: (Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var activeTab by rememberSaveable { mutableStateOf(RemoteTab.Remote) }
    var showAddTvSheet by rememberSaveable { mutableStateOf(false) }
    var showAddSubSheet by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteBond?>(null) }
    var forgetTarget by remember { mutableStateOf<RemoteBond?>(null) }

    val topInsetPadding = PaddingValues(top = contentPadding.calculateTopPadding())

    if (state.bonds.isEmpty()) {
        Column(modifier.fillMaxSize().padding(contentPadding)) {
            ScreenHeader(
                title = stringResource(R.string.remote_title),
                subtitle = stringResource(R.string.remote_not_linked),
                actions = {
                    TonalIconButton(Icons.Rounded.Close, stringResource(R.string.action_back), onBack)
                },
            )
            NotLinked(
                state = state,
                onScan = onScan,
                onOpenAddSheet = { showAddTvSheet = true },
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            )
        }
    } else {
        val online = state.link is RemoteLinkState.Online
        val activeSnapshot = state.snapshot
        val pinging = activeSnapshot?.pingingNodeIds?.isNotEmpty() == true
        val pingTargetIds = remember(state.nodes, state.subscriptions, serverFilter, collapsedGroupIds) {
            filteredRemoteNodeIdsForPing(
                nodes = state.nodes,
                subscriptions = state.subscriptions,
                filter = serverFilter,
                collapsedGroupIds = collapsedGroupIds,
            )
        }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                PillNavigationBar {
                    RemoteTab.entries.forEach { tab ->
                        val label = stringResource(tab.labelRes)
                        ShortNavigationBarItem(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            icon = { Icon(tab.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                when (activeTab) {
                    RemoteTab.Servers -> if (online && state.nodes.isNotEmpty()) {
                        FloatingActionButton(
                            onClick = {
                                if (pingTargetIds.isNotEmpty()) onPingNodes(pingTargetIds)
                            },
                        ) {
                            PingAllButtonContent(isBusy = pinging)
                        }
                    }

                    RemoteTab.Subscriptions -> if (online && activeSnapshot?.settings != null) {
                        ExtendedFloatingActionButton(
                            onClick = { showAddSubSheet = true },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text(stringResource(R.string.action_add)) },
                        )
                    }

                    else -> Unit
                }
            },
        ) { innerPadding ->
            val tabContentPadding = PaddingValues(
                top = topInsetPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            )
            when (activeTab) {
                RemoteTab.Remote -> RemoteHomeTab(
                    state = state,
                    darkTheme = darkTheme,
                    onBack = onBack,
                    onScan = onScan,
                    onSwitchTv = onSwitchTv,
                    onReconnect = onReconnect,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenServers = { activeTab = RemoteTab.Servers },
                    onOpenAddSheet = { showAddTvSheet = true },
                    onCycleRouting = { next ->
                        onUpdateSettings(RemoteSettingsPatch(routingMode = next))
                        onNotifyMessage(R.string.remote_settings_updated)
                    },
                    contentPadding = tabContentPadding,
                )

                RemoteTab.Servers -> RemoteServersTab(
                    state = state,
                    filter = serverFilter,
                    collapsedGroupIds = collapsedGroupIds,
                    onBack = onBack,
                    onSelectNode = onSelectNode,
                    onPingNode = onPingNode,
                    onQueryChange = onQueryChange,
                    onSortChange = onSortChange,
                    onToggleProtocol = onToggleProtocol,
                    onClearProtocols = onClearProtocols,
                    onToggleCountry = onToggleCountry,
                    onCountryModeChange = onCountryModeChange,
                    onClearCountries = onClearCountries,
                    onResetFilters = onResetFilters,
                    onToggleGroup = onToggleGroup,
                    contentPadding = tabContentPadding,
                )

                RemoteTab.Subscriptions -> RemoteSubscriptionsTab(
                    state = state,
                    onBack = onBack,
                    onRefresh = onRefreshSubscription,
                    onRemove = onRemoveSubscription,
                    onSetEnabled = onToggleSubscription,
                    contentPadding = tabContentPadding,
                )

                RemoteTab.Settings -> RemoteSettingsTab(
                    state = state,
                    darkTheme = darkTheme,
                    onBack = onBack,
                    onReconnect = onReconnect,
                    onOpenRename = { renameTarget = state.active },
                    onOpenForget = { forgetTarget = state.active },
                    onPatchSettings = { patch ->
                        onUpdateSettings(patch)
                        onNotifyMessage(R.string.remote_settings_updated)
                    },
                    contentPadding = tabContentPadding,
                )
            }
        }
    }

    if (showAddTvSheet) {
        AddTvBottomSheet(
            state = state,
            onDismiss = { showAddTvSheet = false },
            onLookForTvs = onLookForTvs,
            onScan = {
                showAddTvSheet = false
                onScan()
            },
            onPairCode = { raw ->
                onPairCode(raw)
                showAddTvSheet = false
            },
        )
    }

    if (showAddSubSheet) {
        RemoteAddSubscriptionSheet(
            phoneSubscriptions = phoneSubscriptions,
            onDismiss = { showAddSubSheet = false },
            onAdd = { url, name ->
                onAddSubscription(url, name)
                onNotifyMessage(R.string.remote_sub_added)
            },
            onInvalidUrl = { onNotifyMessage(R.string.remote_sub_url_invalid) },
        )
    }

    renameTarget?.let { target ->
        RenameTvDialog(
            bond = target,
            onDismiss = { renameTarget = null },
            onSave = { alias ->
                onRenameTv(target.hostId, alias)
                renameTarget = null
            },
        )
    }

    forgetTarget?.let { target ->
        UnlinkTvDialog(
            bond = target,
            onDismiss = { forgetTarget = null },
            onConfirm = {
                onForgetTv(target.hostId)
                forgetTarget = null
            },
        )
    }
}

/* ── Variant A2: TV Carousel Home ────────────────────────────────────────────────────────── */

@Composable
private fun RemoteHomeTab(
    state: RemoteConsoleState,
    darkTheme: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onSwitchTv: (String) -> Unit,
    onReconnect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenAddSheet: () -> Unit,
    onCycleRouting: (RoutingMode) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val worldMask = remember(context) {
        runCatching {
            context.resources.openRawResource(R.raw.world_land).use { it.readBytes() }
        }.getOrElse { ByteArray(0) }
    }

    val bonds = state.bonds
    val initialIndex = remember {
        bonds.indexOfFirst { it.hostId == state.active?.hostId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { bonds.size + 1 }

    val latestBonds by rememberUpdatedState(bonds)
    val latestActiveId by rememberUpdatedState(state.active?.hostId)

    LaunchedEffect(pagerState.settledPage, bonds.size) {
        val page = pagerState.settledPage
        val target = latestBonds.getOrNull(page) ?: return@LaunchedEffect
        if (target.hostId != latestActiveId) {
            onSwitchTv(target.hostId)
        }
    }

    LaunchedEffect(state.active?.hostId, bonds.size) {
        val activeId = state.active?.hostId ?: return@LaunchedEffect
        val idx = latestBonds.indexOfFirst { it.hostId == activeId }
        if (idx >= 0 && idx != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(idx)
        }
    }

    val protectedCount = remember(bonds, state.active?.hostId, state.snapshot, state.link, state.cachedSnapshots) {
        bonds.count { bond ->
            val snap = if (bond.hostId == state.active?.hostId) {
                if (state.link is RemoteLinkState.Online) state.snapshot else null
            } else {
                state.cachedSnapshots[bond.hostId]
            }
            snap?.state == RemoteTunnelState.On
        }
    }

    val summary = if (protectedCount > 0) {
        stringResource(R.string.remote_tv_count_protected, bonds.size, protectedCount)
    } else {
        stringResource(R.string.remote_tv_count_only, bonds.size)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        ScreenHeader(
            title = stringResource(R.string.remote_title),
            subtitle = summary,
            actions = {
                TonalIconButton(Icons.Rounded.Add, stringResource(R.string.remote_add_tv), onOpenAddSheet)
                Spacer(Modifier.width(8.dp))
                TonalIconButton(Icons.Rounded.Close, stringResource(R.string.action_back), onBack)
            },
        )

        Spacer(Modifier.height(10.dp))

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val fraction = 1f - pageOffset.coerceIn(0f, 1f)
            val cardModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = lerp(0.91f, 1f, fraction)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(0.60f, 1f, fraction)
                }

            if (page < bonds.size) {
                val bond = bonds[page]
                val isActiveCard = bond.hostId == state.active?.hostId
                val cardSnapshot = if (isActiveCard) state.snapshot else state.cachedSnapshots[bond.hostId]
                val cardNodes = if (isActiveCard) state.nodes else state.cachedNodes[bond.hostId].orEmpty()
                val cardLink = if (isActiveCard) state.link else RemoteLinkState.Connecting

                TvCarouselCard(
                    bond = bond,
                    snapshot = cardSnapshot,
                    nodes = cardNodes,
                    link = cardLink,
                    isActiveCard = isActiveCard,
                    isCentered = pageOffset < 0.35f,
                    darkTheme = darkTheme,
                    worldMask = worldMask,
                    onClickCard = {
                        if (!isActiveCard) {
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                    },
                    onOpenServers = {
                        if (!isActiveCard) onSwitchTv(bond.hostId)
                        onOpenServers()
                    },
                    modifier = cardModifier,
                )
            } else {
                val newSightings = state.sightings.count { s -> bonds.none { it.hostId == s.hostId } }
                AddTvCarouselCard(
                    nearbyCount = newSightings,
                    onClick = onOpenAddSheet,
                    modifier = cardModifier,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        CarouselDots(
            count = bonds.size + 1,
            selectedIndex = pagerState.currentPage,
            onSelect = { targetPage ->
                scope.launch { pagerState.animateScrollToPage(targetPage) }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(10.dp))

        val showingBond = pagerState.currentPage < bonds.size
        if (showingBond) {
            val activeBond = bonds.getOrNull(pagerState.currentPage) ?: state.active
            val cardMatchesActive = activeBond?.hostId == state.active?.hostId
            val pillLinkState = if (cardMatchesActive) state.link else RemoteLinkState.Connecting
            val pillSnapshot = if (cardMatchesActive) {
                state.snapshot
            } else {
                activeBond?.hostId?.let(state.cachedSnapshots::get)
            }
            val pillNodes = if (cardMatchesActive) {
                state.nodes
            } else {
                activeBond?.hostId?.let(state.cachedNodes::get).orEmpty()
            }
            val activeSelectedNode = remember(pillNodes, pillSnapshot?.selectedNodeId) {
                pillSnapshot?.selectedNodeId?.let { id -> pillNodes.firstOrNull { it.id == id } }
            }
            val isOnline = pillLinkState is RemoteLinkState.Online
            val currentRouting = pillSnapshot?.settings?.routingMode ?: RoutingMode.Rules
            val canCycleRouting = isOnline && pillSnapshot?.settings != null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = {
                            activeBond?.let { if (!cardMatchesActive) onSwitchTv(it.hostId) }
                            onOpenServers()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = activeSelectedNode?.name ?: stringResource(R.string.remote_pick_server),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    QuickRoutingButton(
                        mode = currentRouting,
                        enabled = canCycleRouting,
                        onClick = { onCycleRouting(currentRouting.nextMode()) },
                    )
                }

                RemoteControlPill(
                    tunnelState = pillSnapshot?.state ?: RemoteTunnelState.Off,
                    linkState = pillLinkState,
                    tvName = activeBond?.displayName.orEmpty(),
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                Button(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth().height(ControlHeight),
                    shape = RoundedCornerShape(ControlHeight / 2),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.remote_scan),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvCarouselCard(
    bond: RemoteBond,
    snapshot: RemoteSnapshot?,
    nodes: List<com.mydrop.vpn.remote.RemoteNode>,
    link: RemoteLinkState,
    isActiveCard: Boolean,
    isCentered: Boolean,
    darkTheme: Boolean,
    worldMask: ByteArray,
    onClickCard: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tvPalette = snapshot?.settings?.palette ?: bond.lastPalette
    val tvVisualizer = snapshot?.settings?.visualizer ?: bond.lastVisualizer
    val tvScheme = remember(tvPalette, darkTheme) { tvPalette.scheme(darkTheme) }
    val tvSemantic = remember(tvScheme, darkTheme) { tvScheme.toSemanticColors(darkTheme) }

    val online = isActiveCard && link is RemoteLinkState.Online
    val offline = isActiveCard && link is RemoteLinkState.Offline
    val running = snapshot?.state == RemoteTunnelState.On
    val warming = snapshot?.state == RemoteTunnelState.Warming

    val containerColor by animateColorAsState(
        targetValue = if (running && !offline) {
            tvScheme.surfaceContainerLow
        } else {
            tvScheme.surfaceContainerLowest
        },
        label = "remote-card-container",
    )

    val selectedNode = remember(nodes, snapshot?.selectedNodeId) {
        snapshot?.selectedNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, online) {
        while (running && online) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    MaterialTheme(colorScheme = tvScheme) {
        CompositionLocalProvider(LocalSemanticColors provides tvSemantic) {
            Surface(
                onClick = onClickCard,
                modifier = modifier.alpha(if (offline) 0.72f else 1f),
                shape = RoundedCornerShape(34.dp),
                color = containerColor,
                contentColor = tvScheme.onSurface,
                border = if (running && !offline) null else BorderStroke(1.dp, tvScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Only one PixelPlanet may live on screen because PixelPlanet drives a singleton
                    // camera; peeking cards fall back to subtle waves so cameras never fight.
                    if (tvVisualizer == Visualizer.Planet && isActiveCard && isCentered && worldMask.isNotEmpty()) {
                        val place = remember(selectedNode?.name) {
                            selectedNode?.name
                                ?.let { WorldMap.countryCodeOf(it) }
                                ?.let { WorldMap.centroidOf(it) }
                        }
                        PixelPlanet(
                            mask = worldMask,
                            latitude = place?.get(0),
                            longitude = place?.get(1),
                            connected = running && online,
                            warming = warming && online,
                            seaColor = tvScheme.primaryContainer,
                            landColor = tvScheme.primary,
                            gapColor = containerColor,
                            markerColor = tvSemantic.marker,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        TrafficWaves(
                            downloadBytesPerSecond = if (running && online) snapshot?.downloadBytesPerSecond ?: 0L else 0L,
                            uploadBytesPerSecond = if (running && online) snapshot?.uploadBytesPerSecond ?: 0L else 0L,
                            downloadColor = tvSemantic.download,
                            uploadColor = tvSemantic.upload,
                            warming = warming && online,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Soft vertical scrims keep top title & bottom traffic/timer readable over bright planet cells.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.30f)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(containerColor.copy(alpha = 0.92f), Color.Transparent),
                                ),
                            ),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.24f)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, containerColor.copy(alpha = 0.92f)),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = bond.displayName,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = if (bond.alias.isNotBlank()) {
                                    bond.hostName
                                } else {
                                    bond.lastHost
                                }
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tvScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            RemoteLatencyChipBadge(
                                latencyMillis = selectedNode?.latencyMillis,
                                latencyFailed = selectedNode?.latencyFailed == true,
                                onClick = onOpenServers,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Box(Modifier.weight(1f)) {
                                val statusMessage = snapshot?.message?.takeIf { it.isNotBlank() }
                                when {
                                    running && online -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                text = "↓ ${formatRate(snapshot?.downloadBytesPerSecond ?: 0L)}",
                                                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                                                color = tvSemantic.download,
                                            )
                                            Text(
                                                text = "↑ ${formatRate(snapshot?.uploadBytesPerSecond ?: 0L)}",
                                                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                                                color = tvSemantic.upload,
                                            )
                                        }
                                    }

                                    snapshot?.needsConsentOnTv == true -> {
                                        Text(
                                            text = stringResource(R.string.remote_needs_consent),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tvScheme.error,
                                            maxLines = 2,
                                        )
                                    }

                                    statusMessage != null -> {
                                        Text(
                                            text = statusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tvScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    else -> {
                                        Text(
                                            text = stringResource(
                                                when {
                                                    offline -> R.string.remote_offline
                                                    warming -> R.string.connect_stream_warming
                                                    else -> R.string.connect_stream_empty
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = tvScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }

                            val connectedAt = snapshot?.connectedAtEpochMillis ?: 0L
                            if (running && online && connectedAt > 0L) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = formatDuration(now - connectedAt),
                                    style = MonoStyle.copy(
                                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = tvSemantic.connected,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteLatencyChipBadge(
    latencyMillis: Int?,
    latencyFailed: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val text = when {
        latencyFailed -> "✕"
        latencyMillis != null -> latencyMillis.toString()
        else -> "—"
    }
    val tint = when {
        latencyFailed -> semantic.latencySlow
        latencyMillis == null -> semantic.latencyDead
        latencyMillis < 120 -> semantic.latencyFast
        latencyMillis < 300 -> semantic.latencyMedium
        else -> semantic.latencySlow
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MonoStyle.copy(fontWeight = FontWeight.Bold),
                color = tint,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.unit_millis).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddTvCarouselCard(
    nearbyCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.remote_add_tv),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (nearbyCount > 0) {
                    stringResource(R.string.remote_add_tv_nearby, nearbyCount)
                } else {
                    stringResource(R.string.remote_add_tv_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CarouselDots(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { idx ->
            val selected = idx == selectedIndex
            val width by animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "carousel-dot-width",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                label = "carousel-dot-color",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(idx) },
            )
        }
    }
}

/* ── Bottom Controls ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun RemoteControlPill(
    tunnelState: RemoteTunnelState,
    linkState: RemoteLinkState,
    tvName: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val offline = linkState is RemoteLinkState.Offline
    val online = linkState is RemoteLinkState.Online
    val running = online && tunnelState == RemoteTunnelState.On
    val linkBusy = linkState is RemoteLinkState.Connecting || linkState is RemoteLinkState.Searching
    val tunnelBusy = online && (tunnelState == RemoteTunnelState.Warming || tunnelState == RemoteTunnelState.Stopping)
    val busy = linkBusy || tunnelBusy

    val container by animateColorAsState(
        when {
            offline -> MaterialTheme.colorScheme.surfaceContainerHigh
            busy -> MaterialTheme.colorScheme.primaryContainer
            running -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.primary
        },
        label = "remote-control-container",
    )
    val content by animateColorAsState(
        when {
            offline -> MaterialTheme.colorScheme.onSurface
            running || busy -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        label = "remote-control-content",
    )
    val corner by animateDpAsState(
        targetValue = if (running || offline) 34.dp else ControlHeight / 2,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "remote-control-corner",
    )

    Button(
        onClick = {
            when {
                offline -> onReconnect()
                running -> onDisconnect()
                online -> onConnect()
            }
        },
        enabled = !busy && (online || offline),
        modifier = modifier.fillMaxWidth().height(ControlHeight),
        shape = RoundedCornerShape(corner),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.primary,
        ),
        border = if (running) BorderStroke(1.5.dp, semantic.connected) else null,
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        when {
            offline -> Icon(Icons.Rounded.Refresh, null, Modifier.size(26.dp))
            busy -> ShapeSpinner(color = content, size = 28.dp)
            running -> Icon(Icons.Rounded.Pause, null, Modifier.size(30.dp))
            tunnelState == RemoteTunnelState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(26.dp))
            else -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                offline -> stringResource(R.string.remote_find_tv, tvName)
                running -> stringResource(R.string.connect_button_disconnect)
                tunnelState == RemoteTunnelState.Stopping -> stringResource(R.string.connect_button_disconnecting)
                busy -> stringResource(R.string.connect_button_connecting)
                else -> stringResource(R.string.connect_button_connect)
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickRoutingButton(
    mode: RoutingMode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(mode.labelRes),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ControlHeight = 116.dp

private fun RoutingMode.nextMode(): RoutingMode = when (this) {
    RoutingMode.Global -> RoutingMode.Rules
    RoutingMode.Rules -> RoutingMode.Direct
    RoutingMode.Direct -> RoutingMode.Global
}

/* ── Shared Link Banner & Empty State ────────────────────────────────────────────────────── */

@Composable
internal fun LinkBanner(link: RemoteLinkState, modifier: Modifier = Modifier) {
    val label = when (link) {
        RemoteLinkState.Searching -> stringResource(R.string.remote_searching)
        RemoteLinkState.Connecting -> stringResource(R.string.remote_connecting)
        is RemoteLinkState.Offline -> stringResource(R.string.remote_offline)
        else -> null
    }
    AnimatedVisibility(visible = label != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeSpinner(MaterialTheme.colorScheme.primary, size = 18.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotLinked(
    state: RemoteConsoleState,
    onScan: () -> Unit,
    onOpenAddSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = state.sightings.firstOrNull()?.let { stringResource(R.string.remote_found, it.hostName) }
                ?: stringResource(R.string.remote_not_linked),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.remote_not_linked_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(27.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.remote_scan))
        }
        OutlinedButton(onClick = onOpenAddSheet) {
            Text(stringResource(R.string.remote_manual_code_label))
        }
    }
}

/* ── Add / Rename / Unlink Modals ────────────────────────────────────────────────────────── */

@Composable
private fun AddTvBottomSheet(
    state: RemoteConsoleState,
    onDismiss: () -> Unit,
    onLookForTvs: () -> Unit,
    onScan: () -> Unit,
    onPairCode: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var manualCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onLookForTvs() }

    val bondedIds = remember(state.bonds) { state.bonds.mapTo(HashSet()) { it.hostId } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.remote_add_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onLookForTvs) {
                    Text(stringResource(R.string.remote_search_again))
                }
            }

            Text(
                text = stringResource(R.string.remote_nearby_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            if (state.sightings.isEmpty()) {
                Text(
                    text = stringResource(R.string.remote_nearby_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sightings.forEach { sighting ->
                        val linked = sighting.hostId in bondedIds
                        Surface(
                            onClick = { if (!linked) onScan() },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = if (linked) Icons.Rounded.CheckCircle else Icons.Rounded.Tv,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = sighting.hostName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${sighting.host}:${sighting.port}",
                                        style = MonoStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (linked) {
                                    Text(
                                        text = stringResource(R.string.remote_nearby_linked),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(27.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.remote_scan))
            }

            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.remote_manual_code_label)) },
                placeholder = { Text(stringResource(R.string.remote_manual_code_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { manualCode = it.trim() } },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_paste))
                }
                Button(
                    onClick = { onPairCode(manualCode) },
                    enabled = manualCode.isNotBlank() && !state.pairing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.remote_connect_code))
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RenameTvDialog(
    bond: RemoteBond,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(bond.hostId) { mutableStateOf(bond.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(R.string.remote_tv_alias_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = value.trim()
                    onSave(if (clean == bond.hostName) "" else clean)
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun UnlinkTvDialog(
    bond: RemoteBond,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_forget_confirm_title, bond.displayName)) },
        text = { Text(stringResource(R.string.remote_forget_confirm_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
