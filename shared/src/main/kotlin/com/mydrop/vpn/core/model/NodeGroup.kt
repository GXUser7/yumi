package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable

/**
 * A server that is really several: what a provider's auto-select document describes.
 *
 * Some providers hand their clients a whole Xray configuration per server rather than a share
 * link, and the interesting ones carry a balancer — several outbounds, an observatory timing each
 * of them, and a `leastLoad` strategy with a cost per outbound saying which to prefer while it
 * answers. Flattened into a list of links, the same server becomes one of those outbounds, and the
 * one a panel picks for that is the balancer's `fallbackTag`: the last resort.
 *
 * A journal caught what that costs. "LTE Авто - Германия #2" arrived as its fallback, a gRPC
 * outbound the server was refusing with `tls: access denied`, so the tunnel carried nothing; the
 * provider's own client, handed the document, rode the cheapest live candidate — a REALITY server
 * on another address — and worked. A TCP ping could not tell the two apps apart, because both were
 * pinging the same first address.
 *
 * So the node keeps every member. The core is given all of them, and which one carries the tunnel
 * is decided here, by [pick] over delays measured through the tunnel: the core's own balancer is
 * already this app's, pointed at one server by `yumi.SelectOutbound`, and a balancer cannot point
 * at another balancer.
 *
 * The node itself wears its first member — the provider's first choice — as its endpoint, so the
 * list, the ping and everything else that reads a node as one server keep working unchanged.
 */
@Serializable
data class NodeGroup(
    /** The servers, the most preferred first and the provider's last resort last. */
    val members: List<ProxyNode>,
    /** How the provider chooses: by cost while alive ([STRATEGY_COST]), or the fastest ([STRATEGY_FASTEST]). */
    val strategy: String = STRATEGY_COST,
    /** The slowest answer the provider still counts as alive (its `maxRTT`), or 0 for no limit. */
    val maxDelayMillis: Int = 0,
) {
    /**
     * Which member should carry the tunnel, by index, given what came back through the tunnel:
     * member index to milliseconds, with a member that did not answer absent. Null when none did.
     *
     * By cost, this is the provider's order: the first member that answers inside the limit. The
     * costs such documents carry are spaced a hundred thousand apart — a preference order written
     * as numbers — so that is what the core's own `leastLoad` would do with them too. Failing that,
     * the fastest of whatever answered at all: a slow road beats none.
     */
    fun pick(delays: Map<Int, Int>): Int? {
        val alive = delays.filter { (index, millis) -> millis > 0 && index in members.indices }
        if (alive.isEmpty()) return null
        if (strategy == STRATEGY_FASTEST) return alive.minBy { it.value }.key
        val limit = maxDelayMillis.takeIf { it > 0 } ?: Int.MAX_VALUE
        return members.indices.firstOrNull { index -> alive[index]?.let { it <= limit } == true }
            ?: alive.minBy { it.value }.key
    }

    companion object {
        const val STRATEGY_COST = "cost"
        const val STRATEGY_FASTEST = "fastest"
    }
}

/**
 * What the core is given for this node: the node itself, or every member of its group, each under
 * an id of its own.
 *
 * Derived rather than stored. A member can be the very same server as a node elsewhere in the list
 * — a provider offering one outbound both alone and inside a group — and two outbounds with one tag
 * are a document the core refuses outright, taking every other server down with it.
 */
fun ProxyNode.dialed(): List<ProxyNode> =
    group?.members?.mapIndexed { index, member ->
        member.copy(id = memberId(index), name = "$name · ${index + 1}", group = null)
    } ?: listOf(this)

/** The id the core knows member [index] of this node's group by. */
fun ProxyNode.memberId(index: Int): String = "$id-$index"
