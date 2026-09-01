package com.mydrop.vpn.core.model

import com.mydrop.vpn.core.model.FailoverPolicy.Decision
import com.mydrop.vpn.core.model.FailoverPolicy.FAILURES_BEFORE_SWAP
import com.mydrop.vpn.core.model.FailoverPolicy.MAX_FAILBACKS
import com.mydrop.vpn.core.model.FailoverPolicy.SWITCH_COOLDOWN_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * One failed probe is not enough, and the second one is what tells two very different things
     * apart: a server that stopped carrying traffic, and a phone whose own connection dropped a
     * request. A journal caught the difference costing three servers in nine minutes on a walk.
     * The confirmation is deliberately cheap — three seconds, not another full interval.
     */
    @Test
    fun `one failed probe is not enough to leave`() {
        assertEquals(Decision.Hold, decide(failures = 1))
        assertEquals(Decision.LeaveCurrent, decide(failures = FAILURES_BEFORE_SWAP))
    }

    /** The rate limit, not the count, is what keeps a run of failures from causing a flap. */
    @Test
    fun `a confirmed failure still cannot outrun the escape limit`() {
        assertEquals(Decision.Hold, decide(failures = FAILURES_BEFORE_SWAP, sinceSwitch = 10_000L))
        assertEquals(
            Decision.LeaveCurrent,
            decide(
                failures = FAILURES_BEFORE_SWAP,
                sinceSwitch = FailoverPolicy.ESCAPE_COOLDOWN_MILLIS,
            ),
        )
    }

    // ------------------------------------------------------------------ Handover

    /**
     * A handover buys an early probe, not a lowered bar. The value of it is in the watchdog, which
     * stops waiting and asks at once; the answer it gets is judged exactly as any other.
     */
    @Test
    fun `a handover cannot move a tunnel whose server is answering`() {
        assertEquals(Decision.Hold, decide(failures = 0, afterHandover = true))
    }

    @Test
    fun `a handover does not exempt a move from the escape limit`() {
        assertEquals(
            Decision.Hold,
            decide(failures = FAILURES_BEFORE_SWAP, sinceSwitch = 5_000L, afterHandover = true),
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
     * The cost of noticing, stated as time rather than as constants — because time is what the
     * user sits through, and constants are what gets changed without anybody adding it up.
     *
     * Two failures are required again, as in 0.3.2, but they no longer cost what they cost then.
     * 0.3.2 waited a flat twenty seconds between both: fifty seconds with a five-second probe
     * timeout. The confirming probe now comes three seconds after the first, so the second failure
     * is nearly free and the whole budget is one ordinary interval plus two timeouts.
     *
     * That is the deal being asserted here: asking twice is worth it only while asking twice stays
     * cheap. Should the suspect interval ever drift back towards the ordinary one, this fails.
     */
    @Test
    fun `confirming a dead server costs seconds, not another interval`() {
        val probeTimeout = 5_000L
        var elapsed = 0L
        var failures = 0
        while (failures < FailoverPolicy.FAILURES_BEFORE_SWAP) {
            elapsed += FailoverPolicy.nextProbeDelayMillis(failures) + probeTimeout
            failures++
        }

        val oldWorstCase = (FailoverPolicy.PROBE_INTERVAL_MILLIS + probeTimeout) * 2
        assertTrue("took ${elapsed}ms, 0.3.2 took ${oldWorstCase}ms", elapsed < oldWorstCase)
        assertTrue("took ${elapsed}ms, expected under 35s", elapsed <= 35_000L)

        // The confirmation itself is the part that has to stay cheap.
        val confirmation = FailoverPolicy.SUSPECT_PROBE_INTERVAL_MILLIS + probeTimeout
        assertTrue("confirming took ${confirmation}ms", confirmation <= 10_000L)

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
    fun `a server that comes and goes cannot bounce the tunnel`() {
        val moves = simulate(runMillis = 20 * 60_000L) { probe -> (probe / 2) % 2 == 1 }
        assertPaced(moves, "a server that comes and goes")
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

    // ------------------------------------------------------------------ The 07:44 outage

    /**
     * Replayed from a field journal, line for line.
     *
     * The user reported the internet dropping. The server they were on answered a ping the whole
     * time — its port was open — but nothing crossed the tunnel, which is the ordinary shape of
     * interference and exactly what the through-tunnel probe exists to catch. It caught it:
     *
     * ```
     * 07:44:30  tunnel=false alive=false  fail=18/2  recover=18/5  sinceSwitch=171s  -> Hold
     * 07:44:35  tunnel=false alive=false  fail=19/2  recover=19/5  sinceSwitch=176s  -> Hold
     *      ...  thirty-four consecutive failures, every one of them Hold ...
     * 07:46:47  tunnel=false alive=false  fail=34/2  recover=34/5  sinceSwitch=309s  -> ReturnHome
     * ```
     *
     * Detection was never the problem. The decision was: the home-return branch came first and
     * returned unconditionally, so once the server to go back to had answered five probes the
     * policy stopped asking whether the one carrying traffic was alive at all. Five minutes of no
     * internet, spent waiting out a rate limit whose purpose is to stop the tunnel flapping
     * between servers that work.
     *
     * Note what would NOT have fixed this: lowering the failure threshold. At `fail=18` it was
     * already eighteen times over the old threshold of two. Execution never reached that check.
     */
    @Test
    fun `a dead server is left even while the home server is begging to be returned to`() {
        assertEquals(
            Decision.LeaveCurrent,
            decide(
                failures = 18,
                recoveries = 18,
                hasHome = true,
                sinceSwitch = 171_000L,
            ),
        )
    }

    /** The same moment one probe in, now that one failure is enough. */
    @Test
    fun `the very first failure outranks a pending return home`() {
        assertEquals(
            Decision.LeaveCurrent,
            decide(
                failures = FAILURES_BEFORE_SWAP,
                recoveries = 9,
                hasHome = true,
                sinceSwitch = 171_000L,
            ),
        )
    }

    /**
     * The asymmetry, stated as the two limits. Escaping a dead server may not be charged the rate
     * limit that exists to protect a working one.
     */
    @Test
    fun `leaving is rate limited far more loosely than returning`() {
        assertTrue(
            "escape ${FailoverPolicy.ESCAPE_COOLDOWN_MILLIS}ms vs " +
                "return ${SWITCH_COOLDOWN_MILLIS}ms",
            FailoverPolicy.ESCAPE_COOLDOWN_MILLIS < SWITCH_COOLDOWN_MILLIS / 4,
        )

        // Too soon to leave, and too soon to go home.
        assertEquals(Decision.Hold, decide(failures = FAILURES_BEFORE_SWAP, sinceSwitch = 10_000L))
        // Long enough to leave, still nowhere near long enough to go home.
        assertEquals(
            Decision.LeaveCurrent,
            decide(failures = FAILURES_BEFORE_SWAP, sinceSwitch = 60_000L),
        )
        assertEquals(
            Decision.Hold,
            decide(recoveries = 5, hasHome = true, sinceSwitch = 60_000L),
        )
    }

    /** Returning home keeps its long limit; nothing is broken when that question is asked. */
    @Test
    fun `returning home still waits out the full cooldown`() {
        assertEquals(
            Decision.Hold,
            decide(recoveries = 5, hasHome = true, sinceSwitch = SWITCH_COOLDOWN_MILLIS - 1),
        )
        assertEquals(
            Decision.ReturnHome,
            decide(recoveries = 5, hasHome = true, sinceSwitch = SWITCH_COOLDOWN_MILLIS),
        )
    }

    /**
     * The escape must not eat the give-up. A server returned to and lost too many times stops
     * being somewhere to go back to, and that verdict is reached whether or not the current server
     * is also failing.
     */
    @Test
    fun `giving up on a hopeless home still happens once the tunnel is healthy`() {
        assertEquals(
            Decision.AbandonHome,
            decide(
                failures = 0,
                recoveries = 5,
                hasHome = true,
                failbacks = FailoverPolicy.MAX_FAILBACKS,
            ),
        )
    }

    // ------------------------------------------------------------------ The clock

    /**
     * The failure mode that switches failover off without saying so.
     *
     * The caller measures elapsed time with a monotonic clock, so a negative value should be
     * impossible. It is guarded anyway because the two ways of being wrong cost wildly different
     * amounts: read as "not cooled down", a clock that jumped backwards by fifteen minutes leaves
     * the user on a dead server for fifteen minutes with nothing in the journal to explain it, and
     * a jump of hours leaves them there for hours. Read as cooled down, the worst case is one
     * switch happening sooner than the rate limit intended.
     *
     * This is not hypothetical: NTP corrects the clock, roaming onto a foreign carrier resets it,
     * and the user can simply change it. Every one of those happens while travelling, which is
     * exactly when the tunnel matters.
     */
    @Test
    fun `a clock that jumped backwards does not switch failover off`() {
        assertEquals(
            Decision.LeaveCurrent,
            decide(failures = FAILURES_BEFORE_SWAP, sinceSwitch = -900_000L),
        )
        assertEquals(
            Decision.LeaveCurrent,
            decide(
                failures = FAILURES_BEFORE_SWAP,
                sinceSwitch = -900_000L,
                afterHandover = true,
            ),
        )
    }

    @Test
    fun `a clock that jumped backwards still cannot move a healthy tunnel`() {
        assertEquals(Decision.Hold, decide(failures = 0, sinceSwitch = -900_000L))
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
     * What every hostile pattern below has to satisfy.
     *
     * The escape limit is flat by choice: a run of departures is usually a search for a server that
     * actually carries traffic, and slowing it down lengthens the outage it is trying to end. So
     * the guarantee is not that moves become rare, it is that none of them are closer together
     * than the limit — no burst, no cascade, and a hard floor on how often the core is reloaded.
     *
     * Twenty minutes at one move per thirty seconds is forty. That is the honest worst case for a
     * connection where nothing works at all, and it is bounded rather than open-ended.
     */
    private fun assertPaced(moves: List<Long>, what: String) {
        assertTrue("$what moved ${moves.size} times in 20 minutes", moves.size <= 42)
        moves.zipWithNext { a, b ->
            assertTrue(
                "$what: two moves ${b - a}ms apart, limit ${FailoverPolicy.ESCAPE_COOLDOWN_MILLIS}ms",
                b - a >= FailoverPolicy.ESCAPE_COOLDOWN_MILLIS,
            )
        }
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
        assertPaced(moves, "a server failing every other probe")
    }

    /** The same, at the worst rate there is: nothing ever answers. */
    @Test
    fun `a permanently dead server cannot bounce the tunnel either`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis) { false }
        assertPaced(moves, "a permanently dead server")
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
    fun `a handover storm over a dead server still backs off`() {
        val runMillis = 20 * 60_000L
        val moves = simulate(runMillis, handoverEveryMillis = 10_000L) { false }
        assertTrue("a dead server in a storm should still be left once", moves.isNotEmpty())
        // A handover ends the wait between probes, not the limit between moves.
        assertPaced(moves, "a dead server during a handover storm")
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
        assertPaced(moves, "a server that comes and goes during a storm")
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

    /**
     * A core that confirms even one candidate is not blind, whatever the run of guesses before it
     * was — the fallback is not in play this round, so there is nothing to cap.
     */
    @Test
    fun `a confirmed candidate is never a blind run`() {
        assertFalse(FailoverPolicy.shouldHoldOnBlindRun(throughCoreEmpty = false, blindEscapesSoFar = 0))
        assertFalse(FailoverPolicy.shouldHoldOnBlindRun(throughCoreEmpty = false, blindEscapesSoFar = 99))
    }

    /**
     * The first blind guesses are allowed through — a core that needed one more moment to dial
     * candidates is not the same thing as a network refusing to carry proxy traffic at all, and
     * the difference should not cost the tunnel its only way home on a single slow round.
     */
    @Test
    fun `a run under the limit still gets to guess`() {
        for (soFar in 0 until FailoverPolicy.BLIND_ESCAPES_BEFORE_HOLD) {
            assertFalse(
                "escape $soFar should still be allowed",
                FailoverPolicy.shouldHoldOnBlindRun(throughCoreEmpty = true, blindEscapesSoFar = soFar),
            )
        }
    }

    /**
     * Past the limit, the watchdog holds instead of guessing again. This is the field case: a
     * courier's phone ran the core to zero-of-eight twenty-one times in ninety-three minutes, and
     * the bare TCP/TLS fallback found something to switch to every single time regardless —
     * twenty-five hops through servers the core never once confirmed, roughly forty seconds apart.
     * The cap is what stops that pattern from repeating past its first couple of guesses.
     */
    @Test
    fun `a run past the limit holds instead of guessing again`() {
        assertTrue(
            FailoverPolicy.shouldHoldOnBlindRun(
                throughCoreEmpty = true,
                blindEscapesSoFar = FailoverPolicy.BLIND_ESCAPES_BEFORE_HOLD,
            ),
        )
        assertTrue(
            FailoverPolicy.shouldHoldOnBlindRun(throughCoreEmpty = true, blindEscapesSoFar = 21),
        )
    }
}
