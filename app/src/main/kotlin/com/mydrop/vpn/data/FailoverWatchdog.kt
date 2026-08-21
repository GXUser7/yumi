package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.FailoverChoice
import com.mydrop.vpn.core.model.FailoverGroup
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

                if (home != null) {
                    val revived = latencyTester.measure(home, settings.value.pingMode)
                    profiles.recordLatency(revived)
                    recoveries = if (revived.failed) 0 else recoveries + 1
                    // Same reasoning as the swap: one answered probe is a coincidence, and going
                    // home on it would flap the tunnel between two servers every forty seconds.
                    if (recoveries >= PROBES_BEFORE_FAILBACK) {
                        recoveries = 0
                        forgetHome()
                        logs.info("«${home.name}» снова отвечает — возвращаюсь на него")
                        launcher.switchTo(home)
                        continue
                    }
                }

                val result = latencyTester.measure(current, settings.value.pingMode)
                if (!result.failed) {
                    failures = 0
                    continue
                }

                failures++
                // One failed probe is a lost packet or a moment of bad signal. Switching on that
                // would drop every connection the user has, repeatedly, on a working tunnel.
                if (failures < FAILURES_BEFORE_SWAP) {
                    logs.debug("«${current.name}» не ответил ($failures/$FAILURES_BEFORE_SWAP)")
                    continue
                }

                failures = 0
                swapAwayFrom(current)
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

    private suspend fun swapAwayFrom(dead: ProxyNode) {
        val candidates = FailoverGroup.candidates(
            nodes = profiles.nodes,
            selected = dead,
            latencies = profiles.state.value.latencies,
            limit = FailoverGroup.MAX_GROUP,
            chosen = settings.value.failoverNodeIds,
        )
        if (candidates.isEmpty()) {
            logs.warn("«${dead.name}» не отвечает, но заменить его нечем")
            return
        }

        logs.warn("«${dead.name}» не отвечает — проверяю ${candidates.size} запасных")
        // Measured now, not read from the profile: what mattered an hour ago says nothing about
        // which servers are up during the outage this is reacting to.
        val fresh = mutableMapOf<String, com.mydrop.vpn.core.model.LatencyResult>()
        latencyTester.measureAll(candidates, settings.value.pingMode) { result ->
            fresh[result.nodeId] = result
            profiles.recordLatency(result)
        }

        val chosen = FailoverChoice.pick(candidates, fresh)
        if (chosen == null) {
            logs.warn("Ни один запасной сервер не ответил — остаюсь на «${dead.name}»")
            return
        }

        logs.info("Переключаюсь на «${chosen.name}» (${fresh[chosen.id]?.millis} мс)")
        // Recorded before the switch, because moving the tunnel takes this coroutine down with it.
        // Only the first departure counts: hop twice and home is still the server the user chose,
        // not the replacement this watchdog installed on the way.
        if (homeNode == null) homeNode = dead
        autoChosenId = chosen.id
        launcher.switchTo(chosen)
    }

    private companion object {
        const val GRACE_MILLIS = 15_000L
        const val PROBE_INTERVAL_MILLIS = 20_000L

        /** Consecutive failed probes before the tunnel is moved. */
        const val FAILURES_BEFORE_SWAP = 2

        /** Consecutive answered probes before the tunnel returns to the user's own server. */
        const val PROBES_BEFORE_FAILBACK = 2
    }
}
