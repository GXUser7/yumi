package com.mydrop.vpn.core.model

/**
 * What survives in the lists that name servers by id, after the servers themselves have moved.
 *
 * Split out of [com.mydrop.vpn.data.StaleSelectionPruner] for the same reason [FailoverPolicy] is
 * split out of the watchdog: this is the part with a right answer, and where it lived it could
 * only be exercised by running a repository, a settings store and a coroutine against a temporary
 * directory.
 *
 * The problem it exists for: a node id is a hash of the endpoint and the credentials, so a
 * provider rotating an address or a key hands the same server back under a new id and the old one
 * simply stops existing. The profile store cleans up what it owns — the selection, the measured
 * latencies — but these lists live in settings, a separate store it cannot reach.
 *
 * Left alone they fill with ids matching nothing, and that is load-bearing rather than untidy. A
 * non-empty failover list *is* the pool the watchdog may move between; a non-empty mobile list
 * *is* the pool allowed on a cellular network. Filled with ghosts, either one leaves the tunnel
 * sitting on a dead server reporting there is nothing to replace it with, while the subscription
 * is full of working ones.
 */
object StaleSelection {

    /**
     * @param alive every node id the profile currently holds.
     * @return the same sets with vanished ids removed, or null when nothing was lost and there is
     *   no reason to write to storage or say anything about it.
     */
    fun prune(alive: Set<String>, failover: Set<String>, mobile: Set<String>): Pruned? {
        // An empty profile is one that has not loaded yet at least as often as it is one with
        // nothing in it, and pruning against that would wipe a choice somebody made by hand.
        if (alive.isEmpty()) return null

        val keptFailover = failover intersect alive
        val keptMobile = mobile intersect alive
        val lostFailover = failover.size - keptFailover.size
        val lostMobile = mobile.size - keptMobile.size
        if (lostFailover == 0 && lostMobile == 0) return null

        return Pruned(
            failover = keptFailover,
            mobile = keptMobile,
            lostFailover = lostFailover,
            lostMobile = lostMobile,
        )
    }

    data class Pruned(
        val failover: Set<String>,
        val mobile: Set<String>,
        val lostFailover: Int,
        val lostMobile: Int,
    ) {
        /** True when a list that meant something has been emptied out entirely. */
        val failoverEmptied: Boolean get() = lostFailover > 0 && failover.isEmpty()
        val mobileEmptied: Boolean get() = lostMobile > 0 && mobile.isEmpty()
    }
}
