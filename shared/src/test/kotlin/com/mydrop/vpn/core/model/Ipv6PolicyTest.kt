package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6PolicyTest {

    private fun node(id: String, server: String = "$id.example") = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = 443,
        settings = ProxySettings.Hysteria2(password = "secret"),
    )

    private val on = AppSettings(enableIpv6 = true)
    private val now = 1_000_000_000_000L

    private fun passes(vararg nodes: ProxyNode) =
        nodes.associate { Ipv6Policy.exitOf(it) to Ipv6Verdict(passes = true, checkedAtMillis = now) }

    private fun refuses(vararg nodes: ProxyNode) =
        nodes.associate { Ipv6Policy.exitOf(it) to Ipv6Verdict(passes = false, checkedAtMillis = now) }

    @Test
    fun `off is off whatever the servers can do`() {
        val latvia = node("latvia")
        assertFalse(
            Ipv6Policy.answersIpv6(AppSettings(enableIpv6 = false), listOf(latvia), listOf(latvia), passes(latvia)),
        )
    }

    /**
     * The friend's phone: IPv6 switched on, four servers, none of which can dial an IPv6 address.
     * Handing out `AAAA` there is what broke YouTube while TikTok kept working.
     */
    @Test
    fun `a server that refused ipv6 gets ipv4 only`() {
        val latvia = node("latvia")
        assertFalse(Ipv6Policy.answersIpv6(on, listOf(latvia), listOf(latvia), refuses(latvia)))
    }

    /** Never on credit: the first connection through an exit waits for its answer on IPv4. */
    @Test
    fun `a server nobody has asked yet gets ipv4 only`() {
        val latvia = node("latvia")
        assertFalse(Ipv6Policy.answersIpv6(on, listOf(latvia), listOf(latvia), emptyMap()))
    }

    @Test
    fun `a server seen carrying ipv6 gets it`() {
        val latvia = node("latvia")
        assertTrue(Ipv6Policy.answersIpv6(on, listOf(latvia), listOf(latvia), passes(latvia)))
    }

    /**
     * A switch onto another member of the group is a pointer swap: the resolver is not rebuilt and
     * keeps answering what it answered. So one spare known to refuse IPv6 keeps it off for the
     * whole document.
     */
    @Test
    fun `a spare that refuses ipv6 holds it back for the whole group`() {
        val latvia = node("latvia")
        val estonia = node("estonia")
        assertFalse(
            Ipv6Policy.answersIpv6(on, listOf(latvia), listOf(latvia, estonia), passes(latvia) + refuses(estonia)),
        )
    }

    /** A spare nobody could reach carries nothing, so it has no say — otherwise one dead server in a
     *  failover list of twenty keeps IPv6 off for good. */
    @Test
    fun `a spare that could not be asked does not hold ipv6 back`() {
        val latvia = node("latvia")
        val estonia = node("estonia")
        assertTrue(Ipv6Policy.answersIpv6(on, listOf(latvia), listOf(latvia, estonia), passes(latvia)))
    }

    /** A group's members are measured and moved between later; one of them passing is enough to
     *  start on, and one refusing is enough to stop. */
    @Test
    fun `a group needs one member seen carrying ipv6 and none refusing it`() {
        val first = node("group-0")
        val second = node("group-1")
        val group = listOf(first, second)
        assertTrue(Ipv6Policy.answersIpv6(on, group, group, passes(first)))
        assertFalse(Ipv6Policy.answersIpv6(on, group, group, passes(first) + refuses(second)))
        assertFalse(Ipv6Policy.answersIpv6(on, group, group, emptyMap()))
    }

    /** Whether a machine reaches IPv6 is the machine's: a new id for the same exit keeps its verdict. */
    @Test
    fun `verdicts follow the exit rather than the id`() {
        val before = node("latvia-a", server = "13.143.209.5")
        val after = node("latvia-b", server = "13.143.209.5")
        assertFalse(Ipv6Policy.answersIpv6(on, listOf(after), listOf(after), refuses(before)))
        assertTrue(Ipv6Policy.due(listOf(after), refuses(before), now).isEmpty())
    }

    @Test
    fun `nothing is proxied in direct mode, so no server has a say`() {
        val latvia = node("latvia")
        val direct = on.copy(routingMode = RoutingMode.Direct)
        assertTrue(Ipv6Policy.answersIpv6(direct, listOf(latvia), listOf(latvia), refuses(latvia)))
    }

    @Test
    fun `a direct node is never asked and never blamed`() {
        val direct = node("direct").copy(settings = ProxySettings.Direct)
        assertTrue(Ipv6Policy.due(listOf(direct), emptyMap(), now).isEmpty())
        assertTrue(Ipv6Policy.refusing(listOf(direct), refuses(direct)).isEmpty())
    }

    @Test
    fun `a verdict is asked again once it is old, or from the future`() {
        val latvia = node("latvia")
        val fresh = passes(latvia)
        assertTrue(Ipv6Policy.due(listOf(latvia), fresh, now + 60_000).isEmpty())
        assertEquals(
            listOf(latvia),
            Ipv6Policy.due(listOf(latvia), fresh, now + Ipv6Policy.RECHECK_AFTER_MILLIS),
        )
        // The wall clock steps backwards; a verdict dated after "now" is not a fresh one.
        assertEquals(listOf(latvia), Ipv6Policy.due(listOf(latvia), fresh, now - 60_000))
    }

    @Test
    fun `refusing names only the servers that refused`() {
        val latvia = node("latvia")
        val estonia = node("estonia")
        val germany = node("germany")
        assertEquals(
            listOf(estonia),
            Ipv6Policy.refusing(listOf(latvia, estonia, germany), passes(latvia) + refuses(estonia)),
        )
    }
}
