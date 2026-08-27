package com.mydrop.vpn.core.model

import com.mydrop.vpn.core.model.FailoverPolicy.Decision
import com.mydrop.vpn.core.model.FailoverPolicy.MAX_FAILBACKS
import com.mydrop.vpn.core.model.FailoverPolicy.SWITCH_COOLDOWN_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {

    private fun decide(
        failures: Int = 0,
        recoveries: Int = 0,
        hasHome: Boolean = false,
        failbacks: Int = 0,
        sinceSwitch: Long = Long.MAX_VALUE / 2,
        afterHandover: Boolean = false,
    ) = FailoverPolicy.decide(
        failures, recoveries, hasHome, failbacks, sinceSwitch, afterHandover,
    )

    @Test
    fun `a server that is answering is left alone`() {
        assertEquals(Decision.Hold, decide())
    }

    /**
     * One failure is now enough, which reverses what 0.3.2 taught — and the reason it is safe is
     * not that the threshold got braver but that the probe got better. It asks for a page through
     * the tunnel rather than shaking hands with a port, so a failure means traffic did not get
     * through. Waiting for a second one only adds an outage.
     */
    @Test
    fun `one failed probe is enough to leave`() {
        assertEquals(Decision.LeaveCurrent, decide(failures = 1))
    }

    /** The rate limit, not the count, is what keeps a single failure from causing a flap. */
    @Test
    fun `a single failure still cannot outrun the cooldown`() {
        assertEquals(Decision.Hold, decide(failures = 1, sinceSwitch = 60_000L))
        assertEquals(
            Decision.LeaveCurrent,
            decide(failures = 1, sinceSwitch = FailoverPolicy.SWITCH_COOLDOWN_MILLIS),
        )
    }

    // ------------------------------------------------------------------ Handover

    /**
     * A handover is evidence rather than noise: the route the tunnel was riding is gone, so the
     * question of whether the server survived it deserves an answer now, not in five minutes.
     */
    @Test
    fun `a handover shortens the wait between moves without lowering the evidence`() {
        // Too soon under the ordinary limit, allowed under the handover one.
        assertEquals(Decision.Hold, decide(failures = 1, sinceSwitch = 45_000L))
        assertEquals(
            Decision.LeaveCurrent,
            decide(failures = 1, sinceSwitch = 45_000L, afterHandover = true),
        )
    }

    @Test
    fun `a handover cannot move a tunnel whose server is answering`() {
        assertEquals(Decision.Hold, decide(failures = 0, afterHandover = true))
    }

    @Test
    fun `even a handover is rate limited`() {
        assertEquals(
            Decision.Hold,
            decide(failures = 1, sinceSwitch = 5_000L, afterHandover = true),
        )
        assertTrue(
            "a handover has to be faster than the ordinary limit, not unlimited",
            FailoverPolicy.HANDOVER_COOLDOWN_MILLIS in 1 until FailoverPolicy.SWITCH_COOLDOWN_MILLIS,
        )
    }

    /** Settling has to be short enough to read as "immediately" and long enough to re-dial. */
    @Test
    fun `the settle after a handover is measured in seconds, not tens of them`() {
        assertTrue(
            "settle was ${FailoverPolicy.HANDOVER_SETTLE_MILLIS}ms",
            FailoverPolicy.HANDOVER_SETTLE_MILLIS in 1_000L..5_000L,
        )
        assertTrue(
            "settling must not outlast the interval it replaces",
            FailoverPolicy.HANDOVER_SETTLE_MILLIS < FailoverPolicy.PROBE_INTERVAL_MILLIS,
        )
    }

    @Test
    fun `two answered probes are not enough to go back`() {
        // This is the 0.3.2 threshold, and on its own it is what produced the cycle.
        assertEquals(Decision.Hold, decide(recoveries = 2, hasHome = true))
        assertEquals(Decision.Hold, decide(recoveries = 4, hasHome = true))
        assertEquals(Decision.ReturnHome, decide(recoveries = 5, hasHome = true))
    }

    @Test
    fun `returning needs more evidence than leaving`() {
        assertTrue(FailoverPolicy.PROBES_BEFORE_FAILBACK > FailoverPolicy.FAILURES_BEFORE_SWAP)
    }

    @Test
    fun `no move happens inside the cooldown, in either direction`() {
        assertEquals(Decision.Hold, decide(failures = 9, sinceSwitch = 0))
        assertEquals(
            Decision.Hold,
            decide(recoveries = 9, hasHome = true, sinceSwitch = SWITCH_COOLDOWN_MILLIS - 1),
        )
        assertEquals(
            Decision.LeaveCurrent,
            decide(failures = 2, sinceSwitch = SWITCH_COOLDOWN_MILLIS),
        )
    }

    @Test
    fun `a server returned to too often is given up on rather than chased`() {
        assertEquals(
            Decision.ReturnHome,
            decide(recoveries = 5, hasHome = true, failbacks = MAX_FAILBACKS - 1),
        )
        assertEquals(
            Decision.AbandonHome,
            decide(recoveries = 5, hasHome = true, failbacks = MAX_FAILBACKS),
        )
    }

    /** Giving up moves nothing, so it does not queue behind a cooldown. */
    @Test
    fun `giving up does not wait for the cooldown`() {
        assertEquals(
            Decision.AbandonHome,
            decide(recoveries = 5, hasHome = true, failbacks = MAX_FAILBACKS, sinceSwitch = 0),
        )
    }

    @Test
    fun `with nowhere to go back to, only leaving is on the table`() {
        assertEquals(Decision.Hold, decide(recoveries = 99, hasHome = false))
        assertEquals(Decision.LeaveCurrent, decide(failures = 2, recoveries = 99, hasHome = false))
    }

    @Test
    fun `the probe speeds up only once something has failed`() {
        assertEquals(
            FailoverPolicy.PROBE_INTERVAL_MILLIS,
            FailoverPolicy.nextProbeDelayMillis(0),
        )
        assertEquals(
            FailoverPolicy.SUSPECT_PROBE_INTERVAL_MILLIS,
            FailoverPolicy.nextProbeDelayMillis(1),
        )
        assertTrue(
            "a suspect server has to be re-checked sooner than a healthy one",
            FailoverPolicy.SUSPECT_PROBE_INTERVAL_MILLIS < FailoverPolicy.PROBE_INTERVAL_MILLIS,
        )
    }

    /**
     * The cost of noticing, stated as time rather than as constants.
     *
     * 0.3.2 needed two failures on a flat twenty-second cadence: fifty seconds with a five-second
     * probe timeout, and that is what a user actually sat through. It now takes one interval and
     * one timeout, and a handover skips even the interval.
     */
    @Test
    fun `noticing a dead server costs one interval, not two`() {
        val probeTimeout = 5_000L
        var elapsed = 0L
        var failures = 0
        while (failures < FailoverPolicy.FAILURES_BEFORE_SWAP) {
            elapsed += FailoverPolicy.nextProbeDelayMillis(failures) + probeTimeout
            failures++
        }

        val oldWorstCase = (FailoverPolicy.PROBE_INTERVAL_MILLIS + probeTimeout) * 2
        assertTrue("took ${elapsed}ms, 0.3.2 took ${oldWorstCase}ms", elapsed < oldWorstCase)
        assertTrue("took ${elapsed}ms, expected under 30s", elapsed <= 30_000L)

        // And the path that matters when the network changes underneath the tunnel.
        val afterHandover = FailoverPolicy.HANDOVER_SETTLE_MILLIS + probeTimeout
        assertTrue("handover path took ${afterHandover}ms", afterHandover <= 10_000L)
    }

    /**
     * The regression itself, played out.
     *
     * The pattern matters: a server alternating on every single probe never accumulates two of
     * anything in a row and so trips nothing. What produced the fault in the field is a server
     * that goes down for a while and comes back for a while — modelled here as two failures then
     * two successes, the shortest cycle that clears the 0.3.2 thresholds in both directions.
     *
     * Under those thresholds this run moves the tunnel thirty times in twenty minutes, one every
     * forty seconds, and every move reloads the core and kills every connection it carries. The
     * cooldown is what bounds it, so that is what this asserts.
     */
    @Test
    fun `a server that comes and goes cannot bounce the tunnel more than the cooldown allows`() {
        val runMillis = 20 * 60_000L

        var now = 0L
        var lastSwitch = -SWITCH_COOLDOWN_MILLIS
        var failures = 0
        var recoveries = 0
        var failbacks = 0
        var hasHome = false
        var moves = 0
        var probe = 0

        while (now < runMillis) {
            // The adaptive interval, so the guarantee is measured against what actually runs:
            // probing faster while suspicious must not buy any extra switches.
            now += FailoverPolicy.nextProbeDelayMillis(failures)
            // Two down, two up, repeating.
            val answering = (probe / 2) % 2 == 1
            probe++

            recoveries = if (hasHome && answering) recoveries + 1 else 0
            failures = if (answering) 0 else failures + 1

            when (
                FailoverPolicy.decide(
                    consecutiveFailures = failures,
                    consecutiveHomeRecoveries = recoveries,
                    hasHome = hasHome,
                    failbacksSoFar = failbacks,
                    millisSinceLastSwitch = now - lastSwitch,
                )
            ) {
                Decision.Hold -> Unit
                Decision.AbandonHome -> { hasHome = false; recoveries = 0 }
                Decision.ReturnHome -> {
                    moves++; lastSwitch = now; hasHome = false; recoveries = 0; failbacks++
                }
                Decision.LeaveCurrent -> {
                    moves++; lastSwitch = now; hasHome = true; failures = 0
                }
            }
        }

        val ceiling = (runMillis / SWITCH_COOLDOWN_MILLIS).toInt() + 1
        assertTrue("moved $moves times in 20 minutes, ceiling is $ceiling", moves <= ceiling)
    }

    /**
     * The same run against the thresholds 0.3.2 shipped with, so the test above is known to be
     * measuring something. Two probes either way and no cooldown moves the tunnel every forty
     * seconds; if that ever stops being true, the guard above has stopped guarding.
     */
    @Test
    fun `the thresholds that shipped in 0-3-2 would bounce it constantly`() {
        var failures = 0
        var recoveries = 0
        var hasHome = false
        var moves = 0

        repeat(60) { probe ->
            val answering = (probe / 2) % 2 == 1
            recoveries = if (hasHome && answering) recoveries + 1 else 0
            failures = if (answering) 0 else failures + 1

            // Two, in both directions, with nothing rate-limiting the result.
            if (hasHome && recoveries >= 2) {
                moves++; hasHome = false; recoveries = 0
            } else if (failures >= 2) {
                moves++; hasHome = true; failures = 0
            }
        }

        assertTrue("the old thresholds moved it only $moves times", moves >= 25)
    }

    // ------------------------------------------------------------------ Worst cases

    /**
     * Runs the policy against a scripted server for a stretch of time and reports *when* the
     * tunnel moved.
     *
     * Times rather than a count, because the count alone cannot tell a flap from a design: two
     * moves five minutes apart are a departure and a considered return, while two moves twenty
     * seconds apart are the fault this policy exists to prevent.
     *
     * @param answering what the probe returns, by probe number.
     * @param handoverEveryMillis how often the network changes underneath, or null for never.
     *   A handover cuts the wait short and then costs the settle, exactly as the watchdog does it.
     */
    private fun simulate(
        runMillis: Long,
        handoverEveryMillis: Long? = null,
        answering: (Int) -> Boolean,
    ): List<Long> {
        var now = 0L
        var lastSwitch = -SWITCH_COOLDOWN_MILLIS
        var nextHandover = handoverEveryMillis ?: Long.MAX_VALUE
        var failures = 0
        var recoveries = 0
        var failbacks = 0
        var hasHome = false
        val moves = mutableListOf<Long>()
        var probe = 0

        while (now < runMillis) {
            val wait = FailoverPolicy.nextProbeDelayMillis(failures)
            val handover = now + wait >= nextHandover
            if (handover) {
                now = nextHandover + FailoverPolicy.HANDOVER_SETTLE_MILLIS
                nextHandover += handoverEveryMillis ?: 0L
            } else {
                now += wait
            }

            val alive = answering(probe)
            probe++

            recoveries = if (hasHome && alive) recoveries + 1 else 0
            failures = if (alive) 0 else failures + 1

            when (
                FailoverPolicy.decide(
                    consecutiveFailures = failures,
                    consecutiveHomeRecoveries = recoveries,
                    hasHome = hasHome,
                    failbacksSoFar = failbacks,
                    millisSinceLastSwitch = now - lastSwitch,
                    afterHandover = handover,
                )
            ) {
                Decision.Hold -> Unit
                Decision.AbandonHome -> { hasHome = false; recoveries = 0 }
                Decision.ReturnHome -> {
                    moves += now; lastSwitch = now; hasHome = false; recoveries = 0; failbacks++
                }
                Decision.LeaveCurrent -> {
                    moves += now; lastSwitch = now; hasHome = true; failures = 0
                }
            }
        }
        return moves
    }

    /**
     * The pattern the new threshold makes dangerous, and the reason this section exists.
     *
     * A server that fails every other probe never accumulated two failures in a row, so under the
     * old threshold of two it tripped nothing at all — it was invisible. At one failure it trips on
     * every single bad probe, which is precisely the flapping 0.3.2 was cured of. Nothing but the
     * cooldown stands between that pattern and a tunnel that reloads every twenty-five seconds.
     */
    @Test
    fun `a server failing every other probe cannot bounce the tunnel`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis) { probe -> probe % 2 == 0 }
        val ceiling = (runMillis / SWITCH_COOLDOWN_MILLIS).toInt() + 1
        assertTrue(
            "moved ${moves.size} times in 20 minutes, ceiling is $ceiling",
            moves.size <= ceiling,
        )
    }

    /** The same, at the worst rate there is: nothing ever answers. */
    @Test
    fun `a permanently dead server cannot bounce the tunnel either`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis) { false }
        val ceiling = (runMillis / SWITCH_COOLDOWN_MILLIS).toInt() + 1
        assertTrue(
            "moved ${moves.size} times in 20 minutes, ceiling is $ceiling",
            moves.size <= ceiling,
        )
    }

    /**
     * The false positive that would matter most, because it is the everyday case: walking through
     * a building, in and out of Wi-Fi, with a server that is perfectly fine the whole time.
     *
     * A handover shortens the wait and lowers the rate limit, so if it also lowered the evidence
     * this would move the tunnel on every step. It must move it exactly never.
     */
    @Test
    fun `a handover storm cannot move a tunnel whose server is answering`() {
        val moves = simulate(runMillis = 20 * 60_000L, handoverEveryMillis = 10_000L) { true }
        assertEquals("a healthy server was moved ${moves.size} times by handovers", 0, moves.size)
    }

    /**
     * And the genuinely worst case: the network flapping every ten seconds *and* the server dead.
     *
     * Here the handover cooldown is doing the bounding rather than the ordinary one, which is the
     * whole point of it being separate — so the ceiling is higher, and it has to be checked rather
     * than assumed. The backstop this cannot see is in the watchdog: when every candidate is dead
     * too, `swapAwayFrom` logs it and moves nothing, so a total outage does not churn at all.
     */
    @Test
    fun `a handover storm over a dead server stays within the handover cooldown`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis, handoverEveryMillis = 10_000L) { false }
        val ceiling = (runMillis / FailoverPolicy.HANDOVER_COOLDOWN_MILLIS).toInt() + 1
        assertTrue(
            "moved ${moves.size} times in 20 minutes, ceiling is $ceiling",
            moves.size <= ceiling,
        )
        assertTrue("a dead server in a storm should still be left once", moves.isNotEmpty())
        // The spacing is the guarantee, not the count: no two moves closer than the limit allows.
        moves.zipWithNext { a, b ->
            assertTrue(
                "two moves ${b - a}ms apart, limit is ${FailoverPolicy.HANDOVER_COOLDOWN_MILLIS}ms",
                b - a >= FailoverPolicy.HANDOVER_COOLDOWN_MILLIS,
            )
        }
    }

    /**
     * The shape that used to be the regression, re-checked at the new threshold: down for a while,
     * up for a while, which clears both directions and can therefore cycle.
     */
    @Test
    fun `a server that comes and goes cannot bounce the tunnel during a handover storm`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis, handoverEveryMillis = 15_000L) { probe ->
            (probe / 2) % 2 == 1
        }
        val ceiling = (runMillis / FailoverPolicy.HANDOVER_COOLDOWN_MILLIS).toInt() + 1
        assertTrue(
            "moved ${moves.size} times in 20 minutes, ceiling is $ceiling",
            moves.size <= ceiling,
        )
    }

    /**
     * The price of the lowered threshold, stated plainly rather than assumed away.
     *
     * One probe failing in isolation — a lost packet, a moment of congestion — is the single most
     * likely thing to happen to a perfectly good tunnel, and at a threshold of one it is enough to
     * move. Under the old threshold of two it cost nothing at all; it now costs **two** tunnel
     * reloads: the departure, and the considered return to the server the user actually chose.
     *
     * That is the trade, and it is only acceptable because of how the two are spaced. The return
     * needs five answered probes *and* a cooled-down clock, so the second interruption lands a
     * full cooldown after the first rather than seconds later. Two moves five minutes apart is a
     * provisional switch working as designed; the same two twenty seconds apart would be the
     * flapping of 0.3.2. So the spacing is asserted here, not just the count.
     */
    @Test
    fun `a single isolated failure costs a move away and a move back, well spaced`() {
        val moves = simulate(runMillis = 20 * 60_000L) { probe -> probe != 3 }
        assertTrue("one lost probe caused ${moves.size} moves", moves.size <= 2)
        moves.zipWithNext { away, back ->
            assertTrue(
                "left and returned ${back - away}ms apart, cooldown ${SWITCH_COOLDOWN_MILLIS}ms",
                back - away >= SWITCH_COOLDOWN_MILLIS,
            )
        }
    }
}
