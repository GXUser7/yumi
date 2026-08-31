package com.mydrop.vpn.core.model

import kotlin.random.Random
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

    /**
     * A server nominated for cellular is not an ordinary spare. Both callers that build an
     * ordinary pool pass this — FailoverWatchdog.swapAwayFrom off cellular and its ordinaryPoolFor
     * — because landing on one at home undoes the list from the other direction.
     */
    @Test
    fun `a mobile server is not an ordinary spare`() {
        val selected = node("a")
        val nodes = listOf(selected, node("m1"), node("b"))

        val group = FailoverGroup.candidates(
            nodes,
            selected,
            emptyMap(),
            limit = 8,
            exclude = setOf("m1"),
        )

        assertEquals(listOf("b"), group.map { it.id })
    }

    /**
     * Excluded before the cut, not after it.
     *
     * Here the two fastest servers are the mobile ones, so a limit of three filled with them and
     * filtered afterwards hands back nothing — which is how coming back to Wi-Fi once found no
     * ordinary server to come back to.
     */
    @Test
    fun `the excluded do not eat the slots on their way out`() {
        val selected = node("a")
        val nodes = listOf(selected, node("m1"), node("m2"), node("slow"))
        val latencies = mapOf(latency("m1", 10), latency("m2", 20), latency("slow", 900))

        val group = FailoverGroup.candidates(
            nodes,
            selected,
            latencies,
            limit = 3,
            exclude = setOf("m1", "m2"),
        )

        assertEquals(listOf("slow"), group.map { it.id })
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

    @Test
    fun `a lot is drawn from the whole list, not its head`() {
        // Twenty nominated servers, seven drawn, many times over: if the draw were the ordering
        // in disguise, the same seven would come back every time. The bug this replaces did
        // exactly that — a phone retried one corner of the list all evening while the rest of it
        // sat untouched.
        val pool = (1..20).map { node("n$it", "h$it.example.com") }
        val seen = mutableSetOf<String>()
        repeat(30) { seed ->
            val drawn = FailoverGroup.sample(pool, random = Random(seed.toLong()))
            assertEquals(7, drawn.size)
            drawn.forEach { seen += it.id }
        }
        assertEquals(pool.size, seen.size)
    }

    @Test
    fun `the same seed draws the same lot`() {
        val pool = (1..20).map { node("n$it", "h$it.example.com") }
        assertEquals(
            FailoverGroup.sample(pool, random = Random(7)).map { it.id },
            FailoverGroup.sample(pool, random = Random(7)).map { it.id },
        )
    }

    @Test
    fun `a short list is taken whole rather than thinned`() {
        val pool = (1..4).map { node("n$it", "h$it.example.com") }
        assertEquals(4, FailoverGroup.sample(pool, random = Random(1)).size)
    }

    @Test
    fun `the group always leaves room for the servers alongside it`() {
        // Whatever the mobile list costs, the three parts have to fit in one selector group:
        // a draw from a longer list than the core holds is a switch that cannot happen.
        for (mobile in 0..40) {
            val spares = (FailoverGroup.roomFor(mobile) - 1).coerceAtLeast(0)
            assertTrue(
                "selected + mobile + spares must fit in one selector group",
                1 + mobile + spares <= maxOf(FailoverGroup.SWITCHABLE, 1 + mobile),
            )
        }
        // And a mobile list that fills the group on its own leaves no spares at all, rather than
        // naming servers the core was never given.
        assertEquals(emptyList<ProxyNode>(), FailoverGroup.candidates(
            nodes = (1..5).map { node("n$it") },
            selected = node("cur"),
            latencies = emptyMap(),
            limit = FailoverGroup.roomFor(FailoverGroup.SWITCHABLE),
        ))
    }

}
