package com.mydrop.vpn.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.format.formatDuration
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteTunnelState
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.ShapeSpinner
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.format.formatRate
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import com.mydrop.vpn.ui.theme.MonoStyle
import kotlinx.coroutines.delay

/**
 * The television, from the sofa.
 *
 * Built to be recognisably the tunnel screen seen from the other side: the same poster headline,
 * the same control that changes shape as the state changes, the same list of servers underneath.
 * What is deliberately missing is everything the tunnel screen has that only makes sense on the
 * device it belongs to — the visualiser, the routing chips, the statistics panel. A remote should
 * look like the thing it drives without pretending to be it.
 *
 * The screen never waits for the television to answer before drawing. Whatever the last snapshot
 * said stays on screen while the link is being rebuilt, greyed rather than emptied, because a
 * remote that blanks itself every time the Wi-Fi hiccups is one nobody trusts.
 */
@Composable
fun RemoteScreen(
    state: RemoteConsoleState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val bond = state.active
    Column(modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(
            title = stringResource(R.string.remote_title),
            subtitle = bond?.hostName ?: stringResource(R.string.remote_not_linked),
            actions = {
                if (bond != null) {
                    TonalIconButton(
                        Icons.Rounded.LinkOff,
                        stringResource(R.string.remote_forget),
                        { onForget(bond.hostId) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TonalIconButton(Icons.Rounded.Close, stringResource(R.string.action_back), onBack)
            },
        )

        Spacer(Modifier.height(16.dp))

        if (bond == null) {
            NotLinked(state, onScan, Modifier.weight(1f).padding(horizontal = 20.dp))
            return@Column
        }

        LinkBanner(state.link, Modifier.padding(horizontal = 20.dp))

        val snapshot = state.snapshot
        StatusCard(
            snapshot = snapshot,
            online = state.link is RemoteLinkState.Online,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        ControlPill(
            state = snapshot?.state ?: RemoteTunnelState.Off,
            enabled = state.link is RemoteLinkState.Online,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(18.dp))

        Text(
            stringResource(R.string.remote_servers),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (state.nodes.isEmpty()) {
            Text(
                stringResource(R.string.remote_no_servers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.nodes, key = { it.id }) { node ->
                    NodeRow(
                        node = node,
                        selected = node.id == snapshot?.selectedNodeId,
                        enabled = state.link is RemoteLinkState.Online,
                        onClick = { onSelect(node.id) },
                    )
                }
            }
        }
    }
}

/* ── Before there is a television ─────────────────────────────────────────────────────────── */

@Composable
private fun NotLinked(state: RemoteConsoleState, onScan: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Icon(
            Icons.Rounded.Tv,
            null,
            Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            // Names what was heard on the network when there is something to name, because
            // "a television was found" and "look for a television" are answers to different
            // questions and only one of them needs a code scanned.
            state.sightings.firstOrNull()?.let { stringResource(R.string.remote_found, it.hostName) }
                ?: stringResource(R.string.remote_not_linked),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.remote_not_linked_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onScan) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.remote_scan))
        }
    }
}

/* ── The link ─────────────────────────────────────────────────────────────────────────────── */

@Composable
private fun LinkBanner(link: RemoteLinkState, modifier: Modifier = Modifier) {
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
                    label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ── What the television is doing ─────────────────────────────────────────────────────────── */

@Composable
private fun StatusCard(snapshot: RemoteSnapshot?, online: Boolean, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val running = snapshot?.state == RemoteTunnelState.On

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = if (running) BorderStroke(1.dp, semantic.connected) else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    when (snapshot?.state) {
                        RemoteTunnelState.On -> R.string.connect_protected
                        RemoteTunnelState.Warming -> R.string.connect_raising
                        RemoteTunnelState.Stopping -> R.string.connect_stopping
                        RemoteTunnelState.Failed -> R.string.connect_failed_primary
                        else -> R.string.remote_off
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                // Greyed rather than emptied while the link is down: the words are still the last
                // thing the television said, and saying nothing at all would be less true.
                color = when {
                    !online -> MaterialTheme.colorScheme.onSurfaceVariant
                    running -> semantic.connected
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            val detail = when {
                snapshot == null -> ""
                snapshot.message.isNotEmpty() -> snapshot.message
                running -> formatDuration(now - snapshot.connectedAtEpochMillis)
                else -> ""
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot?.needsConsentOnTv == true) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.remote_needs_consent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (running) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        "↓ ${formatRate(snapshot?.downloadBytesPerSecond ?: 0)}",
                        style = MonoStyle,
                        color = semantic.download,
                    )
                    Text(
                        "↑ ${formatRate(snapshot?.uploadBytesPerSecond ?: 0)}",
                        style = MonoStyle,
                        color = semantic.upload,
                    )
                }
            }
        }
    }
}

/**
 * The same control as the tunnel screen's, driving a different tunnel.
 *
 * Disabled while the link is down rather than hidden: the button is where the user is looking, and
 * a control that vanishes when the Wi-Fi stumbles takes the explanation with it.
 */
@Composable
private fun ControlPill(
    state: RemoteTunnelState,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val running = state == RemoteTunnelState.On
    val busy = state == RemoteTunnelState.Warming || state == RemoteTunnelState.Stopping

    val container by animateColorAsState(
        when {
            running -> MaterialTheme.colorScheme.surface
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "remote-control-container",
    )
    val content by animateColorAsState(
        if (running || busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
        label = "remote-control-content",
    )
    val corner by animateDpAsState(
        if (running) 26.dp else ControlHeight / 2,
        MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "remote-control-corner",
    )

    Button(
        onClick = { if (running) onDisconnect() else onConnect() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(ControlHeight),
        shape = RoundedCornerShape(corner),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = if (running) BorderStroke(1.5.dp, semantic.connected) else null,
        contentPadding = PaddingValues(0.dp),
    ) {
        when {
            busy -> ShapeSpinner(color = content, size = 26.dp)
            running -> Icon(Icons.Rounded.Pause, null, Modifier.size(26.dp))
            state == RemoteTunnelState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(24.dp))
            else -> Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(
                when {
                    running -> R.string.connect_button_disconnect
                    busy -> R.string.connect_button_connecting
                    else -> R.string.connect_button_connect
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

private val ControlHeight = 58.dp

/* ── The television's servers ─────────────────────────────────────────────────────────────── */

@Composable
private fun NodeRow(
    node: RemoteNode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (node.group.isNotEmpty()) {
                    Text(
                        node.group,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            val millis = node.latencyMillis
            Box(contentAlignment = Alignment.CenterEnd) {
                Text(
                    when {
                        node.latencyFailed -> stringResource(R.string.servers_latency_failed)
                        millis != null -> "$millis ms"
                        else -> "—"
                    },
                    style = MonoStyle,
                    // The same three bands the servers list uses, so a number means the same thing
                    // whichever screen it is read on.
                    color = when {
                        node.latencyFailed -> semantic.latencySlow
                        millis == null -> semantic.latencyDead
                        millis < 120 -> semantic.latencyFast
                        millis < 300 -> semantic.latencyMedium
                        else -> semantic.latencySlow
                    },
                )
            }
        }
    }
}
