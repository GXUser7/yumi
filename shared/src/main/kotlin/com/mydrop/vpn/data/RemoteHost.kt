package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.net.isNumericAddress
import com.mydrop.vpn.remote.REMOTE_PAIRING_LIFETIME_MILLIS
import com.mydrop.vpn.remote.REMOTE_VERSION
import com.mydrop.vpn.remote.RemoteBeacon
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteCrypto
import com.mydrop.vpn.remote.RemoteInvite
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemotePeer
import com.mydrop.vpn.remote.RemoteServer
import com.mydrop.vpn.remote.RemoteSettings
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteSubscription
import com.mydrop.vpn.remote.RemoteTunnelState
import com.mydrop.vpn.remote.localAddress
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The television's end of the remote control.
 *
 * Held by [AppContainer] rather than by the screen, and the difference is the whole point: the
 * activity goes away when somebody presses Home, but a tunnel that is up keeps the process alive
 * through its own foreground service — so the phone can still turn the television off from the
 * sofa after the app itself has been left. What it cannot do is start a tunnel on a set whose
 * process is gone, and nothing here pretends otherwise.
 *
 * What the phone may ask for is deliberately small: the tunnel on or off, and which server it
 * comes out of. Everything that changes what the television *is* — subscriptions, routing, the
 * language it speaks — stays on the television, where the person changing it can see what they
 * are doing.
 */
class RemoteHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: RemoteRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
    private val latencyTester: LatencyTester,
    private val subscriptionRefresher: SubscriptionRefresher,
) {
    /**
     * Android will not raise a tunnel until the owner of the device has agreed to it in a system
     * dialog, and that dialog is on the television. Both halves of saying so are here: a flag the
     * phone can read, and the intent itself for the set's own screen to put up if anyone is
     * looking at it.
     */
    private val _consentRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val consentRequests = _consentRequests.asSharedFlow()
    private val _needsConsent = MutableStateFlow(false)
    private val _notice = MutableStateFlow("")

    private val pingCoordinator = FilteredPingCoordinator(scope, profiles::recordLatency)
    private val singlePingingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    private val pingingNodeIds: StateFlow<Set<String>> = combine(
        pingCoordinator.pingingNodeIds,
        singlePingingNodeIds,
    ) { batch, single -> batch + single }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val peers: StateFlow<List<RemotePeer>> =
        store.state.map { it.peers }.stateIn(scope, SharingStarted.Eagerly, store.value.peers)

    private val _invite = MutableStateFlow<RemoteInvite?>(null)

    /** The code on screen, or null when the pairing window is shut. */
    val invite: StateFlow<RemoteInvite?> = _invite.asStateFlow()

    private var pairingWatch: Job? = null

    /**
     * The server list and its content hash, rebuilt only when the servers themselves change.
     *
     * Kept out of the snapshot's own combine because traffic ticks once a second and the list does
     * not: mapping three hundred servers and hashing them at one hertz is work a set-top box has
     * better uses for, and it produced the same answer every time.
     */
    private data class NodeList(val revision: Int, val nodes: List<RemoteNode>)
    private data class SubscriptionList(val revision: Int, val items: List<RemoteSubscription>)

    private val nodeList: StateFlow<NodeList> = profiles.state.map { profile ->
        val groups = profile.subscriptions.associate { it.id to (it.remoteTitle ?: it.name) }
        val nodes = profile.visibleNodes.map { node ->
            val latency = profile.latencies[node.id]
            RemoteNode(
                id = node.id,
                name = node.name,
                group = node.subscriptionId?.let(groups::get).orEmpty(),
                latencyMillis = latency?.takeUnless { it.failed }?.millis,
                latencyFailed = latency?.failed == true,
                protocol = node.protocol,
                badges = node.badges,
                subscriptionId = node.subscriptionId,
                memberProtocols = node.group?.members?.map { it.protocol }?.distinct().orEmpty(),
            )
        }
        NodeList(nodes.hashCode(), nodes)
    }.stateIn(scope, SharingStarted.Eagerly, NodeList(0, emptyList()))

    private val subscriptionList: StateFlow<SubscriptionList> = combine(
        profiles.state,
        subscriptionRefresher.running,
    ) { profile, refreshingIds ->
        val counts = profile.nodes.groupingBy { it.subscriptionId }.eachCount()
        val items = profile.subscriptions.map { sub ->
            RemoteSubscription(
                id = sub.id,
                name = sub.name,
                remoteTitle = sub.remoteTitle,
                urlHost = runCatching { URI(sub.url).host.orEmpty() }.getOrDefault(""),
                enabled = sub.enabled,
                nodeCount = counts[sub.id] ?: 0,
                lastUpdatedEpochMillis = sub.lastUpdatedEpochMillis,
                lastError = sub.lastError?.let(::sanitizeRemoteMessage),
                userInfo = sub.userInfo,
                refreshing = sub.id in refreshingIds,
            )
        }
        SubscriptionList(items.hashCode(), items)
    }.stateIn(scope, SharingStarted.Eagerly, SubscriptionList(0, emptyList()))

    private data class ProfileSlice(
        val selectedNodeId: String?,
        val nodes: NodeList,
        val subscriptions: SubscriptionList,
    )

    private data class TunnelSlice(
        val vpn: VpnState,
        val downloadBps: Long,
        val uploadBps: Long,
        val consent: Boolean,
        val notice: String,
    )

    val snapshot: StateFlow<RemoteSnapshot> = combine(
        combine(profiles.state, nodeList, subscriptionList) { profile, nodes, subs ->
            ProfileSlice(profile.selectedNodeId, nodes, subs)
        },
        combine(tunnel.state, tunnel.traffic, _needsConsent, _notice) { vpn, traffic, consent, notice ->
            TunnelSlice(vpn, traffic.downloadBytesPerSecond, traffic.uploadBytesPerSecond, consent, notice)
        },
        settings.settings,
        pingingNodeIds,
    ) { profileSlice, tunnelSlice, currentSettings, pingingIds ->
        val vpn = tunnelSlice.vpn
        RemoteSnapshot(
            hostName = store.identity().name,
            state = when (vpn) {
                is VpnState.Connected -> RemoteTunnelState.On
                is VpnState.Connecting -> RemoteTunnelState.Warming
                VpnState.Disconnecting -> RemoteTunnelState.Stopping
                is VpnState.Failed -> RemoteTunnelState.Failed
                VpnState.Disconnected -> RemoteTunnelState.Off
            },
            message = (vpn as? VpnState.Failed)?.message?.let(::sanitizeRemoteMessage) ?: tunnelSlice.notice,
            needsConsentOnTv = tunnelSlice.consent && !vpn.isActive,
            selectedNodeId = profileSlice.selectedNodeId,
            connectedAtEpochMillis = (vpn as? VpnState.Connected)?.connectedAtEpochMillis ?: 0L,
            downloadBytesPerSecond = tunnelSlice.downloadBps,
            uploadBytesPerSecond = tunnelSlice.uploadBps,
            // The list's own content hash, so "has this changed" needs nothing kept beside it.
            // A collision would cost one stale list until the next real change, which is a price
            // worth the absence of a revision counter that two writers could disagree about.
            nodesRevision = profileSlice.nodes.revision,
            nodes = profileSlice.nodes.nodes,
            pingingNodeIds = pingingIds,
            subscriptionsRevision = profileSlice.subscriptions.revision,
            subscriptions = profileSlice.subscriptions.items,
            settings = currentSettings.toRemoteSettings(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, RemoteSnapshot())

    private val server = RemoteServer(scope, store, snapshot, ::apply)
    private val beacon = RemoteBeacon(scope, store::identity, { server.port.value })

    fun start() {
        server.start()
        beacon.start()
    }

    fun stop() {
        server.stop()
        beacon.stop()
        stopPairing()
    }

    /* ── The introduction ─────────────────────────────────────────────────────────────────── */

    /** Opens the window and puts a code on screen. Null when there is no network to be found on. */
    fun startPairing(): RemoteInvite? {
        stopPairing()
        val address = localAddress(context)?.hostAddress ?: return null
        val port = server.port.value.takeIf { it > 0 } ?: return null
        val identity = store.identity()
        val invite = RemoteInvite(
            version = REMOTE_VERSION,
            host = address,
            port = port,
            code = store.openPairing(),
            hostPublicKey = RemoteCrypto.encode(identity.publicKey),
            hostId = identity.id,
            hostName = identity.name,
        )
        _invite.value = invite
        // Taken off the screen the moment the window shuts, whether that was the clock running out
        // or a phone walking through it. A code still showing after it has stopped working is the
        // one failure the person holding the phone cannot diagnose.
        pairingWatch = scope.launch {
            val until = System.currentTimeMillis() + REMOTE_PAIRING_LIFETIME_MILLIS
            while (System.currentTimeMillis() < until && store.openPairingCode() != null) {
                delay(500)
            }
            _invite.value = null
            store.closePairing()
        }
        return invite
    }

    fun stopPairing() {
        pairingWatch?.cancel()
        pairingWatch = null
        _invite.value = null
        store.closePairing()
    }

    fun forget(peerId: String) = store.forgetPeer(peerId)

    /* ── What the phone asked for ─────────────────────────────────────────────────────────── */

    private suspend fun apply(command: RemoteCommand) {
        when (command) {
            // A keepalive. The snapshot is already on its way out on its own clock.
            RemoteCommand.Look -> Unit

            RemoteCommand.Disconnect -> {
                _notice.value = ""
                launcher.disconnect()
            }

            RemoteCommand.Connect -> connect()

            is RemoteCommand.Select -> {
                val node = profiles.nodes.firstOrNull { it.id == command.nodeId } ?: return
                profiles.selectNode(node.id)
                // Moves a running tunnel across, which is what choosing a server does when it is
                // done with the set's own remote — see TvViewModel.selectNode.
                val vpn = tunnel.state.value
                if (vpn.isActive && vpn.activeNodeId != node.id) {
                    launcher.connectTo(node, "remote server selected")
                }
            }

            is RemoteCommand.PingNodes -> {
                val requested = command.nodeIds.toHashSet()
                val targets = if (requested.isEmpty()) profiles.nodes else profiles.nodes.filter { it.id in requested }
                pingCoordinator.ping(
                    nodes = targets,
                    mode = settings.value.pingMode,
                    measure = { list, mode, onResult -> latencyTester.measureAll(list, mode, onResult) },
                )
            }

            is RemoteCommand.PingNode -> {
                val node = profiles.nodes.firstOrNull { it.id == command.nodeId } ?: return
                scope.launch {
                    singlePingingNodeIds.update { it + node.id }
                    try {
                        profiles.recordLatency(latencyTester.measure(node, settings.value.pingMode))
                    } finally {
                        singlePingingNodeIds.update { it - node.id }
                    }
                }
            }

            is RemoteCommand.AddSubscription -> {
                val trimmed = command.url.trim()
                if (!trimmed.startsWith("https://", true) && !trimmed.startsWith("http://", true)) return
                val safeName = command.name?.trim()?.take(96)?.ifBlank { null }
                    ?: runCatching { URI(trimmed).host }.getOrNull().orEmpty().ifBlank { "TV subscription" }
                val sub = profiles.upsertSubscriptionSource(safeName, trimmed, null, emptyMap())
                scope.launch { subscriptionRefresher.refresh(sub) }
            }

            is RemoteCommand.RefreshSubscription -> {
                val sub = profiles.state.value.subscriptions.firstOrNull { it.id == command.subscriptionId } ?: return
                scope.launch { subscriptionRefresher.refresh(sub) }
            }

            is RemoteCommand.RemoveSubscription -> profiles.removeSubscription(command.subscriptionId)

            is RemoteCommand.SetSubscriptionEnabled ->
                profiles.setSubscriptionEnabled(command.subscriptionId, command.enabled)

            is RemoteCommand.UpdateSettings -> applySettingsPatch(command.patch)
        }
    }

    private fun applySettingsPatch(patch: com.mydrop.vpn.remote.RemoteSettingsPatch) {
        val previousRouting = settings.value.routingMode
        val cleanRemoteDns = patch.remoteDns?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }
        val cleanDirectDns = patch.directDns?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }
        val validMtu = patch.mtu?.takeIf { it in 576..9000 }
        val validInterval = patch.subscriptionUpdateMinutes?.takeIf { it in 15..10080 }

        settings.update { cur ->
            cur.copy(
                visualizer = patch.visualizer ?: cur.visualizer,
                themeMode = patch.themeMode ?: cur.themeMode,
                language = patch.language ?: cur.language,
                palette = patch.palette ?: cur.palette,
                autoConnectOnBoot = patch.autoConnectOnBoot ?: cur.autoConnectOnBoot,
                autoSelectFastest = patch.autoSelectFastest ?: cur.autoSelectFastest,
                subscriptionAutoUpdate = patch.subscriptionAutoUpdate ?: cur.subscriptionAutoUpdate,
                subscriptionUpdateMinutes = validInterval ?: cur.subscriptionUpdateMinutes,
                routingMode = patch.routingMode ?: cur.routingMode,
                providerRouting = patch.providerRouting ?: cur.providerRouting,
                bypassLan = patch.bypassLan ?: cur.bypassLan,
                blockAds = patch.blockAds ?: cur.blockAds,
                blockQuic = patch.blockQuic ?: cur.blockQuic,
                enableIpv6 = patch.enableIpv6 ?: cur.enableIpv6,
                hijackDns = patch.hijackDns ?: cur.hijackDns,
                dnsFallback = patch.dnsFallback ?: cur.dnsFallback,
                remoteDns = cleanRemoteDns ?: cur.remoteDns,
                directDns = cleanDirectDns ?: cur.directDns,
                mtu = validMtu ?: cur.mtu,
            )
        }

        if (patch.routingMode != null && patch.routingMode != previousRouting && tunnel.state.value.isActive) {
            profiles.selectedNode()?.let { node ->
                launcher.connectTo(node, "remote routing mode changed")
            }
        }
    }

    private suspend fun connect() {
        _notice.value = ""
        when (val outcome = launcher.connect()) {
            is ConnectOutcome.Started -> _needsConsent.value = false
            is ConnectOutcome.NeedsConsent -> {
                _needsConsent.value = true
                _consentRequests.tryEmit(outcome.intent)
            }
            is ConnectOutcome.Rejected -> {
                _needsConsent.value = false
                _notice.value = outcome.reason
            }
        }
    }

    /** Called by the television's own screen once the owner has answered the system dialog. */
    fun consentResolved(granted: Boolean) {
        _needsConsent.value = !granted
    }
}

