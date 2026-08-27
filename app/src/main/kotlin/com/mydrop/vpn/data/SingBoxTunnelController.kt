package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.vpn.MyDropVpnService
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
    override val isReal: Boolean = true
    override val handovers: Flow<String> = MyDropVpnService.handovers
    override val hasNetwork: StateFlow<Boolean> = MyDropVpnService.hasNetwork

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

    override fun disconnect() {
        MyDropVpnService.stop(context)
    }
}
