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
     * Emits when the phone wakes up and somebody is about to use the tunnel.
     *
     * The watchdog's schedule is a coroutine delay, and a coroutine delay does not run while the
     * processor is suspended. Measured on a phone left on a desk: a check that should have run
     * every twenty seconds ran a hundred times in ten hours, with thirty-nine gaps over five
     * minutes and one of forty. That is not a bug to fix — in Doze the CPU is genuinely off, and
     * the only ways around it are a wakelock that costs the night's battery or an alarm the system
     * grants roughly once every nine minutes. Every client on the phone sleeps the same way.
     *
     * What can be fixed is the moment it is felt. Nobody minds an unchecked tunnel while the phone
     * is in a pocket; they mind the first seconds after picking it up, when the tunnel has been
     * dead since three in the morning and the app has not looked yet. So instead of beating the
     * clock, the check is hung on the event: the screen coming on, or the system leaving idle.
     *
     * Costs nothing while asleep — no alarms, no wakelocks, only a broadcast that was going to be
     * sent anyway.
     */
    val wakeups: Flow<Unit> get() = emptyFlow()

    /**
     * Moves the tunnel onto another server without restarting anything.
     *
     * The core carries a selector group wearing the tag every route and DNS rule already points
     * at, so changing which server that tag means is a pointer swap: the TUN stays, the DNS cache
     * stays, and connections the user has open are left alone. Compare [connect], which tears the
     * core down and takes every one of them with it.
     *
     * @return false when it could not be done — no tunnel, or a server that is not in the group.
     *   The caller is expected to fall back to [connect], which always works and costs more.
     */
    fun selectOutbound(nodeId: String): Boolean = false

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