private fun AppSettings.toRemoteSettings(): RemoteSettings = RemoteSettings(
    visualizer = visualizer,
    themeMode = themeMode,
    language = language,
    palette = palette,
    autoConnectOnBoot = autoConnectOnBoot,
    autoSelectFastest = autoSelectFastest,
    subscriptionAutoUpdate = subscriptionAutoUpdate,
    subscriptionUpdateMinutes = subscriptionUpdateMinutes,
    routingMode = routingMode,
    providerRouting = providerRouting,
    bypassLan = bypassLan,
    blockAds = blockAds,
    blockQuic = blockQuic,
    enableIpv6 = enableIpv6,
    hijackDns = hijackDns,
    dnsFallback = dnsFallback,
    remoteDns = remoteDns,
    directDns = directDns,
    mtu = mtu,
)

private val BRACKETED_IPV6_PATTERN = Regex("""\[([0-9a-fA-F:.]+)(?:%[^\]\s]+)?\](?::\d{1,5})?""")
private val IPV4_PORT_PATTERN = Regex("""\b((?:\d{1,3}\.){3}\d{1,3})(?:\.(?=:\d{1,5}\b))?(?::\d{1,5})?\b""")
private val BARE_IPV6_PATTERN = Regex(
    """(?<![A-Za-z0-9_])((?:[0-9a-fA-F][0-9a-fA-F:]++|::[0-9a-fA-F:]*+)(?:\.(?:\d{1,3}\.){2}\d{1,3})?+)(?:%[A-Za-z0-9_.-]+)?+(?::\d{1,5})?+(:)?(?![A-Za-z0-9_:])""",
)
private val HOST_PORT_PATTERN = Regex(
    """\b(?:(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z0-9-]{2,63}|localhost)\.?:\d{1,5}\b""",
    RegexOption.IGNORE_CASE,
)

