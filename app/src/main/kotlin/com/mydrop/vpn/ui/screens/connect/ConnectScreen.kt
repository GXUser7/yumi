package com.mydrop.vpn.ui.screens.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.R
import com.mydrop.vpn.core.format.ValueAndUnit
import com.mydrop.vpn.core.format.formatDuration
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.ui.MainUiState
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.components.TrafficSparkline
import com.mydrop.vpn.ui.components.TrafficWaves
import com.mydrop.vpn.ui.components.rememberRateHistory
import com.mydrop.vpn.ui.format.formatBytes
import com.mydrop.vpn.ui.format.formatRate
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle
import kotlinx.coroutines.delay

/** Corner radius of the flow figure and of the statistics panel it grows into. */
private val FigureShape = RoundedCornerShape(44.dp)

/**
 * The tunnel screen.
 *
 * Composition, top to bottom: poster headline, the flow figure, the control pill, the navigation
 * pill. Nothing moves between states — only what fills the figure and what the pill says — so the
 * screen never reshuffles under the thumb.
 *
 * The figure is centred with equal margins rather than bled off one edge. It is the main object
 * of the screen, and an off-centre main object reads as a layout mistake rather than as a device.
 * The one asymmetry left is the latency badge sitting in its top-left corner, and that badge
 * carries a number.
 */
