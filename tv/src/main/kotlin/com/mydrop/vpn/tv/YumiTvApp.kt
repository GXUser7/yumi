package com.mydrop.vpn.tv

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrop.vpn.core.format.formatDuration
import com.mydrop.vpn.core.format.scaleBytes
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.pairing.PairingReceiverState
import com.mydrop.vpn.ui.components.PixelPlanet
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.ui.components.TrafficWaves
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle

enum class TvDestination(@StringRes val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Connect(R.string.tv_nav_connect, Icons.Rounded.Shield),
    Servers(R.string.tv_nav_servers, Icons.Rounded.Dns),
    Subscriptions(R.string.tv_nav_subscriptions, Icons.Rounded.Cloud),
    Settings(R.string.tv_nav_settings, Icons.Rounded.Settings),
}

@Composable
fun YumiTvApp(viewModel: TvViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    var destination by rememberSaveable {
        mutableStateOf(
            if (state.subscriptions.isEmpty()) TvDestination.Subscriptions else TvDestination.Connect,
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val navFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = destination != TvDestination.Connect) {
        destination = TvDestination.Connect
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // One bar for the whole app rather than one per screen. Two instances cannot animate
            // into each other — the old arrangement drew a fresh one on every screen, so moving
            // between tabs read as one bar vanishing and another appearing somewhere else. This
            // is the same node throughout, which is what lets it travel.
            val rail = destination != TvDestination.Connect
            val spec = MaterialTheme.motionScheme.slowSpatialSpec<Dp>()
            // The slot is measured from the screen edge while the button it lines up under is
            // measured from inside the screen's own padding, so the two do not share an origin:
            //   button left = ScreenInset + (W - 2*ScreenInset - ColumnGap) / 2 + ColumnGap
            //   pill left   = W - navWidth + ScreenInset
            // Equating the two is where this comes from. Halving the width instead leaves the
            // pill a screen inset narrow, and it reads as a wobble rather than as a column.
            val navWidth by animateDpAsState(
                if (rail) NavRailSlot else (maxWidth + ScreenInset * 2 - ColumnGap) / 2,
                spec,
                label = "nav-width",
            )
            val navHeight by animateDpAsState(
                if (rail) maxHeight else NavPillSlot,
                spec,
                label = "nav-height",
            )
            // The lists have to end where the rail begins. Before this they ran underneath it and
            // bought the clearance with 210dp of bottom padding, which meant the only way to
            // reach the tabs was to scroll a list of two hundred servers to its very end.
            val contentEnd by animateDpAsState(if (rail) NavRailSlot else 0.dp, spec, label = "content-inset")

            // Clamped, all three of them, because the spec above is a spring and a spring
            // goes past where it is heading. Travelling back from the rail to the pill this one
            // passed below zero and took the app down with
            // `IllegalArgumentException: Padding must be non-negative` on the first frame of the
            // overshoot. Widths and heights reject a negative the same way; the same clamp is on
            // the zoom in PixelPlanet, for the same reason.
            Box(Modifier.fillMaxSize().padding(end = contentEnd.coerceAtLeast(0.dp))) {
                when (destination) {
                    TvDestination.Connect -> TvConnectScreen(
                        state = state,
                        navFocus = navFocus,
                        onToggle = viewModel::toggleConnection,
                        onOpenServers = { destination = TvDestination.Servers },
                    )
                    TvDestination.Servers -> TvServersScreen(
                        state = state,
                        onSelect = viewModel::selectNode,
                        onMeasure = viewModel::pingAll,
                    )
                    TvDestination.Subscriptions -> TvSubscriptionsScreen(
                        state = state,
                        pairing = pairing,
                        onStartPairing = viewModel::startPairing,
                        onStopPairing = viewModel::stopPairing,
                        onManualAdd = viewModel::addManualSubscription,
                        onRefresh = viewModel::refreshSubscription,
                        onRemove = viewModel::removeSubscription,
                        onEnabled = viewModel::setSubscriptionEnabled,
                    )
                    TvDestination.Settings -> TvSettingsScreen(
                        state = state,
                        updates = updates,
                        onUpdateSettings = viewModel::updateSettings,
                        onCheckUpdate = viewModel::checkForUpdate,
                        onDownloadUpdate = viewModel::downloadUpdate,
                        onInstallUpdate = { viewModel.installUpdate(it) },
                    )
                }
            }

            TvNavigationBar(
                selected = destination,
                onSelected = { destination = it },
                vertical = rail,
                firstFocusRequester = navFocus,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(navWidth.coerceAtLeast(NavRailSlot))
                    .height(navHeight.coerceAtLeast(NavPillSlot))
                    .padding(ScreenInset),
            )
        }
    }
}

/** The screen's own margin, and the inset that keeps the bar off the edge of a TV panel. */
internal val ScreenInset = 24.dp

