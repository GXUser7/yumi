package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import com.mydrop.vpn.vpn.MyDropVpnService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the real tunnel. State and counters are owned by [MyDropVpnService] — the service is
 * the only component that knows whether the core is actually running, so it publishes and this
 * class merely forwards commands to it.
 */
class SingBoxTunnelController(
    private val context: Context,
    private val configs: TunnelConfigBuilder,
    private val logs: LogRepository,
) : TunnelController {

    override val state: StateFlow<VpnState> = MyDropVpnService.state
    override val traffic: StateFlow<TrafficStats> = MyDropVpnService.traffic

    override val coreDelays: StateFlow<Map<String, Int>> = MyDropVpnService.coreDelays

    override fun requestUrlTest(): Boolean = runCatching {
        check(state.value is VpnState.Connected) { "no tunnel to measure through" }
        MyDropVpnService.forgetCoreDelays()
        val client = Libbox.newStandaloneCommandClient()
        try {
            // The group, not one server. sing-box answers a URL test on any outbound group by
            // walking its members and pulling the test page through each — so one request covers
            // every candidate the tunnel could move onto, measured the way the tunnel is used.
            client.urlTest(SingBoxConfigFactory.PROXY_TAG)
        } finally {
            runCatching { client.disconnect() }
        }
        true
    }.getOrElse { error ->
        logs.trace("YumiCore", "urlTest refused: ${error.message}")
        false
    }
    override val isReal: Boolean = true
    override val handovers: Flow<String> = MyDropVpnService.handovers
    override val wakeups: Flow<Unit> = MyDropVpnService.wakeups
    override val hasNetwork: StateFlow<Boolean> = MyDropVpnService.hasNetwork
    override val transport: StateFlow<NetworkTransport> = MyDropVpnService.transport
    override val screenOn: StateFlow<Boolean> = MyDropVpnService.screenOn

    /**
     * Building a configuration extracts the bundled rule-sets and reads the settings store, and
     * [connect] is called straight from a tap handler — on the main thread, on a screen that is
     * animating. The lock keeps two quick taps in the server list in the order they were made.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildLock = Mutex()

    /**
     * Non-null when the user has not yet granted VPN consent. The returned intent has to be
     * launched from an Activity, so the decision surfaces to the UI rather than being handled
     * here.
     */
    override fun permissionIntent(): Intent? = VpnService.prepare(context)

    override fun connect(node: ProxyNode, reason: String) {
        scope.launch {
            buildLock.withLock {
                val config = configs.build(node) ?: return@withLock
                logs.trace("YumiCore", "connect requested by $reason -> ${node.name}")
                logs.info(R.string.log_connecting_to, node.name, node.protocol.label, node.address)
                MyDropVpnService.start(context, config, node.id, node.name)
            }
        }
    }

    /**
     * Asks the running core to point its selector at another member.
     *
     * A standalone command client rather than the one the service keeps: that one exists to hold
     * the status and log subscriptions alive, and borrowing it for a one-shot call would tie this
     * to the service's lifecycle for no reason. This opens a connection, says one thing and closes.
     */
    override fun selectOutbound(node: ProxyNode): Boolean = runCatching {
        check(state.value is VpnState.Connected) { "no tunnel to switch inside" }
        val client = Libbox.newStandaloneCommandClient()
        try {
            client.selectOutbound(
                SingBoxConfigFactory.PROXY_TAG,
                SingBoxConfigFactory.nodeTag(node.id),
            )
        } finally {
            runCatching { client.disconnect() }
        }
        // Before returning, so nobody can observe a tunnel that has moved and a screen that has not.
        MyDropVpnService.noteNode(node.id, node.name)
        logs.trace("YumiCore", "switched to ${node.name} without restarting the core")
        true
    }.getOrElse { error ->
        // Not an error worth showing: every caller has a working fallback, and the reasons this
        // fails are ordinary — the tunnel is down, or the server is not in the group.
        logs.trace("YumiCore", "selectOutbound refused: ${error.message}")
        false
    }

    override fun disconnect() {
        MyDropVpnService.stop(context)
    }
}
