package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.remote.REMOTE_PAIRING_LIFETIME_MILLIS
import com.mydrop.vpn.remote.REMOTE_VERSION
import com.mydrop.vpn.remote.RemoteBeacon
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteCrypto
import com.mydrop.vpn.remote.RemoteInvite
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemotePeer
import com.mydrop.vpn.remote.RemoteServer
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteTunnelState
import com.mydrop.vpn.remote.localAddress
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
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
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
            )
        }
        NodeList(nodes.hashCode(), nodes)
    }.stateIn(scope, SharingStarted.Eagerly, NodeList(0, emptyList()))

    val snapshot: StateFlow<RemoteSnapshot> = combine(
        combine(profiles.state, nodeList) { profile, list -> profile.selectedNodeId to list },
        tunnel.state,
        tunnel.traffic,
        _needsConsent,
        _notice,
    ) { (selectedNodeId, list), vpn, traffic, consent, notice ->
        RemoteSnapshot(
            hostName = store.identity().name,
            state = when (vpn) {
                is VpnState.Connected -> RemoteTunnelState.On
                is VpnState.Connecting -> RemoteTunnelState.Warming
                VpnState.Disconnecting -> RemoteTunnelState.Stopping
                is VpnState.Failed -> RemoteTunnelState.Failed
                VpnState.Disconnected -> RemoteTunnelState.Off
            },
            message = (vpn as? VpnState.Failed)?.message ?: notice,
            needsConsentOnTv = consent && !vpn.isActive,
            selectedNodeId = selectedNodeId,
            connectedAtEpochMillis = (vpn as? VpnState.Connected)?.connectedAtEpochMillis ?: 0L,
            downloadBytesPerSecond = traffic.downloadBytesPerSecond,
            uploadBytesPerSecond = traffic.uploadBytesPerSecond,
            // The list's own content hash, so "has this changed" needs nothing kept beside it.
            // A collision would cost one stale list until the next real change, which is a price
            // worth the absence of a revision counter that two writers could disagree about.
            nodesRevision = list.revision,
            nodes = list.nodes,
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
