package com.mydrop.vpn.core.model

/**
 * When the tunnel may be moved, and when it must be left alone.
 *
 * Split out of the watchdog because this is the part that was wrong, and the part that could not
 * be tested where it lived: the watchdog reaches a decision through twenty-second probes and
 * five-minute cooldowns against live sockets, so the only way to exercise its policy was to run it.
 *
 * The rules exist because 0.3.2 shipped without them and the tunnel dropped connections. It added
 * a return to the server the user had picked, which is a good idea, but returning used the same
 * evidence as leaving: two probes either way. A server answering intermittently — which is the
 * normal state of a blocked endpoint, and the probe leaves through the user's own connection where
 * the blocking is — then produced a cycle. Away after two failures, back after two successes, away
 * again, roughly every ninety seconds. Every one of those moves reloads the core and kills every
 * connection the tunnel is carrying.
 *
 * So three things, none of which is about deciding better:
 *
 * - **Asymmetry.** Leaving a dead server restores service; returning to one interrupts a tunnel
 *   that is working. The second needs far more evidence than the first.
 * - **A floor on the interval.** Whatever the probes say, moves are rate-limited. The cost of
 *   each move is paid immediately by whoever is using the tunnel, so a rate that noise can drive
 *   is worse than a slow reaction. Leaving and returning are charged separately, because they
 *   are not the same act — see [ESCAPE_COOLDOWN_MILLIS].
 * - **Giving up.** A server returned to twice and lost twice is not recovering. Chasing it costs
 *   two interruptions per attempt and buys nothing over the replacement that is working.
 */
object FailoverPolicy {

    /**
     * Consecutive failed probes before the tunnel leaves the server it is on.
     *
     * One, and that is a deliberate reversal of what 0.3.2 taught. Back then a probe was a bare
     * TCP handshake against the server's port, which says nothing about whether the proxy behind
     * it works and everything about whether a single packet got lost — acting on one of those was
     * what made the tunnel flap thirty times in twenty minutes.
     *
     * The probe is no longer that. `TunnelHealthCheck` asks for a page *through the tunnel*, so a
     * failure means traffic did not get through, not that a handshake was slow. That is a far
     * stronger signal, and waiting for a second one only adds an outage the user sits through.
     * The rate limit that actually prevents flapping is [SWITCH_COOLDOWN_MILLIS], and it stays.
     */
    const val FAILURES_BEFORE_SWAP = 1

    /** Gap between probes while the server is answering. */
    const val PROBE_INTERVAL_MILLIS = 20_000L

    /** Gap between probes once one has already failed. */
    const val SUSPECT_PROBE_INTERVAL_MILLIS = 5_000L

    /** Consecutive answered probes before the tunnel returns to the user's own server. */
    const val PROBES_BEFORE_FAILBACK = 5

    /**
     * No return to the user's own server within this of the previous move.
     *
     * Applies to [Decision.ReturnHome] only. Going back interrupts a tunnel that is working, in
     * exchange for a preference — so it is worth being slow about, and being slow about it is what
     * bounds the leave/return cycle that made 0.3.2 flap.
     */
    const val SWITCH_COOLDOWN_MILLIS = 5 * 60_000L

    /**
     * No departure from a dead server within this of the previous move.
     *
     * Deliberately far shorter than [SWITCH_COOLDOWN_MILLIS], and the asymmetry is the whole
     * point. This was found in a field journal: a server died, the watchdog counted thirty-four
     * consecutive failed probes over five minutes, and held every single time because a switch had
     * happened three minutes earlier. The user had no internet for that entire stretch while the
     * policy waited out a rate limit meant to stop it flapping between servers that work.
     *
     * Leaving restores service; the cost of leaving too eagerly is one reconnection. Returning
     * costs a working tunnel for a preference. Charging both the same five minutes confused the
     * two, and it is the one that restores service that was paying.
     *
     * Not zero, because a replacement that is also dead would otherwise let the tunnel walk the
     * whole candidate list in a few seconds.
     *
     * Flat: it does not grow with repeated escapes. A run of departures usually means a search
     * rather than a fault — candidates are chosen on a direct probe, which only proves a port is
     * open, and whether a server actually carries traffic is answered by the through-tunnel probe
     * that runs after switching to it. Slowing that search down makes an outage longer, and the
     * thing it would save is a core reload whose connections were failing anyway.
     */
    const val ESCAPE_COOLDOWN_MILLIS = 30_000L

    /** Round trips to one server before it stops being treated as somewhere to go back to. */
    const val MAX_FAILBACKS = 2

