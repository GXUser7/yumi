package com.mydrop.vpn.data

import android.content.Intent
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

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
     * Emits the new interface name each time the tunnel moves between physical networks.
     *
     * Empty by default, because a controller that does not run a real tunnel has no way to know
     * and no business pretending. [FailoverWatchdog] treats silence as "nothing changed", which is
     * the correct reading for the simulated one.
     */
    val handovers: Flow<String> get() = emptyFlow()

    /**
     * Whether the device has a default network at all.
     *
     * True by default: a controller that cannot know must not claim the phone is offline, because
     * [FailoverWatchdog] stops acting on probes while this is false and a wrong `false` would
     * disable failover entirely.
     */
    val hasNetwork: StateFlow<Boolean> get() = AlwaysOnline

    companion object {
        private val AlwaysOnline = MutableStateFlow(true)
    }

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
