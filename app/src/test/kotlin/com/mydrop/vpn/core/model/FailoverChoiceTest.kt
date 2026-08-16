package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FailoverChoiceTest {

    private fun node(id: String) = ProxyNode(
        id = id,
        name = id,
        server = "$id.example.com",
        port = 443,
        settings = ProxySettings.Trojan(password = "p"),
    )

    private fun measured(vararg pairs: Pair<String, Int?>): Map<String, LatencyResult> =
        pairs.associate { (id, millis) ->
            id to LatencyResult(
                nodeId = id,
                millis = millis ?: 0,
                measuredAtEpochMillis = 0L,
                failed = millis == null,
            )
        }

    @Test
    fun `the straggler is left out`() {
        val nodes = listOf("a", "b", "c", "d", "slow").map(::node)
        val latencies = measured(
            "a" to 100, "b" to 100, "c" to 100, "d" to 100, "slow" to 200,
        )

        // Median 100, ceiling 150: the 200 ms server never comes back from a draw.
        val picked = (1..200).map { FailoverChoice.pick(nodes, latencies, Random(it))!!.id }.toSet()

        assertEquals(setOf("a", "b", "c", "d"), picked)
    }

    @Test
    fun `a server that did not answer is out`() {
        val nodes = listOf("alive", "dead").map(::node)
        val latencies = measured("alive" to 120, "dead" to null)

        assertEquals("alive", FailoverChoice.pick(nodes, latencies, Random(1))?.id)
    }

    @Test
    fun `a server nobody measured is out`() {
        val nodes = listOf("alive", "unknown").map(::node)

        assertEquals("alive", FailoverChoice.pick(nodes, measured("alive" to 120), Random(1))?.id)
    }

    @Test
    fun `nothing answered means nowhere to go`() {
        val nodes = listOf("x", "y").map(::node)

        assertNull(FailoverChoice.pick(nodes, measured("x" to null, "y" to null)))
        assertNull(FailoverChoice.pick(nodes, emptyMap()))
        assertNull(FailoverChoice.pick(emptyList(), measured("x" to 10)))
    }

    @Test
    fun `the pick is spread across the survivors, not always the fastest`() {
        val nodes = listOf("fast", "mid", "alsoMid").map(::node)
        // Median 110, ceiling 165: all three qualify, and the fastest holds no privilege.
        val latencies = measured("fast" to 100, "mid" to 110, "alsoMid" to 120)

        val picked = (1..300).map { FailoverChoice.pick(nodes, latencies, Random(it))!!.id }.toSet()

        assertEquals(setOf("fast", "mid", "alsoMid"), picked)
    }

    @Test
    fun `a healthy spread is not culled`() {
        // 40 and 60 against a median of 50 gives a ceiling of 75: nobody here is a straggler.
        val nodes = listOf("a", "b", "c").map(::node)
        val latencies = measured("a" to 40, "b" to 50, "c" to 60)

        val picked = (1..300).map { FailoverChoice.pick(nodes, latencies, Random(it))!!.id }.toSet()

        assertEquals(setOf("a", "b", "c"), picked)
    }

    @Test
    fun `one survivor is chosen without a draw`() {
        val nodes = listOf("only").map(::node)

        assertEquals("only", FailoverChoice.pick(nodes, measured("only" to 900), Random(7))?.id)
    }

    @Test
    fun `an even pool takes the median between the two middle servers`() {
        // Sorted 100/100/300/300: median 200, ceiling 300, so nothing is excluded.
        val nodes = listOf("a", "b", "c", "d").map(::node)
        val latencies = measured("a" to 100, "b" to 100, "c" to 300, "d" to 300)

        val picked = (1..300).map { FailoverChoice.pick(nodes, latencies, Random(it))!!.id }.toSet()

        assertEquals(setOf("a", "b", "c", "d"), picked)
    }

    @Test
    fun `a lone outlier cannot drag the cut up to save itself`() {
        // The mean here is 280 and would clear a 1000 ms server at +50%. The median is 100.
        val nodes = listOf("a", "b", "c", "d", "awful").map(::node)
        val latencies = measured(
            "a" to 100, "b" to 100, "c" to 100, "d" to 100, "awful" to 1000,
        )

        val picked = (1..200).map { FailoverChoice.pick(nodes, latencies, Random(it))!!.id }.toSet()

        assertTrue("awful" !in picked)
    }
}
