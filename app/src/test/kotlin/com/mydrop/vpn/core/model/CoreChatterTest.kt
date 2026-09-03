package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget that keeps a shouting core from evicting the journal it is shouting in.
 *
 * The numbers come from a real journal: eight hundred and forty-one lines a second for two
 * minutes, which is what a hundred and three thousand core lines in a two-minute file works out
 * to.
 */
class CoreChatterTest {

    private val chatter = CoreChatter()

    /** Ordinary running writes a few lines a second and must not notice this exists. */
    @Test
    fun `a quiet core is never touched`() {
        var now = 1_000_000L
        repeat(60) { second ->
            repeat(5) {
                val verdict = chatter.admit(now)
                assertTrue("dropped a line in second $second", verdict.write)
                assertEquals(0, verdict.suppressed)
            }
            now += 1_000
        }
    }

    /** The storm, at the rate it actually ran. */
    @Test
    fun `a storming core is cut to the budget`() {
        var written = 0
        var now = 1_000_000L
        repeat(10) {
            repeat(841) {
                if (chatter.admit(now).write) written++
            }
            now += 1_000
        }

        assertTrue("kept $written of 8410 — the budget did nothing", written < 8410 / 5)
        assertTrue("kept $written — the journal needs some of them", written > 0)
    }

    /**
     * The count is the most useful line in the file: a core wanting to write eight hundred lines a
     * second is a core in trouble, whatever else the journal says.
     */
    @Test
    fun `what was dropped is reported, not lost silently`() {
        var now = 1_000_000L
        repeat(500) { chatter.admit(now) }

        now += 1_000
        val rollover = chatter.admit(now)

        assertTrue(rollover.write)
        assertEquals("every line over the budget must be accounted for", 440, rollover.suppressed)
    }

    /** Reported once, not on every line of the next window. */
    @Test
    fun `the report does not repeat`() {
        var now = 1_000_000L
        repeat(500) { chatter.admit(now) }
        now += 1_000
        chatter.admit(now)

        repeat(10) {
            assertEquals(0, chatter.admit(now).suppressed)
        }
    }

    /** A quiet window after a loud one has nothing to report. */
    @Test
    fun `a window that dropped nothing reports nothing`() {
        var now = 1_000_000L
        repeat(500) { chatter.admit(now) }
        now += 1_000
        assertTrue(chatter.admit(now).suppressed > 0)

        now += 1_000
        assertEquals(0, chatter.admit(now).suppressed)
    }

    /** The first line ever seen is written, whatever the clock happens to read. */
    @Test
    fun `the first line is not charged to a window that never began`() {
        val verdict = CoreChatter().admit(System.currentTimeMillis())

        assertTrue(verdict.write)
        assertEquals(0, verdict.suppressed)
    }

    /** Forgotten along with the core that was shouting. */
    @Test
    fun `a reset returns the whole budget`() {
        val now = 1_000_000L
        repeat(500) { chatter.admit(now) }

        chatter.reset()

        val after = chatter.admit(now)
        assertTrue(after.write)
        assertEquals("a reset must not carry the old window's arrears", 0, after.suppressed)
    }
}