    /**
     * How long to let the core settle after a Wi-Fi/cellular handover before probing.
     *
     * Not a delay for its own sake. A handover kills every connection pinned to the old interface
     * and the core has to re-dial, so for a moment the tunnel is broken *by definition* — probing
     * into that window measures the handover rather than the server, and with a single failure now
     * being enough to move, that false reading would cost a switch every time the user walked out
     * of Wi-Fi range.
     */
    const val HANDOVER_SETTLE_MILLIS = 3_000L


    /**
     * How long to wait before the next probe.
     *
     * The two intervals answer different questions. While the server answers, the probe is only
     * asking whether anything changed, and asking every twenty seconds costs a request through the
     * user's own tunnel for nothing — so it stays slow. Once a probe has failed, the question is
     * whether that was a lost packet or a dead server, and until it is answered the user has no
     * internet: every second of the wait is an outage they are sitting through.
     *
     * So the wait shrinks exactly when it is expensive, and the steady-state cost does not move.
     *
     * With [FAILURES_BEFORE_SWAP] now at one, the suspect interval only matters for the case where
     * a probe fails and the policy still holds — a cooldown that has not expired. The worst case
     * for noticing a dead server is one ordinary interval plus one probe timeout, and a handover
     * short-circuits even that.
     */
    fun nextProbeDelayMillis(consecutiveFailures: Int): Long =
        if (consecutiveFailures > 0) SUSPECT_PROBE_INTERVAL_MILLIS else PROBE_INTERVAL_MILLIS

    sealed interface Decision {
        /** Nothing to do, or something to do that has to wait for the cooldown. */
        data object Hold : Decision

        /** The current server is dead; move to a replacement. */
        data object LeaveCurrent : Decision

        /** The user's own server is answering again; move back to it. */
        data object ReturnHome : Decision

        /** It has answered and failed too many times to be worth returning to again. */
        data object AbandonHome : Decision
    }

    /**
     * @param consecutiveFailures answered-with-nothing probes against the server now in use.
     * @param consecutiveHomeRecoveries successful probes in a row against the server to return to.
     * @param hasHome whether there is a server to return to at all.
     * @param failbacksSoFar completed returns to that server during this session.
     * @param millisSinceLastSwitch since the last automatic move, in either direction.
     * @param afterHandover whether this probe was prompted by the network changing underneath the
     *   tunnel rather than by the clock. It changes nothing here — the evidence required is the
     *   same and the escape limit is already short — and is kept so the trace can say why a probe
     *   happened when it did. A handover's value is in the watchdog, which stops waiting and
     *   probes at once.
     */
    fun decide(
        consecutiveFailures: Int,
        consecutiveHomeRecoveries: Int,
        hasHome: Boolean,
        failbacksSoFar: Int,
        millisSinceLastSwitch: Long,
        @Suppress("UNUSED_PARAMETER") afterHandover: Boolean = false,
    ): Decision {
        // A negative elapsed time means the clock the caller measured with moved under it. The
        // caller uses a monotonic one precisely so this cannot happen, but the consequence of
        // being wrong is not symmetric: reading it as "not cooled down yet" switches failover off
        // entirely and silently, for as long as the skew lasts. Treated as cooled down, the worst
        // case is one switch sooner than intended.
        fun cooledDownFor(limit: Long) =
            millisSinceLastSwitch < 0L || millisSinceLastSwitch >= limit

        // Leaving comes first, and the order is the fix for a real outage rather than a tidy-up.
        //
        // It used to come second, after a branch that returned unconditionally. Five answered
        // probes against the server to go back to — twenty-five seconds — were enough to enter
        // that branch, and from then on the question of whether the server actually carrying
        // traffic was alive was never asked again. A journal caught the consequence exactly:
        // thirty-four consecutive failures on a dead server, every one of them answered `Hold`,
        // because the policy was waiting out a cooldown to plan a trip home. Whether the tunnel
        // works has to be decided before where it would rather be.
        if (consecutiveFailures >= FAILURES_BEFORE_SWAP) {
            return if (cooledDownFor(ESCAPE_COOLDOWN_MILLIS)) {
                Decision.LeaveCurrent
            } else {
                Decision.Hold
            }
        }

        if (hasHome && consecutiveHomeRecoveries >= PROBES_BEFORE_FAILBACK) {
            // Ahead of the cooldown on purpose: giving up moves nothing, so making it wait would
            // only keep a pointless probe running for another five minutes.
            if (failbacksSoFar >= MAX_FAILBACKS) return Decision.AbandonHome
            // The long limit, and it stays long. Nothing is broken when this is asked.
            return if (cooledDownFor(SWITCH_COOLDOWN_MILLIS)) {
                Decision.ReturnHome
            } else {
                Decision.Hold
            }
        }

        return Decision.Hold
    }
}
