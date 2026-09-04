package com.mydrop.vpn.tv

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.UpdateState
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.pairing.PairingReceiverState
import com.mydrop.vpn.ui.components.QrCode
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle

/**
 * Margin for the screens behind the navigation rail.
 *
 * These screens no longer reserve room for the bar at their bottom edge: it lives on the right
 * now and [YumiTvApp] insets the whole screen by its width. What that buys is the reason the rail
 * exists at all — the tabs used to sit on top of the last row of a two-hundred-server list, and
 * the only way to reach them with a D-pad was to scroll all the way through it.
 */
private val SectionInset = 32.dp

/**
 * Bottom margin for those screens, which is [ScreenInset] rather than [SectionInset].
 *
 * The rail is inset from the panel by that much, so anything else at the bottom stops short of it
 * and the two edges disagree by eight dp — small, and visible, because they sit side by side.
 */
private val SectionBottomInset = ScreenInset

/* ── Servers ──────────────────────────────────────────────────────────────────────────────── */

@Composable
fun TvServersScreen(
    state: TvUiState,
    onSelect: (ProxyNode) -> Unit,
    onMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeId = state.vpnState.activeNodeId
    Column(modifier.fillMaxSize().padding(SectionInset, SectionInset, SectionInset, SectionBottomInset)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tv_servers_title), style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tv_servers_summary, state.nodes.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onMeasure) {
                Icon(Icons.Rounded.NetworkPing, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.tv_measure))
            }
        }
        Spacer(Modifier.height(22.dp))
        // The card height is measured out of the space the grid actually got rather than being a
        // number chosen in advance. With a fixed 128 dp the viewport held two rows and a sliver,
        // so a D-pad step down left half a row hanging off the top and half off the bottom, and
        // neither one could be read. Three whole rows means a step scrolls by exactly one.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardHeight = (maxHeight - CardGap * (ServerRows - 1)) / ServerRows
            // Three whole rows fill the viewport exactly, so a D-pad step scrolls by exactly one
            // row and the rows stay whole on their own. An earlier version also snapped the top
            // row flush whenever the scroll settled off a boundary; it fought the scroll that
            // focus had just performed and every step down landed with a visible bounce. Getting
            // the height right is the whole fix — correcting it afterwards is not a fix, it is a
            // second animation arguing with the first.
            LazyVerticalGrid(
                // Two columns rather than three. A server's name carries its country, its flag
                // and whatever the provider tagged it with, and at three columns all of that
                // ellipsised into "Германия #2 | Torr…" — the part that tells two servers apart
                // is the part that was being cut off.
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(state.nodes, key = { it.id }) { node ->
                    TvServerCard(
                        node = node,
                        latency = state.latencies[node.id],
                        selected = node.id == state.selectedNode?.id,
                        active = node.id == activeId,
                        height = cardHeight,
                        onClick = { onSelect(node) },
                    )
                }
            }
        }
    }
}

/** Rows of servers on screen at once, and the gap between them. */
private const val ServerRows = 3
private val CardGap = 16.dp

/**
 * One server.
 *
 * Three things have to be told apart from across a room: what the D-pad is sitting on, what will
 * be used next, and what the tunnel is actually running through right now. Before this the only
 * mark of the chosen server was ten dp of extra corner radius, which nobody has ever noticed on a
 * television. So focus takes the accent fill, the chosen one takes a heavy outline and a tick,
 * and the live one says so in a word.
 */
@Composable
private fun TvServerCard(
    node: ProxyNode,
    latency: LatencyResult?,
    selected: Boolean,
    active: Boolean,
    height: Dp,
    onClick: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "server-focus")

    val stateLabel = when {
        active -> stringResource(R.string.tv_server_active)
        selected -> stringResource(R.string.tv_server_selected)
        else -> null
    }

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.height(height).scale(scale),
        shape = RoundedCornerShape(28.dp),
        color = when {
            focused -> MaterialTheme.colorScheme.primaryContainer
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = when {
            focused -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            selected -> BorderStroke(3.dp, semantic.connected)
            else -> null
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // The name gets the whole width and the readings go underneath it. Sharing the line
            // with the latency left it about half a card, and these names open with four emoji —
            // a flag, a pad, a bolt, a star — each about as wide as two letters. What survived
            // was "LTE Ав…": the emoji, and none of the part that tells two servers apart.
            //
            // The tick sits with the name and takes no room until there is one to show. A fixed
            // slot did line every name up, but only one card in a hundred and ten has a tick in
            // it, so what it really bought was forty dp of nothing down the left of the whole
            // grid. Here the one chosen server pushes its own name across, and that shift is
            // itself a mark: it is the only row in the column that does not start flush.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stateLabel,
                        modifier = Modifier.size(26.dp),
                        tint = semantic.connected,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(node.protocol.name, stateLabel).joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active || selected) semantic.connected
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                TvLatencyReadout(latency)
            }
        }
    }
}