/** Gap between the two halves of the tunnel screen; the bar lines up with the right one. */
private val ColumnGap = 20.dp

/** Slot the bar occupies: its height as a pill, its width as a rail. Visible size is this minus
 *  twice [ScreenInset], so a square number here keeps the two forms the same thickness. */
private val NavPillSlot = 152.dp

/**
 * Wider than the pill is tall, and measured rather than chosen: at 152 the longest label,
 * "Подключение", came out of the rail as "Подключен" — clipped, not ellipsised, so it read as a
 * different word rather than as a truncation.
 */
private val NavRailSlot = 176.dp

@Composable
internal fun TvConnectScreen(
    state: TvUiState,
    navFocus: FocusRequester,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectFocus = remember { FocusRequester() }
    val latencyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { connectFocus.requestFocus() }

    // Two columns, and each one fills the screen from top edge to bottom edge — but they are not
    // the same shape, because they do not carry the same things. The headline sits above the
    // figure, so the figure starts lower and runs to the bottom; the tab bar sits under the
    // button, so the button starts at the top and stops above it. What that buys is every edge of
    // the screen being met by something: nothing floats in the middle with air around it.
    Row(
        modifier = modifier.fillMaxSize().padding(ScreenInset),
        horizontalArrangement = Arrangement.spacedBy(ColumnGap),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TvConnectionHeadline(
                state = state,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 18.dp),
            )
            TvFlowFigure(
                state = state,
                onOpenServers = onOpenServers,
                latencyFocus = latencyFocus,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            TvConnectionButton(
                state = state.vpnState,
                onClick = onToggle,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("connection-button")
                    .focusRequester(connectFocus)
                    .focusProperties { down = navFocus; left = latencyFocus },
            )
            // The bar is drawn by YumiTvApp so that it can travel to the other screens; this is
            // the room it takes, plus the gap, measured off its own slot rather than guessed.
            Spacer(Modifier.height(NavPillSlot - ScreenInset * 2 + ColumnGap))
        }
    }
}

