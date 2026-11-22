package com.mydrop.vpn.ui.screens.remote

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.Palette
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SubscriptionUserInfo
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.data.RemoteConsoleState
import com.mydrop.vpn.data.RemoteLinkState
import com.mydrop.vpn.remote.REMOTE_PORT
import com.mydrop.vpn.remote.RemoteBond
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteSettings
import com.mydrop.vpn.remote.RemoteSettingsPatch
import com.mydrop.vpn.remote.RemoteSighting
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteSubscription
import com.mydrop.vpn.remote.RemoteTunnelState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug-only in-memory TV remote sandbox for previewing every remote screen, tab, sheet and state
 * without a physical Android TV on the local network.
 */
internal class DebugRemoteSandbox(
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RemoteConsoleState> = _state.asStateFlow()

    private var trafficJob: Job? = null
    private var open = false

    fun open(hostId: String? = null) {
        open = true
        _state.update { cur ->
            val target = (hostId?.let { id -> cur.bonds.firstOrNull { it.hostId == id } }
                ?: cur.active?.takeIf { a -> cur.bonds.any { it.hostId == a.hostId } }
                ?: cur.bonds.firstOrNull())
            if (target == null) {
                cur.copy(
                    active = null,
                    link = RemoteLinkState.Idle,
                    snapshot = null,
                    nodes = emptyList(),
                    subscriptions = emptyList(),
                )
            } else {
                cur.copy(
                    active = target,
                    link = RemoteLinkState.Online,
                    snapshot = cur.cachedSnapshots[target.hostId],
                    nodes = cur.cachedNodes[target.hostId].orEmpty(),
                    subscriptions = cur.cachedSubscriptions[target.hostId].orEmpty(),
                )
            }
        }
        startTrafficTicker()
    }

    fun close() {
        open = false
        trafficJob?.cancel()
        trafficJob = null
    }

    fun reconnect() {
        val activeId = _state.value.active?.hostId ?: return
        _state.update { it.copy(link = RemoteLinkState.Connecting) }
        scope.launch {
            delay(450)
            if (open) open(activeId)
        }
    }

    fun rename(hostId: String, alias: String) {
        _state.update { cur ->
            val updatedBonds = cur.bonds.map { b ->
                if (b.hostId == hostId) b.copy(alias = alias.trim()) else b
            }
            cur.copy(
                bonds = updatedBonds,
                active = cur.active?.let { a -> updatedBonds.firstOrNull { it.hostId == a.hostId } },
            )
        }
    }

    fun forget(hostId: String) {
        _state.update { cur ->
            val remaining = cur.bonds.filterNot { it.hostId == hostId }
            val nextActive = if (cur.active?.hostId == hostId) remaining.firstOrNull() else cur.active
            cur.copy(
                bonds = remaining,
                active = nextActive,
                link = if (nextActive != null && open) RemoteLinkState.Online else RemoteLinkState.Idle,
                snapshot = nextActive?.let { cur.cachedSnapshots[it.hostId] },
                nodes = nextActive?.let { cur.cachedNodes[it.hostId].orEmpty() }.orEmpty(),
                subscriptions = nextActive?.let { cur.cachedSubscriptions[it.hostId].orEmpty() }.orEmpty(),
                cachedSnapshots = cur.cachedSnapshots - hostId,
                cachedNodes = cur.cachedNodes - hostId,
                cachedSubscriptions = cur.cachedSubscriptions - hostId,
            )
        }
    }

    fun reset() {
        val wasOpen = open
        _state.value = initialState()
        if (wasOpen) open()
    }

    fun send(command: RemoteCommand) {
        val hostId = _state.value.active?.hostId ?: return
        when (command) {
            RemoteCommand.Look -> Unit

            RemoteCommand.Connect -> {
                updateHostSnapshot(hostId) { snap ->
                    snap.copy(
                        state = RemoteTunnelState.Warming,
                        message = "",
                        downloadBytesPerSecond = 0L,
                        uploadBytesPerSecond = 0L,
                    )
                }
                scope.launch {
                    delay(650)
                    updateHostSnapshot(hostId) { snap ->
                        if (snap.state != RemoteTunnelState.Warming) {
                            snap
                        } else {
                            snap.copy(
                                state = RemoteTunnelState.On,
                                connectedAtEpochMillis = System.currentTimeMillis(),
                                downloadBytesPerSecond = 1_450_000L,
                                uploadBytesPerSecond = 210_000L,
                            )
                        }
                    }
                }
            }

            RemoteCommand.Disconnect -> {
                updateHostSnapshot(hostId) { snap ->
                    snap.copy(
                        state = RemoteTunnelState.Off,
                        connectedAtEpochMillis = 0L,
                        downloadBytesPerSecond = 0L,
                        uploadBytesPerSecond = 0L,
                    )
                }
            }

            is RemoteCommand.Select -> {
                updateHostSnapshot(hostId) { snap ->
                    snap.copy(selectedNodeId = command.nodeId)
                }
            }

            is RemoteCommand.PingNode -> pingNodes(hostId, listOf(command.nodeId))

            is RemoteCommand.PingNodes -> {
                val ids = command.nodeIds.ifEmpty {
                    _state.value.cachedNodes[hostId].orEmpty().map { it.id }
                }
                pingNodes(hostId, ids)
            }

            is RemoteCommand.AddSubscription -> {
                val id = "demo-sub-${UUID.randomUUID().toString().take(6)}"
                val host = runCatching { java.net.URI(command.url.trim()).host }.getOrNull()
                    ?.removePrefix("www.")
                    .orEmpty()
                val title = command.name?.takeIf(String::isNotBlank) ?: host.ifBlank { "Demo Sub" }
                val newSub = RemoteSubscription(
                    id = id,
                    name = title,
                    remoteTitle = title,
                    urlHost = host.ifBlank { "demo.example" },
                    enabled = true,
                    nodeCount = 2,
                    lastUpdatedEpochMillis = System.currentTimeMillis(),
                )
                val addedNodes = listOf(
                    RemoteNode(
                        id = "$id-de",
                        name = "${WorldMap.flagOf("DE")} $title · Frankfurt",
                        group = title,
                        latencyMillis = 39,
                        protocol = Protocol.VLESS,
                        badges = listOf("REALITY", "Vision"),
                        subscriptionId = id,
                    ),
                    RemoteNode(
                        id = "$id-fi",
                        name = "${WorldMap.flagOf("FI")} $title · Helsinki",
                        group = title,
                        latencyMillis = 28,
                        protocol = Protocol.HYSTERIA2,
                        badges = listOf("TLS"),
                        subscriptionId = id,
                    ),
                )
                updateHostData(
                    hostId = hostId,
                    transformNodes = { it + addedNodes },
                    transformSubs = { it + newSub },
                )
            }

            is RemoteCommand.RefreshSubscription -> {
                updateHostData(
                    hostId = hostId,
                    transformSubs = { subs ->
                        subs.map { s ->
                            if (s.id == command.subscriptionId) s.copy(refreshing = true, lastError = null) else s
                        }
                    },
                )
                scope.launch {
                    delay(700)
                    updateHostData(
                        hostId = hostId,
                        transformSubs = { subs ->
                            subs.map { s ->
                                if (s.id == command.subscriptionId) {
                                    s.copy(refreshing = false, lastUpdatedEpochMillis = System.currentTimeMillis())
                                } else {
                                    s
                                }
                            }
                        },
                    )
                }
            }

            is RemoteCommand.RemoveSubscription -> {
                updateHostData(
                    hostId = hostId,
                    transformNodes = { nodes -> nodes.filterNot { it.subscriptionId == command.subscriptionId } },
                    transformSubs = { subs -> subs.filterNot { it.id == command.subscriptionId } },
                )
            }

            is RemoteCommand.SetSubscriptionEnabled -> {
                updateHostData(
                    hostId = hostId,
                    transformSubs = { subs ->
                        subs.map { s ->
                            if (s.id == command.subscriptionId) s.copy(enabled = command.enabled) else s
                        }
                    },
                )
            }

            is RemoteCommand.UpdateSettings -> applySettingsPatch(hostId, command.patch)
        }
    }

    private fun pingNodes(hostId: String, nodeIds: List<String>) {
        if (nodeIds.isEmpty()) return
        val idSet = nodeIds.toSet()
        updateHostSnapshot(hostId) { snap ->
            snap.copy(pingingNodeIds = snap.pingingNodeIds + idSet)
        }
        scope.launch {
            delay(550)
            updateHostData(
                hostId = hostId,
                transformNodes = { nodes ->
                    nodes.map { node ->
                        if (node.id in idSet) {
                            val jitter = ((System.currentTimeMillis() + node.id.hashCode()) % 17).toInt()
                            val base = node.latencyMillis ?: 64
                            val nextMs = (base + jitter - 8).coerceIn(18, 290)
                            node.copy(latencyMillis = nextMs, latencyFailed = false)
                        } else {
                            node
                        }
                    }
                },
                transformSnapshot = { snap ->
                    snap.copy(pingingNodeIds = snap.pingingNodeIds - idSet)
                },
            )
        }
    }

    private fun applySettingsPatch(hostId: String, patch: RemoteSettingsPatch) {
        _state.update { cur ->
            val snap = cur.cachedSnapshots[hostId] ?: return@update cur
            val currentSettings = snap.settings ?: RemoteSettings()
            val updatedSettings = currentSettings.copy(
                visualizer = patch.visualizer ?: currentSettings.visualizer,
                themeMode = patch.themeMode ?: currentSettings.themeMode,
                language = patch.language ?: currentSettings.language,
                palette = patch.palette ?: currentSettings.palette,
                autoConnectOnBoot = patch.autoConnectOnBoot ?: currentSettings.autoConnectOnBoot,
                autoSelectFastest = patch.autoSelectFastest ?: currentSettings.autoSelectFastest,
                subscriptionAutoUpdate = patch.subscriptionAutoUpdate ?: currentSettings.subscriptionAutoUpdate,
                subscriptionUpdateMinutes = patch.subscriptionUpdateMinutes ?: currentSettings.subscriptionUpdateMinutes,
                routingMode = patch.routingMode ?: currentSettings.routingMode,
                providerRouting = patch.providerRouting ?: currentSettings.providerRouting,
                bypassLan = patch.bypassLan ?: currentSettings.bypassLan,
                blockAds = patch.blockAds ?: currentSettings.blockAds,
                blockQuic = patch.blockQuic ?: currentSettings.blockQuic,
                enableIpv6 = patch.enableIpv6 ?: currentSettings.enableIpv6,
                hijackDns = patch.hijackDns ?: currentSettings.hijackDns,
                dnsFallback = patch.dnsFallback ?: currentSettings.dnsFallback,
                remoteDns = patch.remoteDns ?: currentSettings.remoteDns,
                directDns = patch.directDns ?: currentSettings.directDns,
                mtu = patch.mtu ?: currentSettings.mtu,
                tlsFingerprint = patch.tlsFingerprint ?: currentSettings.tlsFingerprint,
                fragmentMode = patch.fragmentMode ?: currentSettings.fragmentMode,
                fragmentScope = patch.fragmentScope ?: currentSettings.fragmentScope,
                fragmentLength = patch.fragmentLength
                    ?.let { AppSettings.normalizeRange(it, AppSettings.MIN_FRAGMENT_LENGTH, AppSettings.MAX_FRAGMENT_LENGTH) }
                    ?: currentSettings.fragmentLength,
                fragmentInterval = patch.fragmentInterval
                    ?.let { AppSettings.normalizeRange(it, AppSettings.MIN_FRAGMENT_INTERVAL, AppSettings.MAX_FRAGMENT_INTERVAL) }
                    ?: currentSettings.fragmentInterval,
                fragmentMaxChunks = patch.fragmentMaxChunks?.let { raw ->
                    if (raw.isBlank()) "" else AppSettings.normalizeRange(
                        raw,
                        AppSettings.MIN_FRAGMENT_MAX_CHUNKS,
                        AppSettings.MAX_FRAGMENT_MAX_CHUNKS,
                    )
                } ?: currentSettings.fragmentMaxChunks,
            )
            val updatedSnap = snap.copy(settings = updatedSettings)
            val updatedBonds = cur.bonds.map { b ->
                if (b.hostId == hostId) {
                    b.copy(
                        lastPalette = updatedSettings.palette,
                        lastVisualizer = updatedSettings.visualizer,
                    )
                } else {
                    b
                }
            }
            val updatedSnapshots = cur.cachedSnapshots + (hostId to updatedSnap)
            cur.copy(
                bonds = updatedBonds,
                active = cur.active?.let { a -> updatedBonds.firstOrNull { it.hostId == a.hostId } },
                snapshot = if (cur.active?.hostId == hostId) updatedSnap else cur.snapshot,
                cachedSnapshots = updatedSnapshots,
            )
        }
    }

    private fun updateHostSnapshot(
        hostId: String,
        transform: (RemoteSnapshot) -> RemoteSnapshot,
    ) {
        updateHostData(hostId = hostId, transformSnapshot = transform)
    }

    private fun updateHostData(
        hostId: String,
        transformSnapshot: (RemoteSnapshot) -> RemoteSnapshot = { it },
        transformNodes: (List<RemoteNode>) -> List<RemoteNode> = { it },
        transformSubs: (List<RemoteSubscription>) -> List<RemoteSubscription> = { it },
    ) {
        _state.update { cur ->
            val oldSnap = cur.cachedSnapshots[hostId] ?: return@update cur
            val newSnap = transformSnapshot(oldSnap)
            val newNodes = transformNodes(cur.cachedNodes[hostId].orEmpty())
            val newSubs = transformSubs(cur.cachedSubscriptions[hostId].orEmpty())
            val isActive = cur.active?.hostId == hostId
            cur.copy(
                snapshot = if (isActive) newSnap else cur.snapshot,
                nodes = if (isActive) newNodes else cur.nodes,
                subscriptions = if (isActive) newSubs else cur.subscriptions,
                cachedSnapshots = cur.cachedSnapshots + (hostId to newSnap),
                cachedNodes = cur.cachedNodes + (hostId to newNodes),
                cachedSubscriptions = cur.cachedSubscriptions + (hostId to newSubs),
            )
        }
    }

    private fun startTrafficTicker() {
        if (trafficJob?.isActive == true) return
        trafficJob = scope.launch {
            var tick = 0
            while (isActive && open) {
                delay(1000)
                tick++
                _state.update { cur ->
                    val updatedSnapshots = cur.cachedSnapshots.mapValues { (_, snap) ->
                        if (snap.state == RemoteTunnelState.On) {
                            val wave = abs(sin(tick * 0.55))
                            val dl = (650_000L + (wave * 4_800_000.0).toLong())
                            val ul = (110_000L + (abs(sin(tick * 0.35 + 1.1)) * 780_000.0).toLong())
                            snap.copy(downloadBytesPerSecond = dl, uploadBytesPerSecond = ul)
                        } else {
                            snap
                        }
                    }
                    val activeId = cur.active?.hostId
                    cur.copy(
                        cachedSnapshots = updatedSnapshots,
                        snapshot = activeId?.let(updatedSnapshots::get) ?: cur.snapshot,
                    )
                }
            }
        }
    }

    companion object {
        private const val HOST_LIVING = "demo-tv-living"
        private const val HOST_BEDROOM = "demo-tv-bedroom"

        private fun initialState(): RemoteConsoleState {
            val now = System.currentTimeMillis()
            val livingBond = RemoteBond(
                hostId = HOST_LIVING,
                hostName = "Sony Bravia XR",
                hostPublicKey = "demo-pub-living",
                peerId = "demo-peer-1",
                secret = "demo-secret-1",
                lastHost = "192.168.1.42",
                lastPort = REMOTE_PORT,
                alias = "Гостиная · Android TV",
                lastPalette = Palette.Glacier,
                lastVisualizer = Visualizer.Waves,
            )
            val bedroomBond = RemoteBond(
                hostId = HOST_BEDROOM,
                hostName = "Google TV Streamer",
                hostPublicKey = "demo-pub-bedroom",
                peerId = "demo-peer-2",
                secret = "demo-secret-2",
                lastHost = "192.168.1.57",
                lastPort = REMOTE_PORT,
                alias = "Спальня · Google TV",
                lastPalette = Palette.Lilac,
                lastVisualizer = Visualizer.Planet,
            )

            val subsLiving = listOf(
                RemoteSubscription(
                    id = "demo-sub-1",
                    name = "Yumi Cloud",
                    remoteTitle = "Yumi Cloud · Premium",
                    urlHost = "sub.yumi.example",
                    enabled = true,
                    nodeCount = 8,
                    lastUpdatedEpochMillis = now - 18 * 60_000L,
                    userInfo = SubscriptionUserInfo(
                        uploadBytes = 8_400_000_000L,
                        downloadBytes = 41_200_000_000L,
                        totalBytes = 200_000_000_000L,
                        expiresAtEpochSeconds = (now / 1000L) + 21L * 86_400L,
                    ),
                ),
                RemoteSubscription(
                    id = "demo-sub-2",
                    name = "Backup Edge",
                    remoteTitle = "Backup Edge",
                    urlHost = "edge.backup.example",
                    enabled = true,
                    nodeCount = 3,
                    lastUpdatedEpochMillis = now - 3 * 3_600_000L,
                ),
            )

            val nodesLiving = listOf(
                RemoteNode(
                    id = "demo-de-1",
                    name = "${WorldMap.flagOf("DE")} Германия · Берлин",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 34,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY", "Vision"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-nl-1",
                    name = "${WorldMap.flagOf("NL")} Нидерланды · Амстердам",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 41,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY", "gRPC"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-fi-1",
                    name = "${WorldMap.flagOf("FI")} Финляндия · Хельсинки",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 27,
                    protocol = Protocol.HYSTERIA2,
                    badges = listOf("TLS"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-gb-1",
                    name = "${WorldMap.flagOf("GB")} Великобритания · Лондон",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 58,
                    protocol = Protocol.TROJAN,
                    badges = listOf("TLS", "WS"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-us-1",
                    name = "${WorldMap.flagOf("US")} США · Нью-Йорк",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 128,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-tr-1",
                    name = "${WorldMap.flagOf("TR")} Турция · Стамбул",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 74,
                    protocol = Protocol.VLESS,
                    badges = listOf("TLS"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-ru-1",
                    name = "${WorldMap.flagOf("RU")} Россия · Москва (Relay)",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = 19,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-jp-1",
                    name = "${WorldMap.flagOf("JP")} Япония · Токио",
                    group = "Yumi Cloud · Premium",
                    latencyMillis = null,
                    latencyFailed = true,
                    protocol = Protocol.HYSTERIA2,
                    badges = listOf("TLS"),
                    subscriptionId = "demo-sub-1",
                ),
                RemoteNode(
                    id = "demo-sg-1",
                    name = "${WorldMap.flagOf("SG")} Сингапур · Edge",
                    group = "Backup Edge",
                    latencyMillis = 164,
                    protocol = Protocol.TROJAN,
                    badges = listOf("TLS"),
                    subscriptionId = "demo-sub-2",
                ),
                RemoteNode(
                    id = "demo-se-1",
                    name = "${WorldMap.flagOf("SE")} Швеция · Стокгольм",
                    group = "Backup Edge",
                    latencyMillis = null,
                    protocol = Protocol.VLESS,
                    badges = listOf("REALITY"),
                    subscriptionId = "demo-sub-2",
                ),
                RemoteNode(
                    id = "demo-manual-1",
                    name = "Home WireGuard Gateway",
                    group = "",
                    latencyMillis = 22,
                    protocol = Protocol.WIREGUARD,
                    badges = emptyList(),
                    subscriptionId = null,
                ),
            )

            val subsBedroom = listOf(subsLiving.first())
            val nodesBedroom = nodesLiving.take(6)

            val snapLiving = RemoteSnapshot(
                hostName = livingBond.hostName,
                state = RemoteTunnelState.On,
                selectedNodeId = "demo-de-1",
                connectedAtEpochMillis = now - 37 * 60_000L,
                downloadBytesPerSecond = 2_340_000L,
                uploadBytesPerSecond = 340_000L,
                nodesRevision = 1,
                subscriptionsRevision = 1,
                settings = RemoteSettings(
                    visualizer = Visualizer.Waves,
                    palette = Palette.Glacier,
                    routingMode = RoutingMode.Rules,
                    blockAds = true,
                ),
            )
            val snapBedroom = RemoteSnapshot(
                hostName = bedroomBond.hostName,
                state = RemoteTunnelState.Off,
                selectedNodeId = "demo-nl-1",
                nodesRevision = 1,
                subscriptionsRevision = 1,
                settings = RemoteSettings(
                    visualizer = Visualizer.Planet,
                    palette = Palette.Lilac,
                    routingMode = RoutingMode.Global,
                ),
            )

            val sightings = listOf(
                RemoteSighting(
                    hostId = "demo-tv-kitchen",
                    hostName = "Кухня · Mi Box S",
                    host = "192.168.1.84",
                    port = REMOTE_PORT,
                ),
            )

            return RemoteConsoleState(
                bonds = listOf(livingBond, bedroomBond),
                sightings = sightings,
                active = livingBond,
                link = RemoteLinkState.Online,
                snapshot = snapLiving,
                nodes = nodesLiving,
                subscriptions = subsLiving,
                cachedSnapshots = mapOf(
                    HOST_LIVING to snapLiving,
                    HOST_BEDROOM to snapBedroom,
                ),
                cachedNodes = mapOf(
                    HOST_LIVING to nodesLiving,
                    HOST_BEDROOM to nodesBedroom,
                ),
                cachedSubscriptions = mapOf(
                    HOST_LIVING to subsLiving,
                    HOST_BEDROOM to subsBedroom,
                ),
            )
        }
    }
}