/**
 * Latency, in the phone's buckets: under 150 ms fast, under 400 usable, past that bad.
 *
 * Coarse on purpose, and the same numbers as [com.mydrop.vpn.ui.components.LatencyChip] on the
 * phone, because a finer scale would claim a precision a TCP handshake probe does not have.
 */
@Composable
private fun TvLatencyReadout(latency: LatencyResult?) {
    val semantic = LocalSemanticColors.current
    val (label, colour) = when {
        latency == null -> stringResource(R.string.tv_not_measured) to semantic.latencyDead
        latency.failed -> stringResource(R.string.tv_latency_failed) to semantic.latencySlow
        latency.millis < 150 -> "${latency.millis} ms" to semantic.latencyFast
        latency.millis < 400 -> "${latency.millis} ms" to semantic.latencyMedium
        else -> "${latency.millis} ms" to semantic.latencySlow
    }
    Text(label, style = MonoStyle, color = colour, maxLines = 1)
}

/* ── Subscriptions ────────────────────────────────────────────────────────────────────────── */

@Composable
fun TvSubscriptionsScreen(
    state: TvUiState,
    pairing: PairingReceiverState,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onManualAdd: (String) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onRemove: (Subscription) -> Unit,
    onEnabled: (Subscription, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPairing by rememberSaveable { mutableStateOf(state.subscriptions.isEmpty()) }
    var manual by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (showPairing) {
            DisposableEffect(Unit) {
                if (pairing is PairingReceiverState.Idle) onStartPairing()
                onDispose(onStopPairing)
            }
            TvPairingPanel(
                state = pairing,
                onRetry = onStartPairing,
                onManual = { manual = true },
                onClose = { if (state.subscriptions.isNotEmpty()) showPairing = false },
            )
        } else {
            Column(Modifier.fillMaxSize().padding(SectionInset, SectionInset, SectionInset, SectionBottomInset)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.tv_subscriptions_title),
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showPairing = true; onStartPairing() }) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.tv_add_subscription))
                    }
                }
                Spacer(Modifier.height(22.dp))
                LazyColumn(
                    contentPadding = PaddingValues(bottom = SectionInset),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.subscriptions, key = { it.id }) { subscription ->
                        TvSubscriptionRow(subscription, onRefresh, onRemove, onEnabled)
                    }
                }
            }
        }
    }

    if (manual) {
        ManualUrlDialog(
            onDismiss = { manual = false },
            onAdd = { onManualAdd(it); manual = false; showPairing = false },
        )
    }
}

@Composable
private fun TvPairingPanel(
    state: PairingReceiverState,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().padding(SectionInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.tv_pair_title), style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.tv_pair_hint),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = when (state) {
                PairingReceiverState.Idle -> stringResource(R.string.tv_pair_waiting)
                is PairingReceiverState.Waiting -> stringResource(R.string.tv_pair_waiting)
                PairingReceiverState.Receiving -> stringResource(R.string.tv_pair_receiving)
                is PairingReceiverState.Complete -> stringResource(R.string.tv_pair_complete)
                is PairingReceiverState.Failed -> stringResource(R.string.tv_pair_failed)
            }
            Text(status, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = onManual) { Text(stringResource(R.string.tv_manual)) }
                if (state is PairingReceiverState.Failed) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.tv_pair_retry)) }
                }
                if (state is PairingReceiverState.Complete) {
                    Button(onClick = onClose) { Text(stringResource(R.string.tv_nav_subscriptions)) }
                }
            }
        }
        // The code, and nothing underneath it. It used to sit on a focusable rounded card that
        // did nothing when activated - a stop on the D-pad for no reason - and the white ground
        // of the code kept its square corners inside that card's round ones, so the two shapes
        // argued instead of reading as one.
        //
        // The rounding stays well inside the quiet zone. At this size a module is about six dp,
        // the specification's three-module margin is some eighteen, and a 28 dp radius takes
        // about a module and a half out of the corner at its deepest. Rounding past that is where
        // stylised codes stop being scannable, and the finder squares are three modules in.
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            val invite = (state as? PairingReceiverState.Waiting)?.invite
            if (invite != null) {
                QrCode(invite.encode(), Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)))
            } else {
                Icon(
                    if (state is PairingReceiverState.Complete) Icons.Rounded.CheckCircle else Icons.Rounded.QrCode2,
                    null,
                    Modifier.size(96.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSubscriptionRow(
    subscription: Subscription,
    onRefresh: (Subscription) -> Unit,
    onRemove: (Subscription) -> Unit,
    onEnabled: (Subscription, Boolean) -> Unit,
) {
    // A plain surface, not a focusable one. The row used to be a single click target wrapping
    // the switch and both buttons, and a D-pad cannot step inside a thing that is itself the
    // target: the only reachable action was the whole row, which refreshed. Now each control is
    // its own stop and the row is just the card they sit on.
    Surface(
        modifier = Modifier.fillMaxWidth().height(128.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 22.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.remoteTitle ?: subscription.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (subscription.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subscription.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subscription.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 1) }
            }
            TvRowAction(onClick = { onEnabled(subscription, !subscription.enabled) }) {
                Switch(subscription.enabled, onCheckedChange = null)
            }
            TvRowAction(onClick = { onRefresh(subscription) }) {
                Icon(Icons.Rounded.Autorenew, stringResource(R.string.tv_refresh))
            }
            TvRowAction(onClick = { onRemove(subscription) }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.tv_delete))
            }
        }
    }
}