@Composable
fun ConnectScreen(
    state: MainUiState,
    onToggleConnection: () -> Unit,
    onPickServer: () -> Unit,
    onRoutingModeChange: (RoutingMode) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val traffic = state.traffic
    val vpnState = state.vpnState

    var statsExpanded by rememberSaveable { mutableStateOf(false) }

    val downloadHistory = rememberRateHistory()
    val uploadHistory = rememberRateHistory()

    // Read through rememberUpdatedState because the sampler below outlives the composition it
    // started in and would otherwise keep charting the rates of the second it was launched.
    val currentTraffic by rememberUpdatedState(traffic)

    // Sampled on a clock rather than on every change of the counters. Keying the effect on the
    // value looked equivalent and was not: two identical seconds — an idle tunnel — produce one
    // sample, so the axis stopped being time and a lull was drawn as a single point. It also meant
    // the chart's speed depended on how many status messages arrived.
    //
    // The window follows the tunnel being up rather than being *connected*, so a server switch
    // draws its own dip instead of wiping the history and folding the panel away underneath the
    // user.
    LaunchedEffect(vpnState.isActive) {
        if (!vpnState.isActive) {
            downloadHistory.clear()
            uploadHistory.clear()
            statsExpanded = false
            return@LaunchedEffect
        }
        while (true) {
            downloadHistory.push(currentTraffic.downloadBytesPerSecond)
            uploadHistory.push(currentTraffic.uploadBytesPerSecond)
            delay(1_000)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TonalIconButton(Icons.Rounded.Article, stringResource(R.string.connect_logs), onOpenLogs)
            Spacer(Modifier.width(8.dp))
            // Settings kept its place in the navigation pill, so the shortcut here was a second
            // door to the same room. Measuring the tunnel has nowhere else to be reached from.
            TonalIconButton(
                Icons.Rounded.Speed,
                stringResource(R.string.connect_speed_test),
                onOpenSpeedTest,
            )
        }

        Headline(
            state = state,
            statsExpanded = statsExpanded,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        AnimatedVisibility(visible = state.tunnelIsSimulated) {
            SimulationBanner(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // Drag anywhere on the figure: it is the statistics panel in its collapsed form,
                // and a 250 dp target beats hunting for a handle.
                .pointerInput(Unit) {
                    var travel = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = {
                            if (travel < -THRESHOLD_PX) statsExpanded = true
                            if (travel > THRESHOLD_PX) statsExpanded = false
                        },
                    ) { _, amount -> travel += amount }
                },
        ) {
            AnimatedContent(
                targetState = statsExpanded,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "stage",
            ) { expanded ->
                if (expanded) {
                    StatsPanel(
                        state = state,
                        downloadSeries = downloadHistory.values,
                        uploadSeries = uploadHistory.values,
                        onRoutingModeChange = onRoutingModeChange,
                    )
                } else {
                    FlowFigure(
                        state = state,
                        latency = state.selectedNode?.let { state.latencies[it.id] },
                        onPickServer = onPickServer,
                    )
                }
            }
        }

        ControlPill(
            state = vpnState,
            onClick = onToggleConnection,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

private const val THRESHOLD_PX = 90f

/* ── Headline ─────────────────────────────────────────────────────────────────────────────── */

@Composable
private fun Headline(state: MainUiState, statsExpanded: Boolean, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val node = state.selectedNode

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.vpnState) {
        while (state.vpnState is VpnState.Connected) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val connected = state.vpnState as? VpnState.Connected

    val primary: String
    val secondary: String
    val accent: Boolean
    val kind: String

    when {
        statsExpanded -> {
            kind = "stats"
            primary = stringResource(R.string.connect_session)
            secondary = formatBytes(state.traffic.downloadBytes + state.traffic.uploadBytes).toString()
            accent = true
        }

        connected != null -> {
            kind = "connected"
            primary = stringResource(R.string.connect_protected)
            secondary = formatDuration(now - connected.connectedAtEpochMillis)
            accent = true
        }

        state.vpnState is VpnState.Connecting -> {
            kind = "connecting"
            primary = stringResource(R.string.connect_raising)
            secondary = stringResource(R.string.connect_tunnel_word)
            accent = false
        }

        state.vpnState is VpnState.Disconnecting -> {
            kind = "disconnecting"
            primary = stringResource(R.string.connect_stopping)
            secondary = stringResource(R.string.connect_tunnel_word)
            accent = false
        }

        state.vpnState is VpnState.Failed -> {
            kind = "failed"
            primary = stringResource(R.string.connect_failed_primary)
            secondary = stringResource(R.string.connect_failed_secondary)
            accent = false
        }

        else -> {
            kind = "idle"
            primary = stringResource(R.string.connect_tunnel_primary)
            secondary = stringResource(R.string.connect_tunnel_secondary)
            accent = false
        }
    }

    val subtitle = when {
        statsExpanded -> stringResource(R.string.connect_pull_to_return)
        state.vpnState is VpnState.Failed -> (state.vpnState as VpnState.Failed).message
        state.vpnState is VpnState.Connecting -> stringResource(
            R.string.connect_connecting_with,
            stringResource((state.vpnState as VpnState.Connecting).phase.labelRes),
            node?.name ?: stringResource(R.string.connect_the_server),
        )
        // The mobile mark answers the question a server nobody chose always raises: why this one.
        // Without it the tunnel silently sits somewhere else after a walk to the shops, and the
        // only explanation is a line in a journal the user has no reason to open.
        connected != null -> node?.let {
            val mobile = it.id in state.settings.mobileNodeIds
            val mark = if (mobile) " · " + stringResource(R.string.connect_mobile_server) else ""
            "${it.name} · ${it.protocol.name}$mark"
        }
            ?: stringResource(R.string.connect_tunnel_active)
        node != null -> stringResource(R.string.connect_direct_with_standby, node.name)
        else -> stringResource(R.string.connect_no_server)
    }

    Column(modifier = modifier) {
        Text(
            text = primary,
            style = MaterialTheme.typography.displayLarge,
            color = if (accent) semantic.connected else MaterialTheme.colorScheme.onSurface,
        )
        // Keyed on the kind of headline, never on the text. Keying on the text crossfaded the
        // whole line once a second while the timer ticked, which read as flicker; the digits now
        // update in place and only a real state change animates. Tabular figures keep the line
        // from twitching sideways as digit widths change.
        // The unused target parameter is the point, and lint's objection to it is the bug this
        // shape avoids: reading the animated state here would mean animating on `secondary`, which
        // is the session counter and changes every second.
        @Suppress("UnusedContentLambdaTargetStateParameter")
        AnimatedContent(
            targetState = kind,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "headline-second-line",
        ) { _ ->
            Text(
                text = secondary,
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                color = if (accent) semantic.connected else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.vpnState is VpnState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
        )
    }
}

/* ── The figure ───────────────────────────────────────────────────────────────────────────── */

@Composable
private fun FlowFigure(
    state: MainUiState,
    latency: LatencyResult?,
    onPickServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val running = state.vpnState is VpnState.Connected
    val warming = state.vpnState is VpnState.Connecting

    val container by animateColorAsState(
        targetValue = when {
            running -> MaterialTheme.colorScheme.surfaceContainerLow
            warming -> MaterialTheme.colorScheme.surfaceContainerLowest
            else -> MaterialTheme.colorScheme.surfaceContainerLowest
        },
        label = "figure-container",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(FigureShape)
            .background(container)
            .then(
                // An empty outline is how "no traffic" is stated. Once the field is full the
                // border would only compete with it.
                if (running) Modifier
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, FigureShape)
            ),
    ) {
        TrafficWaves(
            downloadBytesPerSecond = state.traffic.downloadBytesPerSecond,
            uploadBytesPerSecond = state.traffic.uploadBytesPerSecond,
            downloadColor = semantic.download,
            uploadColor = semantic.upload,
            warming = warming,
        )

        // Grip: the figure is the statistics panel collapsed, and this says so.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )

        LatencyBadge(
            latency = latency,
            onClick = onPickServer,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )

        if (running) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "↓ ${formatRate(state.traffic.downloadBytesPerSecond)}",
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = semantic.download,
                )
                Text(
                    text = "↑ ${formatRate(state.traffic.uploadBytesPerSecond)}",
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = semantic.upload,
                )
            }
        } else {
            Text(
                text = stringResource(
                    if (warming) R.string.connect_stream_warming else R.string.connect_stream_empty,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomStart).padding(22.dp),
            )
        }
    }
}

/**
 * Latency as a rounded square in the figure's top-left corner. Tapping it opens the server list,
 * because the number and the choice it argues for belong together.
 */
@Composable
private fun LatencyBadge(
    latency: LatencyResult?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val value = latency?.takeIf { !it.failed }?.millis

    Card(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = value?.toString() ?: "—",
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                color = when {
                    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    value < 80 -> semantic.latencyFast
                    value < 180 -> semantic.latencyMedium
                    else -> semantic.latencySlow
                },
            )
            Text(
                text = stringResource(R.string.unit_millis).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ── Statistics ───────────────────────────────────────────────────────────────────────────── */

@Composable
private fun StatsPanel(
    state: MainUiState,
    downloadSeries: List<Long>,
    uploadSeries: List<Long>,
    onRoutingModeChange: (RoutingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val traffic = state.traffic

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(FigureShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RateTile(
                stringResource(R.string.connect_rate_download),
                formatRate(traffic.downloadBytesPerSecond),
                semantic.download,
                Modifier.weight(1f),
            )
            RateTile(
                stringResource(R.string.connect_rate_upload),
                formatRate(traffic.uploadBytesPerSecond),
                semantic.upload,
                Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(12.dp),
        ) {
            TrafficSparkline(
                downloadHistory = downloadSeries,
                uploadHistory = uploadSeries,
                downloadColor = semantic.download,
                uploadColor = semantic.upload,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    R.string.connect_received,
                    formatBytes(traffic.downloadBytes).toString(),
                ),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = semantic.download,
            )
            Text(
                text = stringResource(
                    R.string.connect_sent,
                    formatBytes(traffic.uploadBytes).toString(),
                ),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = semantic.upload,
            )
        }

        // Scrollable rather than wrapped: three Russian labels do not fit a narrow phone, and a
        // chip row that silently clips its last option hides a routing mode the user owns.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoutingMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.settings.routingMode == mode,
                    onClick = { onRoutingModeChange(mode) },
                    label = {
                    Text(
                        stringResource(mode.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                )
            }
        }
    }
}

/** [ValueAndUnit] keeps the number and its unit apart precisely so the tile can size them apart. */
@Composable
private fun RateTile(
    label: String,
    rate: ValueAndUnit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = rate.value,
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                color = accent,
            )
            Text(
                text = rate.unit,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/* ── Control ──────────────────────────────────────────────────────────────────────────────── */

/** Tall enough to be the obvious thing on the screen. */
private val ControlHeight = 116.dp

/**
 * The control: full width always, with the corner radius carrying the state.
 *
 * It keeps its width when the tunnel comes up. Contracting to a square looked deliberate in a
 * mockup, but on the device it moves the tap target out from under the thumb every time the state
 * changes, and the label has to disappear with it. The radius still relaxes from a pill to a
 * rounded rectangle, so the shape reads differently without the geometry moving.
 */
@Composable
private fun ControlPill(
    state: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val connected = state is VpnState.Connected
    val busy = state is VpnState.Connecting || state is VpnState.Disconnecting

    val container by animateColorAsState(
        targetValue = when {
            connected -> MaterialTheme.colorScheme.surface
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "control-container",
    )
    val content by animateColorAsState(
        targetValue = when {
            connected || busy -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        label = "control-content",
    )
    // Half the height is a pill; a quarter of it is the square. Animating the radius rather than
    // swapping shapes keeps the corners continuous through the whole transition.
    val corner by animateDpAsState(
        targetValue = if (connected) 34.dp else ControlHeight / 2,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "control-corner",
    )

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ControlHeight),
        shape = RoundedCornerShape(corner),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = if (connected) BorderStroke(1.5.dp, semantic.connected) else null,
        contentPadding = PaddingValues(0.dp),
    ) {
        when (state) {
            is VpnState.Connecting, VpnState.Disconnecting ->
                ShapeSpinner(color = content, size = 28.dp)

            is VpnState.Connected -> Icon(Icons.Rounded.Pause, null, Modifier.size(30.dp))
            is VpnState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(26.dp))
            VpnState.Disconnected -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(26.dp))
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = stringResource(
                when (state) {
                    is VpnState.Connected -> R.string.connect_button_disconnect
                    is VpnState.Connecting -> R.string.connect_button_connecting
                    VpnState.Disconnecting -> R.string.connect_button_disconnecting
                    is VpnState.Failed -> R.string.action_retry
                    VpnState.Disconnected -> R.string.connect_button_connect
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SimulationBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.connect_simulated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
