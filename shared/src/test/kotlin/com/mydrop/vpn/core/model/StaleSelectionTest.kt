package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a subscription refresh may and may not do to lists that name servers by id.
 *
 * Written from the failure it prevents: a provider rotates a key, every id moves, and the lists
 * the user filled in by hand are left pointing at servers that no longer exist. Nothing complains
 * — the settings screen goes on counting the ghosts as a number somebody chose — and the tunnel
 * sits on a dead server reporting there is nothing to replace it with.
 */
class StaleSelectionTest {

    private val alive = setOf("a", "b", "c")

    @Test
    fun `ids that vanished leave both lists`() {
        val pruned = StaleSelection.prune(
            alive = alive,
            failover = setOf("a", "gone"),
            mobile = setOf("b", "also-gone"),
        )!!

        assertEquals(setOf("a"), pruned.failover)
        assertEquals(setOf("b"), pruned.mobile)
        assertEquals(1, pruned.lostFailover)
        assertEquals(1, pruned.lostMobile)
    }

    /** Nothing lost means nothing written and nothing said. */
    @Test
    fun `an unchanged refresh reports nothing`() {
        assertNull(StaleSelection.prune(alive, setOf("a", "b"), setOf("c")))
        assertNull(StaleSelection.prune(alive, emptySet(), emptySet()))
    }

    /**
     * The case that would quietly destroy a choice: a profile that has not loaded yet looks
     * exactly like one with nothing in it.
     */
    @Test
    fun `an empty profile prunes nothing at all`() {
        assertNull(StaleSelection.prune(emptySet(), setOf("a", "gone"), setOf("b")))
    }

    /** Each list is judged on its own; one surviving intact must not silence the other. */
    @Test
    fun `losing from one list leaves the other alone`() {
        val pruned = StaleSelection.prune(alive, setOf("a", "b"), setOf("gone"))!!

        assertEquals(setOf("a", "b"), pruned.failover)
        assertEquals(0, pruned.lostFailover)
        assertTrue(pruned.mobile.isEmpty())
        assertTrue(pruned.mobileEmptied)
    }

    /**
     * Emptied is not the same as empty. A list that was already empty has lost nothing, and
     * saying it was emptied would report a feature switching itself off that nobody had on.
     */
    @Test
    fun `a list that was already empty was not emptied`() {
        val pruned = StaleSelection.prune(alive, setOf("gone"), emptySet())!!

        assertTrue(pruned.failoverEmptied)
        assertEquals(0, pruned.lostMobile)
        assertTrue(!pruned.mobileEmptied)
    }
}
