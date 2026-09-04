package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the full connect/disconnect state machine without touching the network, so the connect
 * screen's phases, counters and shape morphs can be built and reviewed before the core lands.
 *
 * It reports [isReal] = false; the UI shows a demo banner on top of it, because a VPN client
 * that looks connected while forwarding nothing is actively dangerous.
 */
class SimulatedTunnelController(
    private val scope: CoroutineScope,
    private val logs: LogRepository,
) : TunnelController {

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats.Zero)
    override val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    override val isReal: Boolean = false

    private var job: Job? = null

    override fun connect(node: ProxyNode, reason: String) {
        job?.cancel()
        _traffic.value = TrafficStats.Zero
        job = scope.launch {
            logs.info(R.string.log_connecting_to, node.name, node.protocol.label, node.address)

            VpnState.Connecting.Phase.entries.forEach { phase ->
                _state.value = VpnState.Connecting(node.id, phase)
                logs.debug(phase.labelRes)
                delay(Random.nextLong(350, 700))
            }

            _state.value = VpnState.Connected(node.id, System.currentTimeMillis())
            logs.info(R.string.log_tunnel_up, node.name)
            pumpTraffic()
        }
    }

    override fun disconnect() {
        job?.cancel()
        job = scope.launch {
            _state.value = VpnState.Disconnecting
            logs.info(R.string.log_tunnel_stopping)
            delay(300)
            _state.value = VpnState.Disconnected
            _traffic.value = TrafficStats.Zero
        }
    }

    /**
     * Emits plausible throughput once a second. Rates follow a slow sine so the sparkline shows
     * recognisable bursts and lulls instead of white noise.
     */
    private suspend fun pumpTraffic() {
        var totalDown = 0L
        var totalUp = 0L
        var tick = 0

        while (scope.isActive) {
            delay(1_000)
            tick++

            val wave = (sin(tick / 7.0) + 1.0) / 2.0
            val downRate = (120_000 + wave * 2_400_000 + Random.nextLong(0, 260_000)).toLong()
            val upRate = (downRate * (0.06 + Random.nextDouble(0.0, 0.05))).toLong()

            totalDown += downRate
            totalUp += upRate

            _traffic.value = TrafficStats(
                uploadBytes = totalUp,
                downloadBytes = totalDown,
                uploadBytesPerSecond = upRate,
                downloadBytesPerSecond = downRate,
                activeConnections = 3 + Random.nextInt(0, 14),
            )
        }
    }
}
