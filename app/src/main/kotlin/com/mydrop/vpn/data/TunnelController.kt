package com.mydrop.vpn.data

import android.content.Intent
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the UI and whatever actually moves packets.
 *
 * Phase 1 ships [SimulatedTunnelController] so the interface, the state machine and every
 * animation driven by them can be exercised. Phase 2 adds a sing-box implementation behind the
 * same contract; nothing above this interface changes when it lands.
 */
interface TunnelController {
    val state: StateFlow<VpnState>
    val traffic: StateFlow<TrafficStats>

    /** True when this controller really forwards traffic. Drives the "demo mode" banner. */
    val isReal: Boolean

    /**
     * Consent intent to launch before connecting, or null when no consent is needed. Must be
     * started from an Activity, so it is surfaced rather than handled inside the controller.
     */
    fun permissionIntent(): Intent? = null

    fun connect(node: ProxyNode)

    fun disconnect()
}
