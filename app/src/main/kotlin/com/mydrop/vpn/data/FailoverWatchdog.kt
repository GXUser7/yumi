package com.mydrop.vpn.data

import android.os.SystemClock
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.FailoverChoice
import com.mydrop.vpn.core.model.FailoverGroup
import com.mydrop.vpn.core.model.FailoverPolicy
import com.mydrop.vpn.core.model.NetworkEnvironment
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private val alerts: AlertNotifier,
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

    /**
     * The network the tunnel has been arranged for, as opposed to the one the callbacks are
     * shouting about this second. Only changes once a transport has held for its settling time.
     */
    private var confirmedTransport = NetworkTransport.None

    /**
     * Where the tunnel was before it went onto the mobile list, so coming home can be a return
     * rather than a fresh guess. Cleared once it has been used or has stopped existing.
     */
    private var savedOrdinaryNodeId: String? = null

    /**
     * Moves the tunnel between the ordinary servers and the mobile ones as the phone changes
     * network — but only once the change has held.
     *
     * `collectLatest` is doing the work: a new value cancels the coroutine still waiting out the
     * previous one, so a transport that does not survive its settling time never reaches the
     * bottom of this block. That is the whole debounce, and it is why the firmware storms
     * described in [NetworkEnvironment] cost nothing.
     *
     * The screen gate is the second half. Several firmwares switch Wi-Fi off when the screen goes
     * off and fall back to cellular; without waiting for somebody to be looking, every press of
     * the power button would read as leaving the house and move the tunnel twice.
     */
    private fun watchTransport() {
        scope.launch {
            combine(tunnel.transport, tunnel.screenOn) { transport, awake -> transport to awake }
                .collectLatest { (transport, awake) ->
                    if (!awake) return@collectLatest
                    if (!NetworkEnvironment.actionable(transport)) return@collectLatest
                    if (transport == confirmedTransport) return@collectLatest

                    delay(NetworkEnvironment.settleMillis(transport))
                    confirmedTransport = transport
                    applyTransport(transport)
                }
        }
    }

    private suspend fun applyTransport(transport: NetworkTransport) {
        val current = settings.value
        if (current.mobileNodeIds.isEmpty()) return
        if (tunnel.state.value !is VpnState.Connected) return
        val carrying = profiles.selectedNode() ?: return

        when {
            NetworkEnvironment.wantsMobileServer(transport, current.mobileNodeIds, carrying.id) -> {
                val pool = profiles.nodes.filter { it.id in current.mobileNodeIds }
                val chosen = measureAndPick(pool)
                if (chosen == null) {
                    // Either the named servers have gone from the subscription, or none of them
                    // answered from here. Staying put beats guessing: the user said which servers
                    // may carry cellular traffic, and none of the others were on that list.
                    logs.warn(R.string.log_mobile_none_left)
                    return
                }
                savedOrdinaryNodeId = carrying.id
                logs.info(R.string.log_mobile_switch, chosen.node.name, chosen.millis.toString())
                launcher.switchTo(chosen.node)
            }

            NetworkEnvironment.wantsOrdinaryServer(transport, current.mobileNodeIds, carrying.id) -> {
                val remembered = savedOrdinaryNodeId
                    ?.let { id -> profiles.nodes.firstOrNull { it.id == id } }
                    ?.takeIf { it.id !in current.mobileNodeIds }
                // The switch says which of two answers to prefer, and both are deliberate.
                //
                // Remembering is predictable: the tunnel comes back to the server it left. Not
                // remembering runs the ordinary choice — over the same pool a failover would use,
                // which is the list the user curated, never the whole subscription.
                //
                // The pool is the point. Choosing "the fastest server that is not on the mobile
                // list" would range over the entire subscription — hundreds of servers the user
                // never nominated for anything — and pick whichever happened to answer quickest.
                // The failover list exists precisely to say which servers are acceptable to be
                // moved onto, and coming home is a move like any other.
                if (current.restoreWifiNodeOnWifi && remembered != null) {
                    savedOrdinaryNodeId = null
                    logs.info(R.string.log_mobile_back, remembered.name, "")
                    launcher.switchTo(remembered)
                    return
                }
                val pool = ordinaryPoolFor(remembered ?: carrying, current.mobileNodeIds)
                val chosen = measureAndPick(pool) ?: return
                savedOrdinaryNodeId = null
                logs.info(R.string.log_mobile_back, chosen.node.name, chosen.millis.toString())
                launcher.switchTo(chosen.node)
            }
        }
    }

    /**
     * Which servers are acceptable on an ordinary network, in the order a failover would consider
     * them.
     *
     * The pool is the user's own failover list, or their subscription when they have not named
     * one — the same pool [swapAwayFrom] draws from, and for the same reason: these are the
     * servers they have said they are willing to be on.
     */
    private fun ordinaryPoolFor(anchor: ProxyNode, mobileIds: Set<String>): List<ProxyNode> =
        FailoverGroup.sample(
            FailoverGroup.candidates(
                nodes = profiles.nodes,
                selected = anchor,
                latencies = profiles.state.value.latencies,
                limit = FailoverGroup.roomFor(mobileIds.size),
                chosen = settings.value.failoverNodeIds,
            ).filter { it.id !in mobileIds },
        )

    private data class Measured(val node: ProxyNode, val millis: Int)

    /**
     * Measures a pool from where the phone is standing now, and picks from what answered.
     *
     * The measuring is the point. An earlier version read the stored numbers instead, to save a
     * few handshakes on somebody's paid data — and the numbers it read had been taken on home
     * Wi-Fi, which says nothing whatever about the same servers reached over cellular from a
     * street. Choosing a mobile server by its Wi-Fi latency is choosing by a measurement of a
     * different thing.
     *
     * Selection is [FailoverChoice], exactly as in an ordinary failover: unanswered servers are
     * out, servers half again slower than the pack's median are out, and the winner is drawn at
     * random from the rest rather than being the fastest — so a subscription's worth of phones
     * leaving home at nine in the morning do not all land on the same server.
     */
    private suspend fun measureAndPick(pool: List<ProxyNode>): Measured? {
        val candidates = pool.filter { it.settings != ProxySettings.Direct }
        if (candidates.isEmpty()) return null

        val measured = latencyTester.measureAll(candidates, settings.value.pingMode) { result ->
            profiles.recordLatency(result)
        }
        val fresh = measured.associateBy { it.nodeId }
        val winner = FailoverChoice.pick(candidates, fresh) ?: return null
        return Measured(winner, fresh[winner.id]?.millis ?: 0)
    }

    /** Follows the tunnel: a watch runs for exactly as long as one connection lives. */
    fun start() {
        watchTransport()
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
                        // The fallback resolver lasts exactly as long as the connection that
                        // needed it. A resolver that was down twenty minutes ago is the likeliest
                        // thing in the world to be up again, and one that stayed swapped until
                        // somebody noticed would be a setting the user never chose.
                        configs.useDnsFallback(false)
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
            val nudges = Channel<Nudge>(Channel.CONFLATED)
            // Children of this watch, so they die with it: cancelling `watching` cancels the
            // collectors too, and there is nothing to unwind by hand.
            launch { tunnel.handovers.collect { nudges.trySend(Nudge.Handover(it)) } }
            launch { tunnel.wakeups.collect { nudges.trySend(Nudge.Awake) } }

            var failures = 0
            var recoveries = 0
            // Names that failed to resolve in a row, and whether the user has already been told.
            var dnsFailures = 0
            // Cumulative counter read at every probe, so the difference is what arrived between
            // two of them.
            var lastDownloadBytes = tunnel.traffic.value.downloadBytes
            var vetoes = 0
            var dnsReported = false
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
            // Wall of its own, so a screen that comes on twice in three seconds does not buy two
            // probes. Monotonic, like everything else here that measures elapsed time.
            var lastProbeAtMillis = 0L
            while (isActive) {
                // Whether this is the first thing asked of the tunnel since it came up. Used to
                // tell a return that was a mistake from a server that worked and then died.
                val firstProbe = firstPass
                // Slow while the server answers, fast once one probe has failed: the wait shrinks
                // exactly when the user is sitting through an outage. See the policy for why.
                //
                // The wait ends early when the ground moves. A handover has just killed every
                // connection pinned to the old interface, so whether this server still works is a
                // question with a fresh answer — waiting out the clock to ask it is up to twenty
                // seconds of an outage the user is already feeling.
                movedTo = null
                if (firstPass) {
                    firstPass = false
                } else {
                    val nudge = withTimeoutOrNull(FailoverPolicy.nextProbeDelayMillis(failures)) {
                        nudges.receive()
                    }
                    when (nudge) {
                        is Nudge.Handover -> {
                            movedTo = nudge.name
                            logs.trace(TAG, "handover to ${nudge.name}, probing early")
                            // Measured after the core has had a moment to re-dial. Probing into
                            // the gap a handover opens measures the handover, not the server, and
                            // that reading would cost a switch every time somebody walked out of
                            // Wi-Fi range.
                            delay(FailoverPolicy.HANDOVER_SETTLE_MILLIS)
                        }

                        // The phone was asleep, so this loop was too — see TunnelController.wakeups
                        // for the measurements. Nothing was reset and nothing needs to settle; the
                        // point is only to have looked before the user does.
                        Nudge.Awake -> {
                            val since = SystemClock.elapsedRealtime() - lastProbeAtMillis
                            if (since < FailoverPolicy.SUSPECT_PROBE_INTERVAL_MILLIS) continue
                            logs.trace(TAG, "phone is awake after ${since / 1000}s, probing early")
                        }

                        null -> Unit
                    }
                }
                if (!settings.value.autoFailover) {
                    failures = 0
                    continue
                }

                // A phone with no working internet of its own says nothing about the server
                // carrying the tunnel, and counting that as the server's failure is how a lift
                // came to read as "this server is dead". A journal caught it exactly: three
                // different servers timing out within two minutes, the tunnel moved off one that
                // was working, every open connection dropped, and the replacement failing a
                // moment later for the same reason.
                //
                // Android's own verdict rather than a request of ours — see the service for why
                // asking a host directly would be worse than useless from this country.
                if (!tunnel.hasNetwork.value) {
                    failures = 0
                    if (!offline) {
                        offline = true
                        logs.trace(TAG, "the phone has no internet of its own, holding")
                    }
                    continue
                }
                if (offline) {
                    offline = false
                    logs.trace(TAG, "internet is back, resuming")
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
                lastProbeAtMillis = SystemClock.elapsedRealtime()
                val throughTunnel = tunnelHealth.passes(configs.probe.value)
                val alive = throughTunnel
                    ?: !latencyTester.measure(current, settings.value.pingMode).failed

                // Bytes that arrived through the tunnel outrank the probe's opinion of it.
                //
                // The probe is one plain HTTP request to one host, and it fails for reasons that
                // have nothing to do with the exit: a momentary stall, a slow round trip past the
                // five-second limit, that one host being throttled where everything else is not.
                // Measured on a phone over an evening it failed 13.5% of the time — and eight of
                // the ten servers it condemned were, in the same seconds, carrying the owner's
                // own connections without a single error.
                //
                // Download is the half that cannot be faked. A dead exit still accepts whatever
                // the phone uploads into it; only a live one sends bytes back. So if traffic has
                // been arriving since the last probe, the exit works, whatever the probe says.
                //
                // Bounded, because a server can rot while an old connection keeps trickling: after
                // three vetoes in a row the probe is believed anyway.
                val arrived = tunnel.traffic.value.downloadBytes
                val sinceLastProbe = arrived - lastDownloadBytes
                lastDownloadBytes = arrived
                val vetoed = !alive &&
                    sinceLastProbe >= FailoverPolicy.TRAFFIC_VETO_BYTES &&
                    vetoes < FailoverPolicy.MAX_TRAFFIC_VETOES
                if (vetoed) {
                    vetoes++
                    logs.trace(
                        TAG,
                        "probe failed but $sinceLastProbe bytes arrived since the last one — " +
                            "the exit is carrying traffic " +
                            "(veto $vetoes/${FailoverPolicy.MAX_TRAFFIC_VETOES})",
                    )
                } else if (alive) {
                    vetoes = 0
                }

                failures = if (alive || vetoed) 0 else failures + 1
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

                // Asked only of a tunnel that has just proved it carries traffic. The two
                // probes differ by one routing rule — see ProbeTargets — so a failure here with a
                // pass above means the resolver, and nothing else, has stopped working. Asking it
                // of a broken tunnel would blame DNS for somebody else's outage.
                val resolving = if (alive) tunnelHealth.resolves(configs.probe.value) else null
                when (resolving) {
                    true -> {
                        dnsFailures = 0
                        dnsReported = false
                    }
                    // The first lookup a new tunnel makes is not evidence of anything, and this
                    // was measured rather than guessed: on three different servers the first DoH
                    // query after a restart came back EOF and every one after it succeeded. The
                    // encrypted resolver has its own connection to open, through a tunnel that is
                    // itself seconds old, and it opens it exactly once.
                    //
                    // Counting it spent one of the two strikes before the resolver had been asked
                    // anything real, which turned "one bad lookup" into a resolver swap on a
                    // resolver that was fine.
                    false -> if (firstProbe) {
                        logs.trace(TAG, "first dns lookup after connect failed, not counting it")
                    } else if (tunnelHealth.passes(configs.probe.value) == false) {
                        // The tunnel probe that licensed this question is five seconds old, and
                        // five seconds is long enough for a flaky exit to have died in between.
                        // That is not a hypothetical: it swapped a working resolver on a phone
                        // whose server was answering every other probe — the DNS check timed out
                        // for the same reason the tunnel check did thirty seconds later, and only
                        // one of the two carries the word "dns" in its name.
                        //
                        // So the verdict is re-taken at the moment of the failure rather than
                        // inherited from before it. A tunnel that fails right now means the exit
                        // is the fault, and the resolver keeps its record clean.
                        logs.trace(TAG, "dns failed, but so does the tunnel now — not the resolver")
                    } else {
                        dnsFailures++
                    }
                    null -> Unit
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
                        "dns=${resolving ?: "n/a"}/$dnsFailures " +
                        (if (configs.dnsFallback.value) "dns-fallback " else "") +
                        (if (movedTo != null) "handover=$movedTo " else "") +
                        "-> $decision",
                )

                if (dnsFailures >= FailoverPolicy.DNS_FAILURES_BEFORE_FALLBACK) {
                    dnsFailures = 0
                    val resolver = currentResolver()
                    when {
                        // Already on the fallback and it is failing too. There is nothing else to
                        // reach for, and saying so is the only useful thing left: the fault is
                        // almost certainly not the resolver at this point.
                        configs.dnsFallback.value -> if (!dnsReported) {
                            dnsReported = true
                            logs.warn(R.string.log_dns_fallback_also_dead, resolver)
                            alerts.dnsDead(resolver)
                        }

                        settings.value.dnsFallback -> {
                            val replacement = SingBoxConfigFactory.fallbackResolver(resolver)
                            logs.warn(R.string.log_dns_switching, resolver, replacement)
                            alerts.dnsReplaced(resolver, replacement)
                            configs.useDnsFallback(true)
                            // The resolver is baked into the document the core reads at startup,
                            // so changing it means handing the core a new one. Same server, same
                            // everything else — the reconnection is the delivery mechanism.
                            launcher.switchTo(current)
                            continue
                        }

                        !dnsReported -> {
                            dnsReported = true
                            logs.warn(R.string.log_dns_dead, resolver)
                            alerts.dnsDead(resolver)
                        }
                    }
                }

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
                        // A server that failed the very first thing asked of it, having just been
                        // returned to, was not worth returning to. The evidence that sent the
                        // tunnel back is a direct probe, and all a direct probe proves is that a
                        // port is open — which is exactly true of a server being interfered with,
                        // and was reproduced on a phone: the tunnel went home to a host that
                        // answered on 443 and carried nothing, sixteen seconds after leaving it.
                        //
                        // Without this the round trip is simply repeated until MAX_FAILBACKS runs
                        // out, and the user pays a second outage to learn what the first one had
                        // already shown.
                        if (firstProbe && (failbacks[current.id] ?: 0) > 0) {
                            failbacks[current.id] = FailoverPolicy.MAX_FAILBACKS + 1
                            logs.trace(TAG, "${current.name} failed on return, not going back")
                        }
                        logs.trace(TAG, "leaving ${current.name}: dead for good")
                        swapAwayFrom(current)
                    }
                }
            }
        }
    }

    /** Why the wait between probes ended. */
    private sealed interface Nudge {
        /** The tunnel moved between physical networks; [name] is the new interface. */
        data class Handover(val name: String) : Nudge

        /** The screen came on, or the system left idle mode. */
        data object Awake : Nudge
    }

    private fun stopWatch() {
        watching?.cancel()
        watching = null
    }

    private fun forgetHome() {
        homeNode = null
        autoChosenId = null
    }

    /** The resolver the tunnel is actually querying, named the way the user chose it. */
    private fun currentResolver(): String = profiles.selectedDnsProfile()?.url
        ?: settings.value.let {
            if (it.routingMode == RoutingMode.Direct) it.directDns else it.remoteDns
        }

    private suspend fun swapAwayFrom(dead: ProxyNode) {
        // The whole group first, then seven of it by lot. Drawing from the group and not from
        // the raw list is what keeps the switch instant: the core can only be pointed at its own
        // members, and everything in here is one.
        val candidates = FailoverGroup.sample(
            FailoverGroup.candidates(
                nodes = profiles.nodes,
                selected = dead,
                latencies = profiles.state.value.latencies,
                limit = FailoverGroup.roomFor(settings.value.mobileNodeIds.size),
                chosen = settings.value.failoverNodeIds,
            ),
        )
        if (candidates.isEmpty()) {
            logs.warn(R.string.log_failover_nothing_to_swap, dead.name)
            alerts.serverStranded(dead.name)
            return
        }

        logs.warn(R.string.log_failover_probing, dead.name, candidates.size)
        // Measured now, not read from the profile: what mattered an hour ago says nothing about
        // which servers are up during the outage this is reacting to.
        //
        // Built from what measureAll returns, not collected in its callback along the way. That
        // callback runs on up to sixteen coroutines at once — see LatencyTester.measureAll — and
        // the map it used to fill was an ordinary LinkedHashMap. Concurrent writes to one of those
        // lose entries, and a lost entry is indistinguishable here from a server that never
        // answered: the choice below sees fewer live candidates than there are, picks a slower
        // one, or finds none at all and leaves the tunnel sitting on the dead server it was
        // supposed to be escaping. Worst exactly when it matters most, because the more spares
        // the user has chosen, the more writers there are to collide.
        //
        // The callback stays, but only for the side effect that is already safe.
        val measured = latencyTester.measureAll(candidates, settings.value.pingMode) { result ->
            profiles.recordLatency(result)
        }
        val fresh = measured.associateBy { it.nodeId }

        val chosen = FailoverChoice.pick(candidates, fresh)
        if (chosen == null) {
            logs.warn(R.string.log_failover_all_dead, dead.name)
            alerts.serverStranded(dead.name)
            return
        }

        logs.info(R.string.log_failover_switching, chosen.name, fresh[chosen.id]?.millis.toString())
        alerts.serverLeft(dead.name, chosen.name)
        // Recorded before the switch, because moving the tunnel takes this coroutine down with it.
        // Only the first departure counts: hop twice and home is still the server the user chose,
        // not the replacement this watchdog installed on the way.
        // A server already given up on does not become home again; otherwise the give-up counter
        // would reset itself every time the tunnel wandered back past it.
        //
        // And nothing is remembered at all when the user has said a switch is final. Then this
        // watchdog only ever moves away from servers that stopped working, and where it lands is
        // where the tunnel stays until somebody chooses otherwise.
        if (settings.value.returnHome &&
            homeNode == null &&
            (failbacks[dead.id] ?: 0) < FailoverPolicy.MAX_FAILBACKS
        ) {
            homeNode = dead
        }
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
