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
 * - **A floor on the interval.** Whatever the probes say, moves are rate-limited. A probe is a
 *   bare TCP handshake and cannot see whether the proxy behind it works, so acting on it often is
 *   acting on noise — and the cost of each action is paid immediately by whoever is using the
 *   tunnel.
 * - **Giving up.** A server returned to twice and lost twice is not recovering. Chasing it costs
 *   two interruptions per attempt and buys nothing over the replacement that is working.
 */
object FailoverPolicy {

    /** Consecutive failed probes before the tunnel leaves the server it is on. */
    const val FAILURES_BEFORE_SWAP = 2

    /** Consecutive answered probes before the tunnel returns to the user's own server. */
    const val PROBES_BEFORE_FAILBACK = 5

    /** No automatic move within this of the previous one, whichever direction it went. */
    const val SWITCH_COOLDOWN_MILLIS = 5 * 60_000L

    /** Round trips to one server before it stops being treated as somewhere to go back to. */
    const val MAX_FAILBACKS = 2

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
     */
    fun decide(
        consecutiveFailures: Int,
        consecutiveHomeRecoveries: Int,
        hasHome: Boolean,
        failbacksSoFar: Int,
        millisSinceLastSwitch: Long,
    ): Decision {
        val cooledDown = millisSinceLastSwitch >= SWITCH_COOLDOWN_MILLIS

        if (hasHome && consecutiveHomeRecoveries >= PROBES_BEFORE_FAILBACK) {
            // Ahead of the cooldown on purpose: giving up moves nothing, so making it wait would
            // only keep a pointless probe running for another five minutes.
            if (failbacksSoFar >= MAX_FAILBACKS) return Decision.AbandonHome
            return if (cooledDown) Decision.ReturnHome else Decision.Hold
        }

        if (consecutiveFailures >= FAILURES_BEFORE_SWAP) {
            return if (cooledDown) Decision.LeaveCurrent else Decision.Hold
        }

        return Decision.Hold
    }
}
