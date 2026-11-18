package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates single-flight filtered server ping sweeps across mobile and TV view models,
 * enforcing an atomic double-tap guard, empty-list short-circuit, and scoped [pingingNodeIds]
 * cleanup on completion, failure, or cancellation.
 */
class FilteredPingCoordinator(
    private val scope: CoroutineScope,
    private val recordLatency: (LatencyResult) -> Unit,
) {
    private val _pingingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val pingingNodeIds: StateFlow<Set<String>> = _pingingNodeIds.asStateFlow()

    private val lock = Any()

    @Volatile
    private var activeJob: Job? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun ping(
        nodes: List<ProxyNode>,
        mode: PingMode,
        measure: suspend (List<ProxyNode>, PingMode, (LatencyResult) -> Unit) -> List<LatencyResult>,
        onComplete: suspend () -> Unit = {},
    ): Job? {
        if (nodes.isEmpty()) return null
        val ids = nodes.mapTo(HashSet()) { it.id }
        val job: Job = synchronized(lock) {
            if (activeJob?.isCompleted == false) return null
            val created = scope.launch(start = CoroutineStart.LAZY) {
                _pingingNodeIds.update { it + ids }
                try {
                    measure(nodes, mode) { result ->
                        recordLatency(result)
                        _pingingNodeIds.update { it - result.nodeId }
                    }
                } finally {
                    _pingingNodeIds.update { it - ids }
                }
                onComplete()
            }
            activeJob = created
            created.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJob === created) {
                        activeJob = null
                    }
                }
            }
            if (created.isCompleted) {
                activeJob = null
            }
            created
        }
        job.start()
        return job
    }
}
