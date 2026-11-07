package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A group is as reachable as the member that would carry it, not as its first member.
 *
 * Shaped after Quattro's "LTE Авто": the provider's first choice is a server abroad that a mobile
 * network with an allow-list never lets through, and the members behind it are the ones that work.
 */
class LatencyTesterGroupTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private lateinit var alive: ServerSocket
    private lateinit var alsoAlive: ServerSocket
    /** A port that was free a moment ago and has nothing listening on it now. */
    private var deadPort = 0

    @Before
    fun open() {
        alive = ServerSocket(0, 50, loopback)
        alsoAlive = ServerSocket(0, 50, loopback)
        deadPort = ServerSocket(0, 1, loopback).use { it.localPort }
    }

    @After
    fun close() {
        alive.close()
        alsoAlive.close()
    }

    private fun member(id: String, port: Int) = ProxyNode(
        id = id,
        name = id,
        server = "127.0.0.1",
        port = port,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
    )

    private fun group(vararg members: ProxyNode, strategy: String = NodeGroup.STRATEGY_COST): ProxyNode {
        val group = NodeGroup(members = members.toList(), strategy = strategy, maxDelayMillis = 2_000)
        // The node wears its first member's endpoint, as ConfigDocumentParser builds it.
        return members.first().copy(id = "group", name = "LTE Авто", group = group)
    }

    @Test
    fun `a dead first member does not make the group unreachable`() = runBlocking {
        val node = group(member("abroad", deadPort), member("relay", alive.localPort))

        val result = LatencyTester().measure(node, PingMode.Tcp)

        assertFalse(result.failed)
        assertEquals("group", result.nodeId)
        assertTrue(result.millis >= 0)
    }

    @Test
    fun `the group fails only when no member answers`() = runBlocking {
        val node = group(member("abroad", deadPort), member("relay", deadPort))

        val result = LatencyTester().measure(node, PingMode.Tcp)

        assertTrue(result.failed)
        assertEquals("group", result.nodeId)
    }

    @Test
    fun `measuring a list keeps one result per server, groups included`() = runBlocking {
        val plain = member("plain", alsoAlive.localPort)
        val node = group(member("abroad", deadPort), member("relay", alive.localPort))

        val results = LatencyTester().measureAll(listOf(plain, node), PingMode.Tcp)

        assertEquals(listOf("plain", "group"), results.map { it.nodeId })
        assertTrue(results.none { it.failed })
    }
}
