package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeGroupTest {

    private fun member(server: String) = ProxyNode(
        id = server,
        name = server,
        server = server,
        port = 443,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
    )

    private val members = listOf(member("a"), member("b"), member("c"))

    @Test
    fun `by cost the first member that answers wins, not the fastest`() {
        val group = NodeGroup(members)
        assertEquals(1, group.pick(mapOf(1 to 300, 2 to 100)))
    }

    @Test
    fun `a member slower than the provider's limit is passed over for one inside it`() {
        val group = NodeGroup(members, maxDelayMillis = 200)
        assertEquals(2, group.pick(mapOf(1 to 300, 2 to 100)))
    }

    @Test
    fun `when nobody is inside the limit the fastest that answered still beats none`() {
        val group = NodeGroup(members, maxDelayMillis = 50)
        assertEquals(2, group.pick(mapOf(1 to 300, 2 to 100)))
    }

    @Test
    fun `least ping takes the fastest`() {
        val group = NodeGroup(members, strategy = NodeGroup.STRATEGY_FASTEST)
        assertEquals(2, group.pick(mapOf(0 to 400, 1 to 300, 2 to 100)))
    }

    @Test
    fun `nothing answered is no choice at all`() {
        val group = NodeGroup(members)
        assertNull(group.pick(emptyMap()))
        assertNull(group.pick(mapOf(0 to -1)))
    }

    @Test
    fun `members are dialled under ids of the group's own`() {
        val group = member("g").copy(id = "g", name = "Auto", group = NodeGroup(members))
        val dialed = group.dialed()
        assertEquals(listOf("g-0", "g-1", "g-2"), dialed.map { it.id })
        assertEquals(listOf("a", "b", "c"), dialed.map { it.server })
        // A plain server is dialled as itself.
        val plain = member("x")
        assertEquals(listOf(plain), plain.dialed())
    }
}
