package com.mydrop.vpn.data

import com.mydrop.vpn.remote.RemoteBond
import com.mydrop.vpn.remote.RemoteClient
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteInvite
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteProbe
import com.mydrop.vpn.remote.RemoteSighting
import com.mydrop.vpn.remote.RemoteSnapshot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the phone's end of the wire is doing, in the only four states worth drawing. */
sealed interface RemoteLinkState {
    /** Nobody is looking at the remote screen. */
    data object Idle : RemoteLinkState

    data object Searching : RemoteLinkState
    data object Connecting : RemoteLinkState
    data object Online : RemoteLinkState

    /** Tried and failed; the loop is waiting before it tries again. */
    data class Offline(val reason: String) : RemoteLinkState
}

data class RemoteConsoleState(
    val bonds: List<RemoteBond> = emptyList(),
    /** Televisions heard on the network, paired or not. */
    val sightings: List<RemoteSighting> = emptyList(),
    val active: RemoteBond? = null,
    val link: RemoteLinkState = RemoteLinkState.Idle,
    val snapshot: RemoteSnapshot? = null,
    /**
     * The last full server list the television sent.
     *
     * Kept here rather than read off [snapshot] because most snapshots do not carry one — see
     * `RemoteSnapshot.nodesRevision`. Dropping the list every second and drawing an empty one in
     * between is exactly the flicker that field exists to avoid.
     */
    val nodes: List<RemoteNode> = emptyList(),
    val pairing: Boolean = false,
)

/**
 * The phone's end of the remote, as one object the screen can watch.
 *
 * Everything about *staying* connected lives here: which address to try, when to search for a set
 * that has moved, how long to wait before trying again. The screen presses buttons and reads a
 * state; it never learns that there is a socket.
 */
