package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.Ipv6Policy
import com.mydrop.vpn.core.model.Ipv6Verdict
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.model.dialed
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Keeps IPv6 addresses away from applications unless the server carrying them can reach IPv6.
 *
 * The decision itself is [Ipv6Policy]; this is the half that asks the servers and remembers what
 * they said. After every connection it takes the members of the running document whose exits have
 * no fresh verdict, asks each of them through the core for the ordinary probe and for an IPv6
 * literal, and files the answer under the exit's host. When the answers change what the document
 * should say, the tunnel is rebuilt with the new answer — in practice once per exit: the first
 * connection through an exit hands out IPv4 only, and a later one hands out IPv6 if it was earned.
 *
 * What it cannot fix is an exit that loses IPv6 after being seen with it. The rebuild takes `AAAA`
 * out of the resolver, but applications keep the addresses they were already given until the
 * record expires — Google's live five minutes — and there is no call on Android that makes them
 * forget. Short, rare, and the reason the policy never hands out IPv6 on credit.
 */
class Ipv6Guard(
    filesDir: File,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
    private val configs: TunnelConfigBuilder,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
    onWriteFailure: (Throwable) -> Unit = {},
) {

    /** What the settings screen says under the switch. */
    sealed interface Status {
        /** No tunnel, IPv6 switched off, or nothing proxied — the setting speaks for itself. */
        data object Idle : Status

        /** Servers are being asked; until they answer, applications get IPv4 only. */
        data object Checking : Status

        /** Applications are being handed IPv6 addresses. */
        data object Active : Status

        /** Held back because these members reached the probe and not the IPv6 address. */
        data class Refused(val servers: List<String>) : Status

        /** Held back because the server carrying traffic could not be asked at all. */
        data object Unchecked : Status
    }

    /**
     * Verdicts by exit host, kept across restarts: a server's IPv6 does not change between two
     * connections, and relearning it each time would put every first connection on IPv4.
     */
    private val store = JsonStore(
        file = File(filesDir, "ipv6.json"),
        serializer = MapSerializer(String.serializer(), Ipv6Verdict.serializer()),
        defaultValue = emptyMap(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * The answer a rebuild was already asked for on this connection, and for which members.
     *
     * A second rebuild for the same thing would mean the document did not take the answer, and
     * asking again would only turn that into a loop of reconnections. Cleared when the tunnel goes
     * down, not when it passes through Connecting — the rebuild passes through Connecting itself.
     */
    private var rebuiltFor: Pair<List<String>, Boolean>? = null

    /** For [TunnelConfigBuilder]: whether this document may answer `AAAA`. */
    fun allows(active: List<ProxyNode>, members: List<ProxyNode>): Boolean =
        Ipv6Policy.answersIpv6(settings.value, active, members, store.value)

    fun start() {
        scope.launch {
            // Latest, because a new state makes the check in flight about a tunnel that is gone.
            // A pointer swap re-emits Connected with the new server, which is the moment the
            // server carrying traffic changes and the question has to be asked again.
            tunnel.state.collectLatest { state ->
                when (state) {
                    is VpnState.Connected -> check()
                    is VpnState.Connecting -> Unit
                    else -> {
                        rebuiltFor = null
                        _status.value = Status.Idle
                    }
                }
            }
        }
    }

    private suspend fun check() {
        val wanted = settings.value
        val inForce = configs.ipv6.value ?: return
        if (!wanted.enableIpv6 || wanted.routingMode == RoutingMode.Direct) {
            _status.value = Status.Idle
            return
        }

        val activeIds = profiles.selectedNode()?.dialed()?.mapTo(mutableSetOf()) { it.id }.orEmpty()
        val active = inForce.members.filter { it.id in activeIds }

        val due = Ipv6Policy.due(inForce.members, store.value, System.currentTimeMillis())
        if (due.isNotEmpty()) {
            // A recheck of a document already answering IPv6 is not a reason to say it stopped.
            _status.value = if (inForce.answers) Status.Active else Status.Checking
            // A core that came up a moment ago is still dialling its first connections, and an
            // answer taken in that gap describes the gap.
            delay(SETTLE_MILLIS)
            record(due, tunnel.ipv6Through(due))
        }

        val verdicts = store.value
        val allowed = Ipv6Policy.answersIpv6(wanted, active, inForce.members, verdicts)
        val refusing = Ipv6Policy.refusing(inForce.members, verdicts)
        _status.value = when {
            allowed -> Status.Active
            // The server carrying traffic first: that is the name somebody is looking for.
            refusing.isNotEmpty() -> Status.Refused(
                refusing.sortedByDescending { it.id in activeIds }.map { it.name }.distinct(),
            )
            else -> Status.Unchecked
        }

        if (allowed == inForce.answers) return
        val target = inForce.members.map { it.id } to allowed
        if (rebuiltFor == target) {
            logs.trace(TAG, "document still disagrees after a rebuild (answers=${inForce.answers}); not rebuilding again")
            return
        }
        val carrying = profiles.selectedNode() ?: return
        rebuiltFor = target
        if (allowed) {
            logs.info(R.string.log_ipv6_enabled, carrying.name)
        } else {
            logs.warn(R.string.log_ipv6_withdrawn, refusing.firstOrNull()?.name ?: carrying.name)
        }
        // The resolver is baked into the document, so a different answer means a different
        // document; same server, same everything else.
        launcher.switchTo(carrying, reloadConfig = true)
    }

    /** Files what came back, and says so in the journal when an exit's answer changed. */
    private fun record(asked: List<ProxyNode>, answers: Map<String, Boolean>) {
        val now = System.currentTimeMillis()
        val before = store.value
        val fresh = asked.mapNotNull { member ->
            val passes = answers[member.id]
            logs.trace(TAG, "${member.name} (${Ipv6Policy.exitOf(member)}): ipv6=${passes ?: "not reached"}")
            passes?.let { Ipv6Policy.exitOf(member) to Ipv6Verdict(it, now) }
        }.toMap()
        if (fresh.isEmpty()) return
        store.update { it + fresh }

        // Once per change of mind, not once per check: a refusal that stands is already on the
        // settings screen, and repeating it twice a day would bury the journal lines that matter.
        asked.filter { member ->
            val exit = Ipv6Policy.exitOf(member)
            fresh[exit]?.passes == false && before[exit]?.passes != false
        }.distinctBy(Ipv6Policy::exitOf).forEach { logs.warn(R.string.log_ipv6_refused, it.name) }
    }

    private companion object {
        const val TAG = "YumiIpv6"

        /** Well inside the watchdog's fifteen-second grace, and long enough for a first dial. */
        const val SETTLE_MILLIS = 3_000L
    }
}
