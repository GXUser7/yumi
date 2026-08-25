package com.mydrop.vpn.data

import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.FailoverChoice
import com.mydrop.vpn.core.model.FailoverGroup
import com.mydrop.vpn.core.model.FailoverPolicy
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the server the tunnel is riding on, and moves off it when it stops answering.
 *
 * This is the app's job rather than the core's, and that is a deliberate reversal. sing-box can do
 * failover itself with a `urltest` group, but that group always dials whichever member is fastest,
 * and it re-chooses only on its probe schedule — a server dying one second after a check leaves
 * every connection failing until the next one. Doing it here buys a reaction time we set, and a
 * choice we control: [FailoverChoice] picks at random among the servers that are keeping pace,
 * instead of piling every client onto the same fastest one.
 *
 * The probe works because the service excludes this app from its own tunnel
 * (`addDisallowedApplication`), so a measurement leaves the phone directly and stays truthful
 * while the tunnel itself is a black hole.
 *
 * What it detects is the server being gone: refusing connections, or no longer routed to. A server
 * that still completes a handshake while its proxy misbehaves looks alive from here and will not
 * trigger a switch.
 *
 * Every switch is provisional. The server the user picked is remembered and kept under probe, and
 * the tunnel returns to it as soon as it answers again — otherwise one bad minute silently became
 * permanent, and the only clue was that the connect screen now named a server nobody had chosen.
 */
