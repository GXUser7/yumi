package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Node identity has to survive a subscription refresh without ever colliding.
 *
 * A collision is not cosmetic: the id keys the server list, the current selection and the latency
 * map at once, and `LazyColumn` throws `IllegalArgumentException` the moment two items report the
 * same key — which is exactly how this surfaced, as a crash on scrolling the servers tab after
 * adding a real subscription.
 */
class NodeIdentityTest {

    private val link =
        "vless://11111111-2222-3333-4444-555555555555@node.example.com:443" +
            "?type=tcp&security=reality&pbk=abc&sid=00#NL-01"

    @Test
    fun `the same endpoint listed twice in one subscription yields one node`() {
        // Providers do this constantly: a duplicated row, or a second label for one server.
        val text = listOf(link, link.replace("#NL-01", "#NL-01%20(backup)")).joinToString("\n")

        val nodes = ProxyUriParser.parseAll(text, subscriptionId = "sub-a")

        assertEquals(2, nodes.size)
        assertEquals("both entries resolve to one identity", nodes[0].id, nodes[1].id)
        assertEquals(1, nodes.distinctBy { it.id }.size)
    }

    @Test
    fun `the same endpoint in two subscriptions stays two distinct nodes`() {
        // Two providers reselling one endpoint are still two rows the user manages separately.
        val fromA = ProxyUriParser.parse(link, subscriptionId = "sub-a")!!
        val fromB = ProxyUriParser.parse(link, subscriptionId = "sub-b")!!

        assertNotEquals(fromA.id, fromB.id)
    }

    @Test
    fun `renaming a node does not change its identity`() {
        // Load tags and expiry notices land in the name on every refresh; the selection must hold.
        val before = ProxyUriParser.parse(link, subscriptionId = "sub-a")!!
        val after = ProxyUriParser.parse(link.replace("#NL-01", "#NL-01%20~%2012ms"), "sub-a")!!

        assertEquals(before.id, after.id)
        assertNotEquals(before.name, after.name)
    }

    @Test
    fun `a manually added server keeps a stable id of its own`() {
        val manual = ProxyUriParser.parse(link)!!
        val again = ProxyUriParser.parse(link)!!
        val subscribed = ProxyUriParser.parse(link, subscriptionId = "sub-a")!!

        assertEquals(manual.id, again.id)
        assertNotEquals(manual.id, subscribed.id)
    }

    @Test
    fun `different credentials on one endpoint are different nodes`() {
        val first = ProxyUriParser.parse(link, subscriptionId = "sub-a")!!
        val second = ProxyUriParser.parse(
            link.replace("11111111-2222-3333-4444-555555555555", "99999999-2222-3333-4444-555555555555"),
            subscriptionId = "sub-a",
        )!!

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `stableId is scoped by subscription`() {
        val settings = ProxyUriParser.parse(link)!!.settings

        assertNotEquals(
            ProxyNode.stableId("node.example.com", 443, settings, "sub-a"),
            ProxyNode.stableId("node.example.com", 443, settings, "sub-b"),
        )
        assertEquals(
            ProxyNode.stableId("node.example.com", 443, settings, "sub-a"),
            ProxyNode.stableId("node.example.com", 443, settings, "sub-a"),
        )
    }
}
