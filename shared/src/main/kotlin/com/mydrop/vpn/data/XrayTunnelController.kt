package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.vpn.MyDropVpnService
import com.mydrop.vpn.vpn.XrayCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives the real tunnel. State and counters are owned by [MyDropVpnService] — the service is the
 * only component that knows whether the core is actually running, so it publishes and this class
 * merely forwards commands to it.
 */
class XrayTunnelController(
    private val context: Context,
    private val configs: TunnelConfigBuilder,
    private val logs: LogRepository,
) : TunnelController {

    override val state: StateFlow<VpnState> = MyDropVpnService.state
    override val traffic: StateFlow<TrafficStats> = MyDropVpnService.traffic

    override val isReal: Boolean = true
    override val handovers: Flow<String> = MyDropVpnService.handovers
    override val wakeups: Flow<Unit> = MyDropVpnService.wakeups
    override val hasNetwork: StateFlow<Boolean> = MyDropVpnService.hasNetwork
    override val transport: StateFlow<NetworkTransport> = MyDropVpnService.transport
    override val screenOn: StateFlow<Boolean> = MyDropVpnService.screenOn

    /**
     * Building a configuration reads the settings store and claims a loopback port, and [connect]
     * is called straight from a tap handler — on the main thread, on a screen that is animating.
     * The lock keeps two quick taps in the server list in the order they were made.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildLock = Mutex()

    /**
     * Non-null when the user has not yet granted VPN consent. The returned intent has to be
     * launched from an Activity, so the decision surfaces to the UI rather than being handled here.
     */
    override fun permissionIntent(): Intent? = VpnService.prepare(context)

    override fun connect(node: ProxyNode, reason: String) {
        scope.launch { connectNow(node, reason) }
    }

    override suspend fun connectNow(node: ProxyNode, reason: String) {
        buildLock.withLock {
            val document = configs.build(node) ?: return
            logs.trace(TAG, "connect requested by $reason -> ${node.name}")
            logs.info(R.string.log_connecting_to, node.name, node.protocol.label, node.address)
            MyDropVpnService.start(
                context,
                document.json,
                document.pinnedTag,
                node.id,
                node.name,
            )
        }
    }

    /**
     * Points the balancer at another server, which moves the tunnel without rebuilding it.
     *
     * sing-box called this swapping a selector; Xray calls it overriding a balancer, and the effect
     * is the same one that matters — the TUN, the DNS cache and every open connection to a server
     * that is staying up are left alone.
     */
    override fun selectOutbound(node: ProxyNode): Boolean {
        if (state.value !is VpnState.Connected) return false
        if (!XrayCore.selectOutbound(XrayConfigFactory.nodeTag(node.id))) return false
        // Before returning, so nobody can observe a tunnel that has moved and a screen that has not.
        MyDropVpnService.noteNode(node.id, node.name)
        logs.trace(TAG, "switched to ${node.name} without restarting the core")
        return true
    }

    /**
     * Times a request through each candidate, in parallel, and answers with what came back.
     *
     * The measurement the app cannot make for itself: everything it can reach from the phone
     * describes the port of a server, and a server under interference answers its port cheerfully
     * while carrying nothing.
     *
     * This replaced a considerably larger piece of machinery, and the machinery is gone rather than
     * ported. sing-box streamed its url-test results — the table filled in one server at a time and
     * was republished on each answer — so the caller had to decide when to stop waiting, and
     * deciding it wrongly handed the chooser a pool of one. (It did: a journal caught
     * `core measured 1/7 candidates` twice inside fifteen minutes, and every phone in the
     * subscription then piled onto the same replacement.) The binding measures every candidate at
     * once and returns when they have all answered or timed out, so there is no partial table to
     * mistake for a complete one.
     */
    override suspend fun measureThroughTunnel(nodes: List<ProxyNode>): Map<String, Int> {
        if (state.value !is VpnState.Connected || nodes.isEmpty()) return emptyMap()

        val byTag = nodes.associateBy { XrayConfigFactory.nodeTag(it.id) }
        val delays = withContext(Dispatchers.IO) {
            XrayCore.measureOutbounds(byTag.keys, MEASURE_TIMEOUT_MILLIS)
        }
        return delays.mapNotNull { (tag, millis) ->
            // Negative means asked and did not answer, which is a different fact from not measured
            // — and it has to stay absent rather than become a number, or a server that answered
            // nothing would be chosen for answering instantly.
            byTag[tag]?.takeIf { millis > 0 }?.let { it.id to millis }
        }.toMap()
    }

    override fun disconnect() {
        MyDropVpnService.stop(context)
    }

    private companion object {
        const val TAG = "YumiCore"

        /**
         * Per-candidate budget. They are measured in parallel, so this is how long the whole sweep
         * can take rather than how long it takes times the number of servers.
         *
         * Five seconds, matching what the core's own observatory allows itself for the same
         * request, and comfortably inside the watchdog probe interval that has to contain it.
         */
        const val MEASURE_TIMEOUT_MILLIS = 5_000
    }
}
