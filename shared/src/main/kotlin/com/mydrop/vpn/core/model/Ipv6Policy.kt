package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable

/**
 * What one exit said when asked to reach an IPv6 address, and when it was asked.
 *
 * Only recorded for an exit that carried the IPv4 probe in the same round: an exit that carries
 * nothing at all has said nothing about IPv6, and marking it would keep IPv6 off long after it
 * came back.
 */
@Serializable
data class Ipv6Verdict(
    val passes: Boolean,
    val checkedAtMillis: Long,
)

/**
 * Whether applications in the tunnel may be handed IPv6 addresses.
 *
 * The setting alone used to decide it, and a friend's phone showed what that costs. With IPv6 on,
 * the resolver answers `AAAA` and every application that has one — YouTube, Play, the Yandex apps —
 * connects to an IPv6 literal. The core sniffs the name only to route (`routeOnly`), so the literal
 * is what reaches the server, and the server has to dial it. Quattro's Hysteria2 exits cannot: in
 * that journal IPv6 streams to Telegram closed one round trip after they were opened, on every one
 * of four servers, while IPv4 through the same exits worked. TikTok, which publishes no `AAAA`,
 * kept working; YouTube never fetched a single video. Nothing said why — Xray's Hysteria server
 * answers "connected" before it dials (`proxy/hysteria/server.go`), so a failed dial reaches the
 * phone as a stream that simply ends, with no error to log.
 *
 * So the answer is now "only through exits that were seen to carry IPv6". An exit nobody has asked
 * yet counts as one that cannot: the first connection through it hands out IPv4 only, the guard
 * asks, and the tunnel is rebuilt with IPv6 if the answer is yes. The other order — answer `AAAA`
 * until an exit is caught failing — breaks the applications in the gap, and the gap outlives the
 * fix: they keep the addresses they were given for as long as the record says, and nothing this
 * side can make them forget.
 */
object Ipv6Policy {

    /**
     * How long a verdict stands before the exit is asked again.
     *
     * An exit gaining or losing IPv6 is a change to how the server is run, not something that
     * flickers, so this is long. Asking on every connection would be a request through every
     * server in the group, every time, on somebody's mobile data, to relearn what is known.
     */
    const val RECHECK_AFTER_MILLIS = 12L * 60 * 60 * 1000

    /**
     * What a verdict is filed under: the host the member dials.
     *
     * The host rather than the node id, because whether a machine reaches IPv6 is a property of
     * the machine. Ids change under it — a panel that rewrites a server's SNI on every fetch gives
     * the same exit a new id each refresh — and a verdict keyed on the id would be lost each time.
     */
    fun exitOf(member: ProxyNode): String = member.server.trim().lowercase()

    /**
     * Whether the members count at all. A `direct` node has no exit of its own — its IPv6 is the
     * phone's — so it is neither asked nor allowed to hold IPv6 back.
     */
    private fun proxied(members: List<ProxyNode>): List<ProxyNode> =
        members.filter { it.settings != ProxySettings.Direct }

    /**
     * @param active the members of the server the tunnel starts on — one, or a group's several.
     * @param members every member the running core may be moved onto without a rebuild, [active]
     *   among them.
     */
    fun answersIpv6(
        settings: AppSettings,
        active: List<ProxyNode>,
        members: List<ProxyNode>,
        verdicts: Map<String, Ipv6Verdict>,
    ): Boolean {
        if (!settings.enableIpv6) return false
        // Nothing is proxied in this mode, so no exit is involved in any connection.
        if (settings.routingMode == RoutingMode.Direct) return true
        // The server carrying traffic has to have shown it passes — for a group, one of its
        // members, since which of them carries it is decided later by measuring them. Anything that
        // was never reachable is tolerated, because it cannot carry anything either; anything that
        // was reached and refused IPv6 is not, because a move onto it — another member, another
        // server — is a pointer swap that nobody gets to re-answer DNS for.
        return proxied(active).any { verdicts[exitOf(it)]?.passes == true } &&
            proxied(members).none { verdicts[exitOf(it)]?.passes == false }
    }

    /** Members whose exit has no verdict, or one older than [RECHECK_AFTER_MILLIS]. */
    fun due(
        members: List<ProxyNode>,
        verdicts: Map<String, Ipv6Verdict>,
        nowMillis: Long,
    ): List<ProxyNode> = proxied(members).filter { member ->
        val verdict = verdicts[exitOf(member)]
        verdict == null ||
            nowMillis - verdict.checkedAtMillis !in 0 until RECHECK_AFTER_MILLIS
    }

    /** Members whose exit was reached and would not carry IPv6. What the warning names. */
    fun refusing(
        members: List<ProxyNode>,
        verdicts: Map<String, Ipv6Verdict>,
    ): List<ProxyNode> = proxied(members).filter { verdicts[exitOf(it)]?.passes == false }
}
