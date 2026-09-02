package com.mydrop.vpn.core.model

import kotlin.random.Random

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
     * Servers measured during one switch, the current one included. A whole subscription in the
     * pool would turn one dead server into a hundred probes; past a handful the extra candidates
     * buy nothing anyway.
     */
    const val MAX_GROUP = 8

    /**
     * Servers written into the core's selector group.
     *
     * Larger than [MAX_GROUP] on purpose. Switching without restarting the core means pointing
     * the selector at another of its own members, so a server the watchdog might choose has to
     * be in here already — and [sample] chooses at random, which means every server it could
     * draw has to be. Membership is cheap: an outbound is a few hundred bytes of configuration
     * that costs nothing until it carries traffic.
     */
    const val SWITCHABLE = 24

    /**
     * The `limit` to ask [candidates] for when [mobileCount] servers are also going into the
     * group, so that the three parts together still fit inside [SWITCHABLE].
     *
     * Both the builder of the group and the watchdog choosing inside it have to arrive at the
     * same number. If the watchdog draws from a longer list than the group holds, the lot can
     * fall on a server the core has never heard of, and an instant switch becomes a failed one.
     *
     * Deliberately allowed to reach zero. A mobile list long enough to fill the group on its own
     * leaves no room for spares, and [candidates] answers such a limit with nothing — which is
     * the truth, and better than handing back names the core cannot be pointed at.
     */
    fun roomFor(mobileCount: Int): Int = SWITCHABLE - mobileCount

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
        exclude: Set<String> = emptySet(),
    ): List<ProxyNode> {
        if (limit <= 1) return emptyList()

        // Two rows can carry the same endpoint — the same server present in two subscriptions,
        // or re-added by hand. Probing it twice measures the same thing twice.
        val takenAddresses = mutableSetOf(selected.address)

        val pool = nodes
            .asSequence()
            .filter { it.id != selected.id }
            // Excluded before the cut, not after it. Filtering a list that has already been
            // trimmed to its first few can empty it outright: when the servers the caller does
            // not want happen to be the fastest ones, they fill every slot and the caller is
            // handed nothing — which is how coming back to Wi-Fi found no ordinary server to
            // come back to.
            .filter { it.id !in exclude }
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

    /**
     * Narrows a candidate list to the servers the running core was actually given.
     *
     * The two lists are supposed to be the same list. [candidates] is called once to build the
     * core's selector group and again, later, when something has to be swapped — and it orders by
     * last measured latency before truncating to `limit`. Those measurements move while the tunnel
     * runs, and the watchdog itself is what moves them, so by the time it draws a replacement the
     * ordering can differ from the one that decided the group. The tail of the list changes, the
     * lot falls on a server the core was never told about, and `selectOutbound` answers
     * "outbound not found in selector" — which a journal caught, and which costs a full restart of
     * the core instead of a pointer swap.
     *
     * That restart is not merely slow. Until the leak in the reload path is found, every extra one
     * is another chance for a core to be left running; see [CoreGenerations].
     *
     * An empty [switchable] means nothing is known about the group — the tunnel is not up, or the
     * builder has forgotten it — and then this is not the place to start refusing candidates.
     * Likewise when nothing survives the filter: a reconnect is worse than a pointer swap and
     * better than being stranded on a dead server.
     */
    fun preferSwitchable(candidates: List<ProxyNode>, switchable: Set<String>): List<ProxyNode> {
        if (switchable.isEmpty()) return candidates
        val inside = candidates.filter { it.id in switchable }
        return inside.ifEmpty { candidates }
    }

    /**
     * [count] of them minus the current server, drawn by lot.
     *
     * The ordering [candidates] applies is right for deciding who belongs in the group at all,
     * and wrong for deciding who gets measured during an outage: it is built from numbers taken
     * before the outage began, so it hands back the same handful every time — including the
     * servers that have just been failing. A phone watched through one bad evening tried the
     * same seven spares on every swap while fourteen others sat in the list untouched.
     *
     * Drawing at random has no opinion about which server is best, and that is the point. Over
     * successive attempts it covers the whole list the user nominated instead of re-testing one
     * corner of it, and nothing that answers is ever unreachable because a stale measurement put
     * it eighth. What answers is still decided afterwards, by measuring.
     */
    fun sample(
        pool: List<ProxyNode>,
        count: Int = MAX_GROUP,
        random: Random = Random,
    ): List<ProxyNode> {
        if (count <= 1) return emptyList()
        return if (pool.size <= count - 1) pool else pool.shuffled(random).take(count - 1)
    }
}