/** Redacts IPv4, IPv6, and `host:port` endpoints before emitting [RemoteSnapshot.message]. */
internal fun sanitizeRemoteMessage(raw: String): String {
    var sanitized = BRACKETED_IPV6_PATTERN.replace(raw) { match ->
        if (isNumericAddress(match.groupValues[1])) "[redacted]" else match.value
    }
    sanitized = BARE_IPV6_PATTERN.replace(sanitized) { match ->
        val rawToken = match.groupValues[1]
        val suffixColon = match.groupValues[2].isNotEmpty()
        val hasTrailingDelimiterColon = suffixColon ||
            (rawToken.endsWith(':') && (!rawToken.endsWith("::") || rawToken.endsWith(":::")))
        val token = if (!suffixColon && hasTrailingDelimiterColon) rawToken.dropLast(1) else rawToken
        if (isValidBareIpv6Token(token)) {
            if (hasTrailingDelimiterColon) "[redacted]:" else "[redacted]"
        } else {
            match.value
        }
    }
    sanitized = IPV4_PORT_PATTERN.replace(sanitized) { match ->
        if (isNumericAddress(match.groupValues[1])) "[redacted]" else match.value
    }
    return HOST_PORT_PATTERN.replace(sanitized, "[redacted]")
}

private fun isValidBareIpv6Token(token: String): Boolean {
    if (token.count { it == ':' } < 2) return false
    if (isNumericAddress(token)) return true
    if (token.endsWith("::")) return false
    val lastColon = token.lastIndexOf(':')
    if (lastColon <= 0) return false
    val addrPart = token.substring(0, lastColon)
    val portPart = token.substring(lastColon + 1)
    if (portPart.isEmpty() || portPart.length > 5 || !portPart.all { it in '0'..'9' }) return false
    val port = portPart.toIntOrNull() ?: return false
    return port in 1..65535 && addrPart.count { it == ':' } >= 2 && isNumericAddress(addrPart)
}
