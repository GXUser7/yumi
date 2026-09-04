package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration touches the only thing in this app that cannot be re-fetched — the servers a user
 * has, the one they chose, and the two lists they curated by hand. So it is tested against the
 * shapes that actually occur rather than against a happy path.
 */
class NodeIdMigrationTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun node(
        id: String,
        name: String = "N",
        server: String = "example.com",
        port: Int = 443,
        transport: TransportOptions? = null,
        subscriptionId: String? = "sub",
    ) = ProxyNode(
        id = id,
        name = name,
        server = server,
        port = port,
        settings = ProxySettings.Vless(uuid = uuid),
        transport = transport,
        subscriptionId = subscriptionId,
    )

    @Test
    fun `a node whose id already matches its contents is left alone`() {
        val settled = node("x").identified()
        assertTrue(NodeIdMigration.remap(listOf(settled)).isEmpty())
    }

    @Test
    fun `a node stored under the old id is moved`() {
        val stale = node("an-old-id")
        val mapping = NodeIdMigration.remap(listOf(stale))

        assertEquals(1, mapping.size)
        assertEquals(ProxyNode.stableId(stale), mapping["an-old-id"])
    }

    /**
     * The case the whole change is for: one endpoint, two transports. Under the old seed both
     * hashed the same, so the second was dropped before it ever reached the list.
     */
    @Test
    fun `two transports on one endpoint end up with two ids`() {
        val ws = node("", transport = TransportOptions.WebSocket(path = "/a")).identified()
        val grpc = node("", transport = TransportOptions.Grpc(serviceName = "s")).identified()

        assertNotEquals(ws.id, grpc.id)
        assertEquals(2, listOf(ws, grpc).distinctBy { it.id }.size)
    }

    @Test
    fun `following an id that is not being moved returns it unchanged`() {
        val mapping = mapOf("old" to "new")
        assertEquals("new", NodeIdMigration.follow("old", mapping))
        assertEquals("untouched", NodeIdMigration.follow("untouched", mapping))
    }

    @Test
    fun `a curated list follows its servers`() {
        val mapping = mapOf("a" to "a2", "b" to "b2")
        assertEquals(setOf("a2", "b2", "c"), NodeIdMigration.follow(setOf("a", "b", "c"), mapping))
    }

    /** Order is part of the meaning for a subscription's own list, so it is preserved. */
    @Test
    fun `a subscription keeps the order of its servers`() {
        val mapping = mapOf("b" to "b2")
        assertEquals(listOf("a", "b2", "c"), NodeIdMigration.follow(listOf("a", "b", "c"), mapping))
    }

    /** Nothing to move means nothing is touched, which is every launch after the first. */
    @Test
    fun `an empty mapping is a no-op on every shape`() {
        assertEquals(setOf("a"), NodeIdMigration.follow(setOf("a"), emptyMap()))
        assertEquals(listOf("a"), NodeIdMigration.follow(listOf("a"), emptyMap()))
        assertEquals("a", NodeIdMigration.follow("a", emptyMap()))
    }

    /**
     * Two subscriptions reselling one endpoint stay two servers, and the migration must not merge
     * them — that would silently delete one of the two rows and whichever the user had chosen.
     */
    @Test
    fun `servers from different subscriptions stay apart`() {
        val one = node("", subscriptionId = "sub-a").identified()
        val two = node("", subscriptionId = "sub-b").identified()
        assertNotEquals(one.id, two.id)
    }

    @Test
    fun `the mapping never sends two servers to one id`() {
        val nodes = listOf(
            node("old-1", transport = TransportOptions.WebSocket(path = "/a")),
            node("old-2", transport = TransportOptions.Grpc(serviceName = "s")),
            node("old-3", transport = null),
        )
        val mapping = NodeIdMigration.remap(nodes)
        assertEquals(nodes.size, mapping.values.toSet().size)
    }
}
