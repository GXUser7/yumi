package com.mydrop.vpn.data

import android.os.SystemClock
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.FailoverChoice
import com.mydrop.vpn.core.model.FailoverGroup
import com.mydrop.vpn.core.model.FailoverPolicy
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches the server the tunnel is riding on, and moves off it when it stops answering.
 *
 * This is the app's job rather than the core's, and that is a deliberate reversal. sing-box can do
 * failover itself with a `urltest` group, but that group always dials whichever member is fastest,
 * and it re-chooses only on its probe schedule — a server dying one second after a check leaves
 * every connection failing until the next one. Doing it here buys a reaction time we set, and a
 * choice we control: [FailoverChoice] picks at random among the servers that are keeping pace,
 * instead of piling every client onto the same fastest one.
 *
 * The probe works because the service excludes this app from its own tunnel
 * (`addDisallowedApplication`), so a measurement leaves the phone directly and stays truthful
 * while the tunnel itself is a black hole.
 *
 * What it detects is the server being gone: refusing connections, or no longer routed to. A server
 * that still completes a handshake while its proxy misbehaves looks alive from here and will not
 * trigger a switch.
 *
 * Every switch is provisional. The server the user picked is remembered and kept under probe, and
 * the tunnel returns to it as soon as it answers again — otherwise one bad minute silently became
 * permanent, and the only clue was that the connect screen now named a server nobody had chosen.
 */
