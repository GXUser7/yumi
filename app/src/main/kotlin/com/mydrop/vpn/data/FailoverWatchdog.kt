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

    /** Follows the tunnel: a watch runs for exactly as long as one connection lives. */
    fun start() {
        scope.launch {
            tunnel.state.collectLatest { state ->
                when (state) {
                    is VpnState.Connected -> restartWatch()
                    else -> stopWatch()
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
            while (isActive) {
                delay(PROBE_INTERVAL_MILLIS)
                if (!settings.value.autoFailover) {
                    failures = 0
                    continue
                }
                val current = profiles.selectedNode() ?: continue

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
        launcher.switchTo(chosen)
    }

    private companion object {
        const val GRACE_MILLIS = 15_000L
        const val PROBE_INTERVAL_MILLIS = 20_000L

        /** Consecutive failed probes before the tunnel is moved. */
        const val FAILURES_BEFORE_SWAP = 2
    }
}
