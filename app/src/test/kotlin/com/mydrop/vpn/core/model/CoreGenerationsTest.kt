package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Counting the cores that are actually running.
 *
 * The numbers in these tests are taken from a real journal: three instances writing across the
 * same eleven minutes, ages advancing in step with the clock, the oldest fifty-two minutes old.
 * That is the shape the counter has to recognise, and the shape a single reconnecting core must
 * not be mistaken for.
 */
class CoreGenerationsTest {

    private val start = 1_700_000_000_000L

    /** One core, writing for a while: its age grows exactly as fast as the clock. */
    @Test
    fun `a single core stays a single core however long it runs`() {
        val counter = CoreGenerations()
        for (second in 0..600 step 3) {
            counter.observe("INFO[%04d] router: something".format(second), start + second * 1000L)
        }
        assertEquals(1, counter.liveCores(start + 600_000L))
        assertNull("one core is not a leak", counter.dueReport(start + 600_000L))
    }

    /**
     * The journal's own case. Two ages far apart in the same instant cannot come from one logger,
     * because the age is measured from the instant its own box was created.
     */
    @Test
    fun `two ages in the same second are two cores`() {
        val counter = CoreGenerations()
        val now = start + 3_000_000L
        counter.observe("INFO[2545] outbound/vless[node-4fcf1b4c]: dial", now)
        counter.observe("INFO[0481] outbound/vless[node-2e904f37]: dial", now)

        assertEquals(2, counter.liveCores(now))
        val report = counter.dueReport(now)
        assertNotNull(report)
        assertEquals(2, report!!.liveCores)
        // The older of the two is 2545 seconds old, and that is what the warning has to say.
        assertEquals(2_545_000L, report.oldestAgeMillis)
    }

    /**
     * A core that stops writing has gone. Without this the count would only ever climb, and every
     * ordinary reconnect would eventually look like a leak.
     */
    @Test
    fun `a core that stops writing stops being counted`() {
        val counter = CoreGenerations()
        counter.observe("INFO[2545] old core", start)
        counter.observe("INFO[0010] new core", start)
        assertEquals(2, counter.liveCores(start))

        // Only the new one keeps writing, well past the live window.
        var now = start
        repeat(10) {
            now += 5_000L
            counter.observe("INFO[%04d] new core".format(10 + (now - start) / 1000), now)
        }
        assertEquals("the silent one is gone", 1, counter.liveCores(now))
    }

    /**
     * An ordinary reconnect replaces one core with another, and for a moment both may have written
     * recently. What must not happen is the counter treating the *same* core as two because its
     * age estimate wobbled by a second between lines.
     */
    @Test
    fun `a wobbling estimate does not split one core in two`() {
        val counter = CoreGenerations()
        // Same core, but lines arrive in batches so the arrival time drifts against the whole-second
        // age: the derived start moves around by a couple of seconds.
        counter.observe("INFO[0100] a", start)
        counter.observe("INFO[0100] b", start + 900L)
        counter.observe("INFO[0101] c", start + 1_100L)
        counter.observe("INFO[0101] d", start + 1_950L)
        assertEquals(1, counter.liveCores(start + 2_000L))
    }

    /** The warning is rate-limited: the condition lasts minutes, the lines arrive by the thousand. */
    @Test
    fun `the warning is not repeated on every line`() {
        val counter = CoreGenerations()
        counter.observe("INFO[2545] old", start)
        counter.observe("INFO[0481] new", start)

        assertNotNull(counter.dueReport(start))
        assertNull("immediately again", counter.dueReport(start + 1_000L))
        assertNull("still inside the interval", counter.dueReport(start + 30_000L))

        // Both still writing a couple of minutes later, so the warning is worth repeating.
        counter.observe("INFO[2665] old", start + 120_000L)
        counter.observe("INFO[0601] new", start + 120_000L)
        assertNotNull(counter.dueReport(start + 120_000L))
    }

    /** Lines the app writes itself carry no age, and must not be mistaken for a core. */
    @Test
    fun `lines without an age prefix are ignored`() {
        assertNull(CoreGenerations.uptimeSecondsOf("tearing the core down"))
        assertNull(CoreGenerations.uptimeSecondsOf(""))
        assertNull(CoreGenerations.uptimeSecondsOf("switched to Latvia without restarting the core"))
        // A connection id in brackets is not an age, and it is not preceded by a level.
        assertNull(CoreGenerations.uptimeSecondsOf("[1939946637 0ms] outbound/vless: dial"))
    }

    /** Every level sing-box writes, and the four-digit padding it uses. */
    @Test
    fun `an age is read from any level`() {
        assertEquals(2545L, CoreGenerations.uptimeSecondsOf("INFO[2545] router"))
        assertEquals(481L, CoreGenerations.uptimeSecondsOf("ERROR[0481] connection"))
        assertEquals(0L, CoreGenerations.uptimeSecondsOf("WARN[0000] starting"))
        assertEquals(12345L, CoreGenerations.uptimeSecondsOf("DEBUG[12345] long session"))
    }

    /** A tunnel torn down for real starts the count over rather than carrying ghosts forward. */
    @Test
    fun `a teardown forgets everything`() {
        val counter = CoreGenerations()
        counter.observe("INFO[2545] old", start)
        counter.observe("INFO[0481] new", start)
        assertEquals(2, counter.liveCores(start))

        counter.reset()

        assertEquals(0, counter.liveCores(start))
        counter.observe("INFO[0001] fresh", start + 1_000L)
        assertEquals(1, counter.liveCores(start + 1_000L))
        assertNull(counter.dueReport(start + 1_000L))
    }
}