class FailoverWatchdog(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
    private val latencyTester: LatencyTester,
    private val tunnelHealth: TunnelHealthCheck,
    private val configs: TunnelConfigBuilder,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {

    private var watching: Job? = null

    /**
     * The server the user chose, kept from the moment this watchdog moves the tunnel off it until
     * the tunnel is back on it. Null whenever the tunnel is riding on a choice somebody made.
     */
    private var homeNode: ProxyNode? = null

    /** The replacement this watchdog installed, so its own doing is distinguishable from a tap. */
    private var autoChosenId: String? = null

    /**
     * When this watchdog last moved the tunnel, in either direction. Every automatic switch waits
     * out [SWITCH_COOLDOWN_MILLIS] from here — see [restartWatch] for why the rate matters more
     * than the decision.
     *
     * Read from `SystemClock.elapsedRealtime`, not the wall clock, and that is not a detail. The
     * wall clock steps: NTP corrects it, roaming onto a foreign network resets it, the user changes
     * it. A step backwards makes the elapsed time negative, the cooldown never expires, and
     * failover switches itself off silently until real time catches up — minutes or hours of a
     * dead tunnel with nothing in the journal to explain it. `elapsedRealtime` counts from boot,
     * never goes backwards, and keeps counting while the device sleeps.
     */
    private var lastSwitchAtMillis = 0L

    /**
     * How many times the tunnel has been sent back to a given server. A server that needed
     * returning to more than [MAX_FAILBACKS] times is not recovering, it is flapping, and the
     * tunnel stops chasing it.
     */
    private val failbacks = mutableMapOf<String, Int>()

    /** Follows the tunnel: a watch runs for exactly as long as one connection lives. */
    fun start() {
        scope.launch {
            tunnel.state.collectLatest { state ->
                when (state) {
                    is VpnState.Connected -> restartWatch()
                    // A switch passes back through Connecting on its way to Connected. The way
                    // home has to survive that, or the switch this watchdog just made would erase
                    // the very thing it is meant to undo.
                    is VpnState.Connecting -> stopWatch()
                    else -> {
                        stopWatch()
                        forgetHome()
                    }
                }
            }
        }
    }

    private fun restartWatch() {
        watching?.cancel()
        watching = scope.launch {
            logs.trace(
                TAG,
                "watch started, grace ${GRACE_MILLIS / 1000}s then probe, then every " +
                    "${FailoverPolicy.PROBE_INTERVAL_MILLIS / 1000}s " +
                    "(${FailoverPolicy.SUSPECT_PROBE_INTERVAL_MILLIS / 1000}s after a failure, " +
                    "autoFailover=${settings.value.autoFailover})",
            )
            // A tunnel that just came up has not had time to break, and the core is still
            // settling. Probing into that only produces a false alarm.
            delay(GRACE_MILLIS)

            // Collected into a conflated channel rather than raced against directly, because a
            // handover that arrives *during* a probe would otherwise fall on the floor: the flow
            // has no replay, and between cycles there is no subscriber to buffer it. Conflated
            // because two handovers in a row still only pose one question, and it is the same one.
            val handovers = Channel<String>(Channel.CONFLATED)
            // A child of this watch, so it dies with it: cancelling `watching` cancels the
            // collector too, and there is nothing to unwind by hand.
            launch { tunnel.handovers.collect { handovers.trySend(it) } }

            var failures = 0
            var recoveries = 0
            // Only so the hold is announced once per outage rather than every probe.
            var offline = false
            // The grace above is the wait before the first probe. Waiting again at the top of the
            // first pass made it the grace *plus* a full interval — thirty-five seconds before the
            // tunnel was asked anything, where the comment on the grace promises fifteen. It cost
            // twenty seconds on every genuine outage, measured on a phone: a server that carried
            // nothing from the moment it came up was left forty seconds later, and twenty of those
            // were this.
            var firstPass = true
            var movedTo: String? = null
            while (isActive) {
                // Slow while the server answers, fast once one probe has failed: the wait shrinks
                // exactly when the user is sitting through an outage. See the policy for why.
                //
                // The wait ends early when the ground moves. A handover has just killed every
                // connection pinned to the old interface, so whether this server still works is a
                // question with a fresh answer — waiting out the clock to ask it is up to twenty
                // seconds of an outage the user is already feeling.
                if (firstPass) {
                    firstPass = false
                } else {
                    movedTo = withTimeoutOrNull(FailoverPolicy.nextProbeDelayMillis(failures)) {
                        handovers.receive()
                    }
                    if (movedTo != null) {
                        logs.trace(TAG, "handover to $movedTo, probing early")
                        // Measured after the core has had a moment to re-dial. Probing into the
                        // gap a handover opens measures the handover, not the server — and with
                        // one failure now enough to move, that reading would cost a switch every
                        // time the user walked out of Wi-Fi range.
                        delay(FailoverPolicy.HANDOVER_SETTLE_MILLIS)
                    }
                }
                if (!settings.value.autoFailover) {
                    failures = 0
                    continue
                }

                // A phone with no default interface has no internet, and a probe run into that
                // says nothing about the server carrying the tunnel. Counting it as a failure is
                // how walking out of Wi-Fi range came to read as "this server is dead" — and at
                // one failure being enough to move, that verdict arrived within seconds of the
                // signal going. There is nothing to measure and nowhere to move to.
                if (!tunnel.hasNetwork.value) {
                    failures = 0
                    if (!offline) {
                        offline = true
                        logs.trace(TAG, "no default interface, holding")
                    }
                    continue
                }
                if (offline) {
                    offline = false
                    logs.trace(TAG, "default interface back, resuming")
                }
                val current = profiles.selectedNode() ?: continue

                // Choosing a server by hand is the freshest statement of intent there is, and it
                // retires whatever this watchdog was still trying to undo.
                if (autoChosenId != null && current.id != autoChosenId) forgetHome()

                // A subscription refresh retires ids, so the saved server can stop existing. Then
                // there is nowhere to go back to and the memory is only a probe against a ghost.
                val home = homeNode?.let { saved -> profiles.nodes.firstOrNull { it.id == saved.id } }
                if (homeNode != null && home == null) forgetHome()

                // Probed first, and always: the count of good probes in a row is what the
                // policy weighs against the count of bad ones.
                if (home != null) {
                    val revived = latencyTester.measure(home, settings.value.pingMode)
                    profiles.recordLatency(revived)
                    recoveries = if (revived.failed) 0 else recoveries + 1
                } else {
                    recoveries = 0
                }

                // The server carrying traffic is asked of the tunnel, not of its own port.
                //
                // A direct probe answers "is the port open", and the failure this watchdog exists
                // to catch is a server whose port is open and whose sessions die anyway — the
                // ordinary shape of DPI interference. Only the through-tunnel check sees that.
                // Candidates and the home node keep the direct probe below: there is no tunnel to
                // them to ask through.
                val throughTunnel = tunnelHealth.passes(configs.probe.value)
                val alive = throughTunnel
                    ?: !latencyTester.measure(current, settings.value.pingMode).failed
                failures = if (alive) 0 else failures + 1
                if (!alive && failures < FailoverPolicy.FAILURES_BEFORE_SWAP) {
                    logs.debug(
                        // Two different diagnoses, and telling them apart is most of the value of
                        // reading this journal: a server that stopped answering is somebody else's
                        // outage, one that answers while carrying nothing is being interfered with.
                        if (throughTunnel == false) {
                            R.string.log_failover_no_traffic
                        } else {
                            R.string.log_failover_no_answer
                        },
                        current.name,
                        failures,
                        FailoverPolicy.FAILURES_BEFORE_SWAP,
                    )
                }

                val sinceSwitch = SystemClock.elapsedRealtime() - lastSwitchAtMillis
                val decision = FailoverPolicy.decide(
                    consecutiveFailures = failures,
                    consecutiveHomeRecoveries = recoveries,
                    hasHome = home != null,
                    failbacksSoFar = failbacks[home?.id] ?: 0,
                    millisSinceLastSwitch = sinceSwitch,
                    afterHandover = movedTo != null,
                )

                // One line per probe cycle, to logcat rather than the journal. Every input the
                // policy saw and the verdict it reached, so a decision that looks wrong on the
                // phone can be read back afterwards instead of guessed at. The journal stays for
                // the user; this is for whoever is holding a cable.
                logs.trace(
                    TAG,
                    "probe current=${current.name} tunnel=${throughTunnel ?: "n/a"} alive=$alive " +
                        "fail=$failures/${FailoverPolicy.FAILURES_BEFORE_SWAP} " +
                        "home=${home?.name ?: "-"} recover=$recoveries/" +
                        "${FailoverPolicy.PROBES_BEFORE_FAILBACK} " +
                        "failbacks=${failbacks[home?.id] ?: 0} " +
                        "sinceSwitch=${if (lastSwitchAtMillis == 0L) "never" else "${sinceSwitch / 1000}s"} " +
                        (if (movedTo != null) "handover=$movedTo " else "") +
                        "-> $decision",
                )

                when (decision) {
                    // Counters are left standing: whatever the policy is waiting for, it is still
                    // true, and resetting them here would make the wait restart every probe.
                    FailoverPolicy.Decision.Hold -> Unit

                    FailoverPolicy.Decision.AbandonHome -> {
                        home ?: continue
                        recoveries = 0
                        failbacks[home.id] = FailoverPolicy.MAX_FAILBACKS + 1
                        forgetHome()
                        logs.info(R.string.log_failover_home_abandoned, home.name)
                    }

                    FailoverPolicy.Decision.ReturnHome -> {
                        home ?: continue
                        recoveries = 0
                        failbacks[home.id] = (failbacks[home.id] ?: 0) + 1
                        forgetHome()
                        logs.info(R.string.log_failover_home_back, home.name)
                        lastSwitchAtMillis = SystemClock.elapsedRealtime()
                        launcher.switchTo(home)
                    }

                    FailoverPolicy.Decision.LeaveCurrent -> {
                        failures = 0
                        logs.trace(TAG, "leaving ${current.name}: dead for good")
                        swapAwayFrom(current)
                    }
                }
            }
        }
    }

    private fun stopWatch() {
        watching?.cancel()
        watching = null
    }

    private fun forgetHome() {
        homeNode = null
        autoChosenId = null
    }

    private suspend fun swapAwayFrom(dead: ProxyNode) {
        val candidates = FailoverGroup.candidates(
            nodes = profiles.nodes,
            selected = dead,
            latencies = profiles.state.value.latencies,
            limit = FailoverGroup.MAX_GROUP,
            chosen = settings.value.failoverNodeIds,
        )
        if (candidates.isEmpty()) {
            logs.warn(R.string.log_failover_nothing_to_swap, dead.name)
            return
        }

        logs.warn(R.string.log_failover_probing, dead.name, candidates.size)
        // Measured now, not read from the profile: what mattered an hour ago says nothing about
        // which servers are up during the outage this is reacting to.
        val fresh = mutableMapOf<String, com.mydrop.vpn.core.model.LatencyResult>()
        latencyTester.measureAll(candidates, settings.value.pingMode) { result ->
            fresh[result.nodeId] = result
            profiles.recordLatency(result)
        }

        val chosen = FailoverChoice.pick(candidates, fresh)
        if (chosen == null) {
            logs.warn(R.string.log_failover_all_dead, dead.name)
            return
        }

        logs.info(R.string.log_failover_switching, chosen.name, fresh[chosen.id]?.millis.toString())
        // Recorded before the switch, because moving the tunnel takes this coroutine down with it.
        // Only the first departure counts: hop twice and home is still the server the user chose,
        // not the replacement this watchdog installed on the way.
        // A server already given up on does not become home again; otherwise the give-up counter
        // would reset itself every time the tunnel wandered back past it.
        if (homeNode == null && (failbacks[dead.id] ?: 0) < FailoverPolicy.MAX_FAILBACKS) homeNode = dead
        autoChosenId = chosen.id
        lastSwitchAtMillis = SystemClock.elapsedRealtime()
        launcher.switchTo(chosen)
    }

    private companion object {
        /** Filter for the live trace: `adb logcat -s YumiFailover`. */
        const val TAG = "YumiFailover"

        const val GRACE_MILLIS = 15_000L

        // The thresholds themselves live in FailoverPolicy, which is where they are tested.
    }
}
