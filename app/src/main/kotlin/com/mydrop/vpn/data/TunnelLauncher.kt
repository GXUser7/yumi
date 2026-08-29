package com.mydrop.vpn.data

import android.content.Intent
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import kotlinx.coroutines.withTimeoutOrNull

/** What came of asking for the tunnel. */
sealed interface ConnectOutcome {
    data class Started(val node: ProxyNode) : ConnectOutcome

    /** Consent has never been granted, or was revoked. Only an Activity can ask for it. */
    data class NeedsConsent(val intent: Intent) : ConnectOutcome

    data class Rejected(val reason: String) : ConnectOutcome
}

/**
 * The one place that answers "connect" — which server, and whether the tunnel may come up at all.
 *
 * Three callers need that answer and only one of them is the UI: the Quick Settings tile has no
 * ViewModel, and the boot receiver has no screen to report to. Keeping the decision here is what
 * makes "auto-connect on boot" and "pick the fastest" mean the same thing wherever the request
 * comes from, instead of each entry point growing its own half of the rules.
 */
class TunnelLauncher(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val latencyTester: LatencyTester,
    private val logs: LogRepository,
    private val strings: Strings,
) {

    suspend fun connect(): ConnectOutcome {
        val node = resolveNode()
            ?: return ConnectOutcome.Rejected(strings.get(R.string.error_no_server_selected))

        tunnel.permissionIntent()?.let { return ConnectOutcome.NeedsConsent(it) }

        connectTo(node, "launcher.connect")
        return ConnectOutcome.Started(node)
    }

    /** Connects to a server the caller has already chosen, e.g. a tap in the server list. */
    fun connectTo(node: ProxyNode, reason: String = "connectTo") = tunnel.connect(node, reason)

    /**
     * Moves the tunnel onto [node] and records it as the selection, so the server list, the
     * connect screen and the notification all name the server that is really carrying traffic.
     */
    /**
     * Moves the tunnel onto [node], the cheapest way that works.
     *
     * The selector inside the core can usually do it by swapping a pointer, which leaves the TUN,
     * the DNS cache and every open connection alone. It cannot when the tunnel is down, or when
     * the server is not one of the group's members — a switch to somewhere outside the pool the
     * config was built around. Then the tunnel is rebuilt, which always works and costs the user
     * every connection they had open.
     */
    fun switchTo(node: ProxyNode, reloadConfig: Boolean = false) {
        profiles.selectNode(node.id)
        // A pointer swap moves the tunnel between servers the running core already holds. It
        // cannot deliver anything else — and the resolver is not a server, it is baked into the
        // document the core read at startup. Asking the selector to point at the node it is
        // already pointing at succeeds, returns true, and the caller walks away believing a new
        // configuration was installed when nothing was reloaded at all. That is how the fallback
        // resolver came to be announced in the journal on every outage and applied on none of
        // them: the file on disk changed, the core kept querying the resolver that had stopped
        // answering.
        if (!reloadConfig && tunnel.selectOutbound(node)) return
        // Rebuilding is the fallback for "the pointer could not be moved", and one of the reasons
        // it cannot be moved is that the tunnel is being torn down underneath: the command socket
        // goes away mid-call, the swap reports failure, and the rebuild below would bring back the
        // tunnel the user just asked to stop. A switch has somewhere to go only while there is
        // something to switch.
        if (!tunnel.state.value.isActive) return
        tunnel.connect(node, if (reloadConfig) "config reload" else "failover")
    }

    fun disconnect() = tunnel.disconnect()

    /**
     * The server to dial, honouring "pick the fastest". Returns null when there is nothing to
     * dial at all.
     *
     * The choice is written back to the profile, so the server list and the connect screen show
     * the server that is actually carrying traffic rather than the one the user last tapped.
     */
    suspend fun resolveNode(): ProxyNode? {
        val nodes = profiles.nodes
        if (nodes.isEmpty()) return null

        val chosen = profiles.selectedNode() ?: nodes.first()
        if (!settings.value.autoSelectFastest || nodes.size == 1) return chosen

        val fastest = fastestNode() ?: run {
            // Measuring is worth a few seconds here and nowhere else: this is the one moment the
            // user is already waiting for a tunnel, and picking "the fastest" off numbers from
            // last week is how the setting stops meaning anything.
            sweep(nodes)
            fastestNode()
        }

        if (fastest == null || fastest.id == chosen.id) return chosen
        logs.info(R.string.log_autoselect, fastest.name)
        profiles.selectNode(fastest.id)
        return fastest
    }

    // ----------------------------------------------------------- Internals

    /** Fastest server with a usable, recent measurement, or null when there is none. */
    fun fastestNode(): ProxyNode? {
        val latencies = profiles.state.value.latencies
        val freshEnough = System.currentTimeMillis() - FRESH_WINDOW_MILLIS
        return profiles.nodes
            .mapNotNull { node ->
                latencies[node.id]
                    ?.takeIf { !it.failed && it.measuredAtEpochMillis >= freshEnough }
                    ?.let { node to it.millis }
            }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Measures the list, recording results as they land and giving up on the rest when the budget
     * runs out. A large subscription measured to completion would hold the connect tap for half a
     * minute; whatever answered inside the budget is enough to choose from.
     */
    private suspend fun sweep(nodes: List<ProxyNode>) {
        logs.info(R.string.log_autoselect_measuring, nodes.size)
        withTimeoutOrNull(SWEEP_BUDGET_MILLIS) {
            latencyTester.measureAll(nodes, settings.value.pingMode) { profiles.recordLatency(it) }
        }
    }

    companion object {
        /** Past this, a measurement says more about the last café's Wi-Fi than about the server. */
        const val FRESH_WINDOW_MILLIS = 30 * 60 * 1000L

        const val SWEEP_BUDGET_MILLIS = 6_000L
    }
}
