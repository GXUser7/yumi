package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget that keeps a shouting core from evicting the journal it is shouting in.
 *
 * Two real journals set the numbers here. The storm: eight hundred and forty-one lines a second
 * for two minutes, which is what a hundred and three thousand core lines in a two-minute file
 * works out to. And ordinary use on Xray: fifty thousand core lines over a three-hour phone
 * session, bursty rather than steady, peaking at two hundred and thirty-three a second sustained
 * over ten seconds. The whole design is about telling those two apart.
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

    /**
     * The case the flat cap got wrong: a page opening forty connections at once.
     *
     * This is not a fault and must not be reported as one. Under the old per-second cap the same
     * traffic threw away thirty-nine per cent of the core's lines across a session where nothing
     * was wrong, and raised the alarm ninety-six times.
     */
    @Test
    fun `an ordinary burst passes whole`() {
        var written = 0
        var alarms = 0
        var now = 1_000_000L

        // The peak second measured on a phone, and then the quiet that follows a page finishing
        // loading. Repeated, because one burst proves nothing about the second one.
        repeat(10) {
            repeat(233) {
                val verdict = chatter.admit(now)
                if (verdict.write) written++
                if (verdict.suppressed > 0) alarms++
            }
            now += 5_000
        }

        assertEquals("every line of an ordinary burst belongs in the journal", 2330, written)
        assertEquals("ordinary browsing must not raise the alarm", 0, alarms)
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
     * The burst is an allowance, not a rate: the sustained figure is what decides whether a
     * fifty-megabyte file still covers the hour before somebody noticed.
     */
    @Test
    fun `a long storm is held to the refill rate, not the burst`() {
        var now = 1_000_000L
        repeat(30) {
            repeat(841) { chatter.admit(now) }
            now += 1_000
        }

        // The bucket is empty by now, so the next minute measures the steady state alone.
        var written = 0
        repeat(60) {
            repeat(841) { if (chatter.admit(now).write) written++ }
            now += 1_000
        }

        assertTrue("kept $written a minute — that is not sixty a second", written in 3_000..4_200)
    }

    /** Idling does not bank a bigger spike than the bucket holds. */
    @Test
    fun `the allowance does not accumulate past the cap`() {
        var now = 1_000_000L
        chatter.admit(now)

        now += 60L * 60 * 1_000
        var written = 0
        repeat(5_000) { if (chatter.admit(now).write) written++ }

        assertTrue("an hour of quiet banked $written lines", written <= CoreChatter.BURST + 1)
    }

    /**
     * The count is the most useful line in the file: a core wanting to write eight hundred lines a
     * second is a core in trouble, whatever else the journal says.
     */
    @Test
    fun `what was dropped is reported, not lost silently`() {
        var now = 1_000_000L
        val asked = CoreChatter.BURST + 440
        repeat(asked) { chatter.admit(now) }

        now += 1_000
        val rollover = chatter.admit(now)

        assertEquals("every line over the budget must be accounted for", 440, rollover.suppressed)
    }

    /**
     * A window can end without a single line getting through, and those are the windows worth
     * knowing about. The count belongs to the window that ended, not to whichever line happens to
     * be admitted next — so it is reported on a refused line too, or a storm bad enough to starve
     * every window would be the one storm that goes unrecorded.
     *
     * Written with a bucket that never refills, because the shipped one always has a token by the
     * time a window rolls; this is about the rule, not about today's constants.
     */
    @Test
    fun `the report survives a window in which nothing was written`() {
        val starved = CoreChatter(perSecond = 0, burst = 10)
        var now = 1_000_000L
        repeat(50) { starved.admit(now) }

        now += 1_000
        val rollover = starved.admit(now)

        assertTrue("a refused line still has to carry the report", !rollover.write)
        assertEquals(40, rollover.suppressed)
    }

    /** Reported once, not on every line of the next window. */
    @Test
    fun `the report does not repeat`() {
        var now = 1_000_000L
        repeat(CoreChatter.BURST + 440) { chatter.admit(now) }
        now += 1_000
        assertTrue(chatter.admit(now).suppressed > 0)

        repeat(10) {
            assertEquals(0, chatter.admit(now).suppressed)
        }
    }

    /** A quiet window after a loud one has nothing to report. */
    @Test
    fun `a window that dropped nothing reports nothing`() {
        var now = 1_000_000L
        repeat(CoreChatter.BURST + 440) { chatter.admit(now) }
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
        repeat(CoreChatter.BURST + 440) { chatter.admit(now) }

        chatter.reset()

        val after = chatter.admit(now)
        assertTrue(after.write)
        assertEquals("a reset must not carry the old window's arrears", 0, after.suppressed)
    }
}
