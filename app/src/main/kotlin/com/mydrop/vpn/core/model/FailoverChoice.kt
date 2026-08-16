package com.mydrop.vpn.core.model

import kotlin.random.Random

/**
 * Chooses the server to move onto once the current one has stopped answering.
 *
 * Two decisions, in order. First, who is even eligible: a server that did not answer the probe is
 * out, and so is one that is dramatically slower than the rest of the pack — being reachable is
 * not the same as being usable. Then, among the survivors, the pick is random rather than
 * fastest-wins. Always taking the fastest sends every client that shares a subscription onto the
 * same server the moment a popular one drops, which is how a failover turns into a stampede.
 *
 * The slowness cut is stated against the median, not the mean. In a pool measuring
 * 100/100/100/100/1000 the mean is dragged to 280 by the very server the rule exists to exclude,
 * while the median stays at the 100 that describes the pack.
 */
object FailoverChoice {

    /**
     * The winner, or null when nothing in [candidates] answered and there is nowhere to go.
     *
     * @param latencies fresh measurements; a server missing from the map counts as no answer.
     */
    fun pick(
        candidates: List<ProxyNode>,
        latencies: Map<String, LatencyResult>,
        random: Random = Random.Default,
    ): ProxyNode? {
        val alive = candidates.mapNotNull { node ->
            latencies[node.id]?.takeIf { !it.failed }?.let { node to it.millis }
        }
        if (alive.isEmpty()) return null

        val ceiling = ceilingFor(alive.map { it.second })
        val survivors = alive.filter { it.second <= ceiling }

        // The median itself always passes its own ceiling, so this cannot be empty.
        return survivors.random(random).first
    }

    /** Median plus half of it: the pack's own pace, with room for honest variation. */
    private fun ceilingFor(measurements: List<Int>): Int {
        val sorted = measurements.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
        return median + median * TOLERANCE_PERCENT / 100
    }

    private const val TOLERANCE_PERCENT = 50
}