/** One D-pad stop inside a row: square, focusable, and lit the same way as everything else. */
@Composable
private fun TvRowAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    TvFocusedSurface(onClick, Modifier.size(96.dp), shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ManualUrlDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_manual_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onAdd(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.tv_add)) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.tv_cancel)) } },
    )
}

/* ── Settings ─────────────────────────────────────────────────────────────────────────────── */

@Composable
fun TvSettingsScreen(
    state: TvUiState,
    updates: UpdateState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (android.content.Context) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<EditableSetting?>(null) }
    var editedValue by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(SectionInset, SectionInset, SectionInset, SectionBottomInset)) {
        Text(stringResource(R.string.tv_settings_title), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(22.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = SectionInset),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Eighteen tiles in one undifferentiated grid is a list to read rather than a screen
            // to scan. The headings cost one row each and turn it into four short lists, which is
            // what somebody looking for "block ads" is actually navigating.
            sectionHeader(R.string.tv_section_appearance)
            item {
                CycleSetting(stringResource(R.string.tv_visualizer), visualizerName(state.settings.visualizer)) {
                    onUpdateSettings {
                        it.copy(visualizer = if (it.visualizer == Visualizer.Planet) Visualizer.Waves else Visualizer.Planet)
                    }
                }
            }
            item {
                CycleSetting(stringResource(R.string.tv_theme), stringResource(state.settings.themeMode.labelRes)) {
                    onUpdateSettings { it.copy(themeMode = it.themeMode.next()) }
                }
            }
            item {
                CycleSetting(stringResource(R.string.tv_language), stringResource(state.settings.language.labelRes)) {
                    onUpdateSettings { it.copy(language = it.language.next()) }
                }
            }

            sectionHeader(R.string.tv_section_behaviour)
            item {
                ToggleSetting(stringResource(R.string.tv_auto_connect), state.settings.autoConnectOnBoot) {
                    onUpdateSettings { s -> s.copy(autoConnectOnBoot = !s.autoConnectOnBoot) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_auto_fastest), state.settings.autoSelectFastest) {
                    onUpdateSettings { s -> s.copy(autoSelectFastest = !s.autoSelectFastest) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_auto_subscriptions), state.settings.subscriptionAutoUpdate) {
                    onUpdateSettings { s -> s.copy(subscriptionAutoUpdate = !s.subscriptionAutoUpdate) }
                }
            }

            sectionHeader(R.string.tv_section_network)
            item {
                CycleSetting(stringResource(R.string.tv_routing), stringResource(state.settings.routingMode.labelRes)) {
                    onUpdateSettings { it.copy(routingMode = it.routingMode.next()) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_bypass_lan), state.settings.bypassLan) {
                    onUpdateSettings { s -> s.copy(bypassLan = !s.bypassLan) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_block_ads), state.settings.blockAds) {
                    onUpdateSettings { s -> s.copy(blockAds = !s.blockAds) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_block_quic), state.settings.blockQuic) {
                    onUpdateSettings { s -> s.copy(blockQuic = !s.blockQuic) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_ipv6), state.settings.enableIpv6) {
                    onUpdateSettings { s -> s.copy(enableIpv6 = !s.enableIpv6) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_hijack_dns), state.settings.hijackDns) {
                    onUpdateSettings { s -> s.copy(hijackDns = !s.hijackDns) }
                }
            }
            item {
                ToggleSetting(stringResource(R.string.tv_dns_fallback), state.settings.dnsFallback) {
                    onUpdateSettings { s -> s.copy(dnsFallback = !s.dnsFallback) }
                }
            }
            item {
                ValueSetting(stringResource(R.string.tv_remote_dns), state.settings.remoteDns) {
                    editing = EditableSetting.RemoteDns; editedValue = state.settings.remoteDns
                }
            }
            item {
                ValueSetting(stringResource(R.string.tv_direct_dns), state.settings.directDns) {
                    editing = EditableSetting.DirectDns; editedValue = state.settings.directDns
                }
            }
            item {
                ValueSetting(stringResource(R.string.tv_mtu), state.settings.mtu.toString()) {
                    editing = EditableSetting.Mtu; editedValue = state.settings.mtu.toString()
                }
            }

            sectionHeader(R.string.tv_section_updates)
            item {
                val label = when (updates) {
                    is UpdateState.Available -> stringResource(R.string.tv_update_available, updates.release.version)
                    is UpdateState.Ready -> stringResource(R.string.tv_install)
                    is UpdateState.Downloading -> stringResource(R.string.tv_download)
                    else -> stringResource(R.string.tv_check_update)
                }
                CycleSetting(stringResource(R.string.tv_updates), label) {
                    when (updates) {
                        is UpdateState.Available -> onDownloadUpdate()
                        is UpdateState.Ready -> onInstallUpdate(context)
                        else -> onCheckUpdate()
                    }
                }
            }
        }
    }
    editing?.let { field ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    stringResource(
                        when (field) {
                            EditableSetting.RemoteDns -> R.string.tv_remote_dns
                            EditableSetting.DirectDns -> R.string.tv_direct_dns
                            EditableSetting.Mtu -> R.string.tv_mtu
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = editedValue,
                    onValueChange = { editedValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (field) {
                            EditableSetting.RemoteDns -> if (editedValue.isNotBlank()) {
                                onUpdateSettings { it.copy(remoteDns = editedValue.trim()) }
                            }
                            EditableSetting.DirectDns -> if (editedValue.isNotBlank()) {
                                onUpdateSettings { it.copy(directDns = editedValue.trim()) }
                            }
                            EditableSetting.Mtu -> editedValue.toIntOrNull()?.takeIf { it in 576..9_000 }?.let { mtu ->
                                onUpdateSettings { it.copy(mtu = mtu) }
                            }
                        }
                        editing = null
                    },
                ) { Text(stringResource(R.string.tv_save)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { editing = null }) { Text(stringResource(R.string.tv_cancel)) }
            },
        )
    }
}

