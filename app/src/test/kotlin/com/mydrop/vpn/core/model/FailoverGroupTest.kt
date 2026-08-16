package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverGroupTest {

    private fun node(
        id: String,
        server: String = "$id.example.com",
        port: Int = 443,
        subscriptionId: String? = "sub-a",
        settings: ProxySettings = ProxySettings.Trojan(password = "p"),
    ) = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = port,
        settings = settings,
        subscriptionId = subscriptionId,
    )

    private fun latency(id: String, millis: Int, failed: Boolean = false) =
        id to LatencyResult(id, millis, measuredAtEpochMillis = 0L, failed = failed)

    @Test
    fun `orders by measured latency`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = nodes[0],
            latencies = mapOf(latency("b", 200), latency("c", 40)),
            limit = 8,
        )

        assertEquals(listOf("c", "b"), group.map { it.id })
    }

    @Test
    fun `an explicit choice replaces the subscription rule`() {
        val mine = node("a")
        val nodes = listOf(mine, node("same"), node("other", subscriptionId = "sub-b"))

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = mine,
            latencies = emptyMap(),
            limit = 8,
            chosen = setOf("other"),
        )

        assertEquals(listOf("other"), group.map { it.id })
    }

    @Test
    fun `an explicit choice still cannot smuggle in a direct outbound`() {
        val mine = node("a")
        val nodes = listOf(mine, node("bypass", settings = ProxySettings.Direct))

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = mine,
            latencies = emptyMap(),
            limit = 8,
            chosen = setOf("bypass"),
        )

        assertTrue(group.isEmpty())
    }

    @Test
    fun `never measured beats last known failure`() {
        val nodes = listOf(node("a"), node("dead"), node("unknown"))
        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = nodes[0],
            latencies = mapOf(latency("dead", 0, failed = true)),
            limit = 8,
        )

        assertEquals(listOf("unknown", "dead"), group.map { it.id })
    }

    @Test
    fun `stays inside the selected server's subscription`() {
        val mine = node("a")
        val nodes = listOf(mine, node("same"), node("other", subscriptionId = "sub-b"))

        val group = FailoverGroup.candidates(nodes, mine, emptyMap(), limit = 8)

        assertEquals(listOf("same"), group.map { it.id })
    }

    @Test
    fun `manually added servers form their own group`() {
        val manual = node("a", subscriptionId = null)
        val nodes = listOf(manual, node("b", subscriptionId = null), node("fromSub"))

        val group = FailoverGroup.candidates(nodes, manual, emptyMap(), limit = 8)

        assertEquals(listOf("b"), group.map { it.id })
    }

    @Test
    fun `same endpoint is not probed twice`() {
        val selected = node("a", server = "edge.example.com")
        val nodes = listOf(
            selected,
            node("duplicate", server = "edge.example.com"),
            node("distinct", server = "other.example.com"),
        )

        val group = FailoverGroup.candidates(nodes, selected, emptyMap(), limit = 8)

        assertEquals(listOf("distinct"), group.map { it.id })
    }

    @Test
    fun `a direct outbound is never a fallback`() {
        val selected = node("a")
        val nodes = listOf(selected, node("bypass", settings = ProxySettings.Direct))

        val group = FailoverGroup.candidates(nodes, selected, emptyMap(), limit = 8)

        assertTrue(group.isEmpty())
    }

    @Test
    fun `limit counts the selected server`() {
        val nodes = (1..10).map { node("n$it") }

        val group = FailoverGroup.candidates(nodes, nodes[0], emptyMap(), limit = 4)

        assertEquals(3, group.size)
    }

    @Test
    fun `a limit of one leaves no room for companions`() {
        val nodes = listOf(node("a"), node("b"))

        assertTrue(FailoverGroup.candidates(nodes, nodes[0], emptyMap(), limit = 1).isEmpty())
    }
}