@Composable
private fun TvConnectionHeadline(state: TvUiState, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.vpnState) {
        while (state.vpnState is VpnState.Connected) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val title = when (state.vpnState) {
        is VpnState.Connected -> stringResource(R.string.tv_protected)
        is VpnState.Connecting -> stringResource(R.string.tv_connecting)
        VpnState.Disconnecting -> stringResource(R.string.tv_disconnecting)
        is VpnState.Failed -> stringResource(R.string.tv_failed)
        VpnState.Disconnected -> stringResource(R.string.tv_tunnel_off)
    }
    val detail = when (val vpn = state.vpnState) {
        is VpnState.Connected -> listOfNotNull(
            state.selectedNode?.name,
            formatDuration(now - vpn.connectedAtEpochMillis),
        ).joinToString(" · ")
        is VpnState.Failed -> vpn.message
        else -> state.selectedNode?.let { node ->
            val latency = state.latencies[node.id]?.takeUnless { it.failed }?.millis
            if (latency == null) node.name else "${node.name} · $latency ms"
        } ?: stringResource(R.string.tv_no_server)
    }
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            color = if (state.vpnState is VpnState.Connected) semantic.connected
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TvFlowFigure(
    state: TvUiState,
    onOpenServers: () -> Unit,
    latencyFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val connected = state.vpnState is VpnState.Connected
    val warming = state.vpnState is VpnState.Connecting
    val container = if (connected) MaterialTheme.colorScheme.surfaceContainerLow
    else MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(44.dp))
            .background(container),
    ) {
        when (state.settings.visualizer) {
            Visualizer.Waves -> TrafficWaves(
                downloadBytesPerSecond = state.traffic.downloadBytesPerSecond,
                uploadBytesPerSecond = state.traffic.uploadBytesPerSecond,
                downloadColor = semantic.download,
                uploadColor = semantic.upload,
                warming = warming,
            )
            Visualizer.Planet -> {
                val resources = LocalResources.current
                val mask = remember {
                    runCatching {
                        resources.openRawResource(com.mydrop.vpn.shared.R.raw.world_land)
                            .use { it.readBytes() }
                    }.getOrElse { ByteArray(0) }
                }
                val place = remember(state.selectedNode?.name) {
                    state.selectedNode?.name?.let(WorldMap::countryCodeOf)?.let(WorldMap::centroidOf)
                }
                PixelPlanet(
                    mask = mask,
                    latitude = place?.get(0),
                    longitude = place?.get(1),
                    connected = connected,
                    warming = warming,
                    seaColor = MaterialTheme.colorScheme.primaryContainer,
                    landColor = MaterialTheme.colorScheme.primary,
                    gapColor = container,
                    markerColor = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        val latency = state.selectedNode?.let { state.latencies[it.id] }
        TvFocusedSurface(
            onClick = onOpenServers,
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp).size(68.dp)
                .testTag("server-selector")
                .focusRequester(latencyFocus),
            shape = RoundedCornerShape(19.dp),
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(latency?.takeUnless { it.failed }?.millis?.toString() ?: "—", style = MonoStyle)
                Text("MS", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "↓ ${rateText(state.traffic.downloadBytesPerSecond)}    ↑ ${rateText(state.traffic.uploadBytesPerSecond)}",
            modifier = Modifier.align(Alignment.BottomStart).padding(22.dp),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvConnectionButton(state: VpnState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "connection-focus")
    val semantic = LocalSemanticColors.current
    val connected = state is VpnState.Connected
    val busy = state is VpnState.Connecting || state is VpnState.Disconnecting
    val container by animateColorAsState(
        when {
            connected -> MaterialTheme.colorScheme.surface
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "connection-colour",
    )
    // The phone's control animates two colours; this one carried over the container and left the
    // label to `buttonColors`, whose default content colour is `onPrimary`. That is right only
    // while the container is `primary`. Connected the container is `surface` - measured #090B0F
    // on this panel - under a label still painted #07203F: about 1.4:1, and the word on the
    // button simply was not there.
    val content by animateColorAsState(
        when {
            connected || busy -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        label = "connection-content",
    )
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.scale(scale),
        // Off, the button is a stadium: fully round ends, whatever size the column gives it.
        // Running, it squares off to 34 dp. The shape is the state — a round thing to press and a
        // settled slab once it is pressed — so the radius is a percentage rather than a number of
        // dp, and follows the button instead of being chosen for one screen size.
        shape = if (connected) RoundedCornerShape(34.dp) else RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = when {
            connected -> BorderStroke(3.dp, semantic.connected)
            focused -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
        contentPadding = PaddingValues(24.dp),
    ) {
        when (state) {
            is VpnState.Connecting, VpnState.Disconnecting -> ShapeSpinner(
                color = content,
                size = 58.dp,
            )
            is VpnState.Connected -> Icon(Icons.Rounded.Pause, null, Modifier.size(64.dp))
            is VpnState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(64.dp))
            VpnState.Disconnected -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(64.dp))
        }
        Spacer(Modifier.width(20.dp))
        Text(
            stringResource(
                when (state) {
                    is VpnState.Connected -> R.string.tv_disconnect
                    is VpnState.Failed -> R.string.tv_retry
                    VpnState.Disconnected -> R.string.tv_connect
                    else -> R.string.tv_connecting
                },
            ),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The tabs: a pill across the bottom of the tunnel screen, a rail down the right of every other.
 *
 * A stadium at both extremes, so the same rounding reads as a pill lying down and as one standing
 * up, and the travel between them needs no shape animation at all - only the width and the
 * height, which [YumiTvApp] animates.
 */
@Composable
fun TvNavigationBar(
    selected: TvDestination,
    onSelected: (TvDestination) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    firstFocusRequester: FocusRequester? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
    ) {
        if (vertical) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TvDestination.entries.forEachIndexed { index, item ->
                    TvNavigationItem(
                        item = item,
                        selected = selected,
                        onSelected = onSelected,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        focusRequester = if (index == 0) firstFocusRequester else null,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvDestination.entries.forEachIndexed { index, item ->
                    TvNavigationItem(
                        item = item,
                        selected = selected,
                        onSelected = onSelected,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        focusRequester = if (index == 0) firstFocusRequester else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvNavigationItem(
    item: TvDestination,
    selected: TvDestination,
    onSelected: (TvDestination) -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "nav-focus")
    // Three appearances, not two. Focus and selection both used to take `primaryContainer`, which
    // meant that landing on the tab you were already on changed nothing on screen and landing on
    // any other one made it look already chosen. With a D-pad the one thing that must always be
    // visible is where the cursor is, so focus takes the full accent and selection keeps the
    // quieter container.
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        item == selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        item == selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier
            .padding(4.dp)
            .testTag("navigation-${item.name.lowercase()}")
            .scale(scale)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null) { onSelected(item) }
            .focusable(interactionSource = interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(item.icon, null, Modifier.size(28.dp), tint = content)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(item.label),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
fun TvFocusedSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "surface-focus")
    Surface(
        modifier = modifier.scale(scale),
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        color = if (focused) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        content = content,
    )
}

private fun rateText(bytes: Long): String = scaleBytes(bytes).let {
    val unit = when (it.scale) {
        com.mydrop.vpn.core.format.ByteScale.Bytes -> "B/s"
        com.mydrop.vpn.core.format.ByteScale.Kilo -> "KiB/s"
        com.mydrop.vpn.core.format.ByteScale.Mega -> "MiB/s"
        com.mydrop.vpn.core.format.ByteScale.Giga -> "GiB/s"
        else -> "TiB/s"
    }
    "${it.value} $unit"
}
