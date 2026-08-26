package com.mydrop.vpn.data

import android.content.Intent
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the UI and whatever actually moves packets.
 *
 * [SingBoxTunnelController] is the real one. [SimulatedTunnelController] implements the same
 * contract and is kept deliberately: it is the only way to walk the connect screen through every
 * state without a working server, and swapping it in is a one-line change in `AppContainer`.
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

    /**
     * @param reason who asked, for the trace. Every path here ends in the same core reload, so
     *   without it a switch made by the watchdog, by a tap and by a settings change are one line.
     */
    fun connect(node: ProxyNode, reason: String)

    fun disconnect()
}