class RemoteConsole(
    private val scope: CoroutineScope,
    private val store: RemoteRepository,
    private val client: RemoteClient,
    private val probe: RemoteProbe,
    private val deviceName: () -> String,
) {
    private val _state = MutableStateFlow(RemoteConsoleState())
    val state: StateFlow<RemoteConsoleState> = _state.asStateFlow()

    private var loop: Job? = null

    /**
     * Commands waiting to go out.
     *
     * Conflated to a handful and dropping the oldest, because a queue is the wrong idea here: if
     * the link is down, five taps on the connect button are not five connections to make when it
     * comes back — they are one impatient user.
     */
    private var outbox = Channel<RemoteCommand>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            store.state.collect { stored ->
                _state.value = _state.value.copy(
                    bonds = stored.bonds,
                    active = _state.value.active?.let { active ->
                        stored.bonds.firstOrNull { it.hostId == active.hostId }
                    },
                )
            }
        }
    }

    /**
     * Listens for televisions without connecting to any.
     *
     * Cheap enough to run whenever the tunnel screen is on show: one broadcast and a second and a
     * half of waiting. What it buys is the door to the remote appearing by itself on a phone that
     * has never been introduced to anything.
     */
    fun look() {
        scope.launch {
            val heard = runCatching { probe.sweep() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(sightings = heard)
        }
    }

    /** Starts driving the given television, or the only one there is. */
    fun open(hostId: String? = null) {
        val bond = store.value.bonds.let { bonds ->
            hostId?.let { id -> bonds.firstOrNull { it.hostId == id } } ?: bonds.firstOrNull()
        }
        if (bond == null) {
            _state.value = _state.value.copy(active = null, link = RemoteLinkState.Idle)
            look()
            return
        }
        if (loop?.isActive == true && _state.value.active?.hostId == bond.hostId) return
        close()
        _state.value = _state.value.copy(active = bond, link = RemoteLinkState.Connecting)
        outbox = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        loop = scope.launch { run(bond) }
    }

    fun close() {
        loop?.cancel()
        loop = null
        outbox.close()
        _state.value = _state.value.copy(link = RemoteLinkState.Idle, snapshot = null)
    }

    fun send(command: RemoteCommand) {
        outbox.trySend(command)
    }

    /** The introduction, from a code the camera read. */
    suspend fun pair(invite: RemoteInvite): Result<RemoteBond> {
        _state.value = _state.value.copy(pairing = true)
        val result = runCatching { client.pair(invite, deviceName()) }
        result.getOrNull()?.let {
            store.remember(it)
            _state.value = _state.value.copy(active = it)
        }
        _state.value = _state.value.copy(pairing = false)
        result.getOrNull()?.let { open(it.hostId) }
        return result
    }

    fun forget(hostId: String) {
        if (_state.value.active?.hostId == hostId) close()
        store.forgetBond(hostId)
        _state.value = _state.value.copy(active = null, snapshot = null, nodes = emptyList())
    }

    private suspend fun run(initial: RemoteBond) {
        var bond = initial
        var searched = false
        // The job's own context rather than the container's scope, which never ends: what closes
        // this loop is [close] cancelling the job, and asking the wrong one would leave it running
        // behind a screen nobody is on.
        while (currentCoroutineContext().isActive) {
            // The remembered address first. It is right almost every time, and it is the only path
            // that does not cost a second and a half of shouting at the network before anything
            // happens on screen.
            val target = addressFor(bond, force = searched)
            if (target == null) {
                _state.value = _state.value.copy(link = RemoteLinkState.Offline(OFF_NETWORK))
                searched = true
                delay(RETRY_MILLIS)
                continue
            }
            _state.value = _state.value.copy(link = RemoteLinkState.Connecting)
            val outcome = runCatching {
                client.session(
                    bond = bond,
                    host = target.first,
                    port = target.second,
                    deviceName = deviceName(),
                    commands = outbox,
                ) { snapshot -> receive(snapshot) }
            }
            // runCatching catches everything, and everything here includes the cancellation that
            // closing the screen throws. Rethrown rather than reported as a lost television.
            (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
            val reached = _state.value.link == RemoteLinkState.Online
            if (reached) {
                store.rememberAddress(bond.hostId, target.first, target.second)
                bond = store.value.bonds.firstOrNull { it.hostId == bond.hostId } ?: bond
            }
            // A session that ended is a session to start again: the phone's screen went off, the
            // set was rebooted, the Wi-Fi hiccuped. The address that just worked is worth trying
            // again first; only one that did not sends the next attempt out to search.
            searched = !reached
            _state.value = _state.value.copy(
                link = RemoteLinkState.Offline(outcome.exceptionOrNull()?.shortReason() ?: LOST),
                snapshot = null,
            )
            delay(RETRY_MILLIS)
        }
    }

    private fun receive(snapshot: RemoteSnapshot) {
        val current = _state.value
        _state.value = current.copy(
            link = RemoteLinkState.Online,
            snapshot = snapshot,
            nodes = snapshot.nodes ?: current.nodes,
        )
    }

    /** Where to knock: the remembered address, or whatever the network says now. */
    private suspend fun addressFor(bond: RemoteBond, force: Boolean): Pair<String, Int>? {
        if (!force && bond.lastHost.isNotEmpty()) return bond.lastHost to bond.lastPort
        _state.value = _state.value.copy(link = RemoteLinkState.Searching)
        val heard = runCatching { probe.sweep() }.getOrDefault(emptyList())
        _state.value = _state.value.copy(sightings = heard)
        heard.firstOrNull { it.hostId == bond.hostId }?.let { return it.host to it.port }
        return bond.lastHost.takeIf { it.isNotEmpty() }?.let { it to bond.lastPort }
    }

    private companion object {
        const val RETRY_MILLIS = 2_500L
        const val LOST = "lost"
        const val OFF_NETWORK = "off-network"
    }
}

private fun Throwable.shortReason(): String =
    message?.take(120)?.ifBlank { null } ?: this::class.simpleName.orEmpty()