class FailoverWatchdog(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
    private val latencyTester: LatencyTester,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {

    private var watching: Job? = null

    /**
     * The server the user chose, kept from the moment this watchdog moves the tunnel off it until
     * the tunnel is back on it. Null whenever the tunnel is riding on a choice somebody made.
     */
    private var homeNode: ProxyNode? = null

    /** The replacement this watchdog installed, so its own doing is distinguishable from a tap. */
    private var autoChosenId: String? = null

    /**
     * When this watchdog last moved the tunnel, in either direction. Every automatic switch waits
     * out [SWITCH_COOLDOWN_MILLIS] from here — see [restartWatch] for why the rate matters more
     * than the decision.
     */
    private var lastSwitchAtMillis = 0L

    /**
     * How many times the tunnel has been sent back to a given server. A server that needed
     * returning to more than [MAX_FAILBACKS] times is not recovering, it is flapping, and the
     * tunnel stops chasing it.
     */
    private val failbacks = mutableMapOf<String, Int>()

    /** Follows the tunnel: a watch runs for exactly as long as one connection lives. */
    fun start() {
        scope.launch {
            tunnel.state.collectLatest { state ->
                when (state) {
                    is VpnState.Connected -> restartWatch()
                    // A switch passes back through Connecting on its way to Connected. The way
                    // home has to survive that, or the switch this watchdog just made would erase
                    // the very thing it is meant to undo.
                    is VpnState.Connecting -> stopWatch()
                    else -> {
                        stopWatch()
                        forgetHome()
                    }
                }
            }
        }
    }

    private fun restartWatch() {
        watching?.cancel()
        watching = scope.launch {
            // A tunnel that just came up has not had time to break, and the core is still
            // settling. Probing into that only produces a false alarm.
            delay(GRACE_MILLIS)
            var failures = 0
            var recoveries = 0
            while (isActive) {
                delay(PROBE_INTERVAL_MILLIS)
                if (!settings.value.autoFailover) {
                    failures = 0
                    continue
                }
                val current = profiles.selectedNode() ?: continue

                // Choosing a server by hand is the freshest statement of intent there is, and it
                // retires whatever this watchdog was still trying to undo.
                if (autoChosenId != null && current.id != autoChosenId) forgetHome()

                // A subscription refresh retires ids, so the saved server can stop existing. Then
                // there is nowhere to go back to and the memory is only a probe against a ghost.
                val home = homeNode?.let { saved -> profiles.nodes.firstOrNull { it.id == saved.id } }
                if (homeNode != null && home == null) forgetHome()

                // Probed first, and always: the count of good probes in a row is what the
                // policy weighs against the count of bad ones.
                if (home != null) {
                    val revived = latencyTester.measure(home, settings.value.pingMode)
                    profiles.recordLatency(revived)
                    recoveries = if (revived.failed) 0 else recoveries + 1
                } else {
                    recoveries = 0
                }

                val result = latencyTester.measure(current, settings.value.pingMode)
                failures = if (result.failed) failures + 1 else 0
                if (result.failed && failures < FailoverPolicy.FAILURES_BEFORE_SWAP) {
                    logs.debug(
                        R.string.log_failover_no_answer,
                        current.name,
                        failures,
                        FailoverPolicy.FAILURES_BEFORE_SWAP,
                    )
                }

                val decision = FailoverPolicy.decide(
                    consecutiveFailures = failures,
                    consecutiveHomeRecoveries = recoveries,
                    hasHome = home != null,
                    failbacksSoFar = failbacks[home?.id] ?: 0,
                    millisSinceLastSwitch = System.currentTimeMillis() - lastSwitchAtMillis,
                )

                when (decision) {
                    // Counters are left standing: whatever the policy is waiting for, it is still
                    // true, and resetting them here would make the wait restart every probe.
                    FailoverPolicy.Decision.Hold -> Unit

                    FailoverPolicy.Decision.AbandonHome -> {
                        home ?: continue
                        recoveries = 0
                        failbacks[home.id] = FailoverPolicy.MAX_FAILBACKS + 1
                        forgetHome()
                        logs.info(R.string.log_failover_home_abandoned, home.name)
                    }

                    FailoverPolicy.Decision.ReturnHome -> {
                        home ?: continue
                        recoveries = 0
                        failbacks[home.id] = (failbacks[home.id] ?: 0) + 1
                        forgetHome()
                        logs.info(R.string.log_failover_home_back, home.name)
                        lastSwitchAtMillis = System.currentTimeMillis()
                        launcher.switchTo(home)
                    }

                    FailoverPolicy.Decision.LeaveCurrent -> {
                        failures = 0
                        swapAwayFrom(current)
                    }
                }
            }
        }
    }

    private fun stopWatch() {
        watching?.cancel()
        watching = null
    }

    private fun forgetHome() {
        homeNode = null
        autoChosenId = null
    }

    /**
     * Whether enough time has passed since the last automatic move.
     *
     * This is the part 0.3.2 was missing, and its absence is what turned a reasonable idea into
     * dropped connections. Switching servers reloads the core, which kills every connection the
     * tunnel is carrying — so the cost of a switch is paid by the user immediately, while the
     * benefit is speculative: the probe is a bare TCP handshake against the endpoint and says
     * nothing about whether the proxy behind it works.
     *
     * With a probe that weak, the rate of switching matters more than the accuracy of any single
     * decision. Leaving a genuinely dead server a few minutes later is a small cost. Bouncing
     * between two servers every forty seconds is the connection dropping over and over, which is
     * exactly what it looked like from the outside.
     */
    private fun maySwitchNow(): Boolean =
        System.currentTimeMillis() - lastSwitchAtMillis >= FailoverPolicy.SWITCH_COOLDOWN_MILLIS

    private suspend fun swapAwayFrom(dead: ProxyNode) {
        val candidates = FailoverGroup.candidates(
            nodes = profiles.nodes,
            selected = dead,
            latencies = profiles.state.value.latencies,
            limit = FailoverGroup.MAX_GROUP,
            chosen = settings.value.failoverNodeIds,
        )
        if (candidates.isEmpty()) {
            logs.warn(R.string.log_failover_nothing_to_swap, dead.name)
            return
        }

        logs.warn(R.string.log_failover_probing, dead.name, candidates.size)
        // Measured now, not read from the profile: what mattered an hour ago says nothing about
        // which servers are up during the outage this is reacting to.
        val fresh = mutableMapOf<String, com.mydrop.vpn.core.model.LatencyResult>()
        latencyTester.measureAll(candidates, settings.value.pingMode) { result ->
            fresh[result.nodeId] = result
            profiles.recordLatency(result)
        }

        val chosen = FailoverChoice.pick(candidates, fresh)
        if (chosen == null) {
            logs.warn(R.string.log_failover_all_dead, dead.name)
            return
        }

        logs.info(R.string.log_failover_switching, chosen.name, fresh[chosen.id]?.millis.toString())
        // Recorded before the switch, because moving the tunnel takes this coroutine down with it.
        // Only the first departure counts: hop twice and home is still the server the user chose,
        // not the replacement this watchdog installed on the way.
        // A server already given up on does not become home again; otherwise the give-up counter
        // would reset itself every time the tunnel wandered back past it.
        if (homeNode == null && (failbacks[dead.id] ?: 0) < FailoverPolicy.MAX_FAILBACKS) homeNode = dead
        autoChosenId = chosen.id
        lastSwitchAtMillis = System.currentTimeMillis()
        launcher.switchTo(chosen)
    }

    private companion object {
        const val GRACE_MILLIS = 15_000L
        const val PROBE_INTERVAL_MILLIS = 20_000L

        // The thresholds themselves live in FailoverPolicy, which is where they are tested.
    }
}