/** A heading across the full width of the settings grid. Not focusable: there is nothing to do. */
private fun LazyGridScope.sectionHeader(@StringRes title: Int) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp),
        )
    }
}

@Composable
private fun ToggleSetting(title: String, checked: Boolean, onClick: () -> Unit) {
    TvFocusedSurface(onClick, Modifier.fillMaxWidth().height(96.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked, onCheckedChange = null)
        }
    }
}

/**
 * A setting whose value is a word rather than a switch.
 *
 * The value is set in the accent and one step larger than the label, because on a television the
 * thing being read is the current value — the label is only there to say what it belongs to.
 */
@Composable
private fun CycleSetting(title: String, value: String, onClick: () -> Unit) {
    TvFocusedSurface(onClick, Modifier.fillMaxWidth().height(96.dp)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ValueSetting(title: String, value: String, onClick: () -> Unit) =
    CycleSetting(title, value, onClick)

@Composable
private fun visualizerName(value: Visualizer): String = stringResource(
    if (value == Visualizer.Planet) R.string.tv_visualizer_planet else R.string.tv_visualizer_waves,
)

private fun ThemeMode.next(): ThemeMode = ThemeMode.entries[(ordinal + 1) % ThemeMode.entries.size]
private fun AppLanguage.next(): AppLanguage = AppLanguage.entries[(ordinal + 1) % AppLanguage.entries.size]
private fun RoutingMode.next(): RoutingMode = RoutingMode.entries[(ordinal + 1) % RoutingMode.entries.size]

private enum class EditableSetting { RemoteDns, DirectDns, Mtu }
