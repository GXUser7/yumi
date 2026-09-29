package com.mydrop.vpn.data

import android.os.SystemClock
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.dialed

/**
 * Chooses which member of a group carries the tunnel: the part of a provider's auto-select server
 * that its own client leaves to the core, done here. See [NodeGroup] for why the core cannot.
 *
 * Every member is measured through the tunnel at once — the same measurement the watchdog trusts
 * for choosing a replacement server, and the only one that sees a member whose port answers while
 * its handshake is refused — and the one [NodeGroup.pick] names is pinned. Asked by the watchdog
 * when the tunnel comes up, when its probe fails, and every [LOOK_EVERY_MILLIS] in between, which is
 * how often the provider's own observatory looks: often enough to move back onto a cheaper member
 * once it recovers, rarely enough to cost nothing to speak of.
 */
class GroupSelector(
    private val tunnel: TunnelController,
    private val logs: LogRepository,
) {
    /** The member each group is pinned to, by index; absent means the one the core started on. */
    private val pinned = mutableMapOf<String, Int>()

    /** When each group was last measured, on the monotonic clock. */
    private val lastLook = mutableMapOf<String, Long>()

    /**
     * Forgets every pin: a tunnel that was started or moved has put each group back on its first
     * member, whatever was pinned before.
     */
    fun tunnelRestarted() {
        pinned.clear()
        lastLook.clear()
    }

    /** Whether [group] is due another look. */
    fun due(group: ProxyNode): Boolean =
        SystemClock.elapsedRealtime() - (lastLook[group.id] ?: 0L) >= LOOK_EVERY_MILLIS

    /**
     * Measures every member of [group] and pins the one the provider would have chosen.
     *
     * @param avoidPinned leave the member the tunnel is on out of the choice: asked when that member
     *   has just failed a probe, which says more about it than one measurement does.
     * @return true when a member answered and carries the tunnel now; false when none did, and then
     *   nothing moved and the failure is the server's.
     */
    suspend fun choose(group: ProxyNode, reason: String, avoidPinned: Boolean = false): Boolean {
        val spec = group.group ?: return false
        val members = group.dialed()
        lastLook[group.id] = SystemClock.elapsedRealtime()

        val measured = tunnel.measureThroughTunnel(members)
        val delays = members.withIndex()
            .mapNotNull { (index, member) -> measured[member.id]?.let { index to it } }
            .toMap()
        val current = pinned[group.id] ?: 0
        val candidates = if (avoidPinned) delays - current else delays
        val summary = members.indices.joinToString(" ") { index ->
            "${index + 1}=${delays[index]?.let { "${it}ms" } ?: "-"}"
        }

        val pick = spec.pick(candidates)
        if (pick == null) {
            logs.trace(TAG, "group ${group.name} ($reason): no member answered [$summary]")
            return false
        }
        if (pick != current && !tunnel.pinMember(members[pick])) {
            logs.trace(TAG, "group ${group.name} ($reason): could not move to member ${pick + 1} [$summary]")
            return false
        }
        pinned[group.id] = pick
        logs.trace(
            TAG,
            "group ${group.name} ($reason): member ${pick + 1}/${members.size} " +
                "${members[pick].address} [$summary]",
        )
        return true
    }

    private companion object {
        const val TAG = "YumiFailover"

        /** The provider's own observatory looks every two minutes; so does this. */
        const val LOOK_EVERY_MILLIS = 120_000L
    }
}
