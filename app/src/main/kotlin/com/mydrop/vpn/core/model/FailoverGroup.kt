package com.mydrop.vpn.core.model

/**
 * Assembles the pool of servers the tunnel is allowed to move onto.
 *
 * Only eligibility is decided here: who may be considered, and in what order they are worth
 * measuring. Which of them actually gets the traffic during a switch is [FailoverChoice]'s
 * decision, taken against measurements made at that moment rather than the stale ones this uses
 * for ordering.
 */
object FailoverGroup {

    /**
     * Servers kept as candidates, the current one included. Every candidate is measured during a
     * switch, so a whole subscription in the pool would turn one dead server into a hundred
     * probes; past a handful the extra candidates buy nothing anyway.
     */
    const val MAX_GROUP = 8

    /**
     * Companions for [selected], best first.
     *
     * When [chosen] holds ids, those are the candidates and nothing else is: the user said which
     * servers they are willing to be moved onto, and quietly adding others would move their exit
     * country without asking. When it is empty the group falls back to the chosen server's own
     * subscription, so the switch does something sensible before anyone opens the picker.
     *
     * Ordering is by last measured latency, with never-measured servers ahead of ones that failed
     * their last probe: an unknown server might work, a known-dead one probably will not. These
     * numbers only decide who makes the cut when there are more candidates than [MAX_GROUP] —
     * every survivor is measured again before anything is chosen.
     *
     * @param limit total group size including [selected], so the returned list is one shorter.
     */
    fun candidates(
        nodes: List<ProxyNode>,
        selected: ProxyNode,
        latencies: Map<String, LatencyResult>,
        limit: Int,
        chosen: Set<String> = emptySet(),
    ): List<ProxyNode> {
        if (limit <= 1) return emptyList()

        // Two rows can carry the same endpoint — the same server present in two subscriptions,
        // or re-added by hand. Probing it twice measures the same thing twice.
        val takenAddresses = mutableSetOf(selected.address)

        val pool = nodes
            .asSequence()
            .filter { it.id != selected.id }
            .filter { if (chosen.isEmpty()) it.subscriptionId == selected.subscriptionId else it.id in chosen }
            // A direct outbound carries traffic outside the proxy entirely. As a fallback it
            // would quietly turn "my VPN switched servers" into "my VPN stopped being a VPN".
            .filter { it.settings != ProxySettings.Direct }
            .sortedWith(
                compareBy(
                    { latencies[it.id]?.let { result -> if (result.failed) 2 else 0 } ?: 1 },
                    { latencies[it.id]?.takeIf { result -> !result.failed }?.millis ?: 0 },
                ),
            )
            .filter { takenAddresses.add(it.address) }
            .toList()

        return pool.take(limit - 1)
    }
}
