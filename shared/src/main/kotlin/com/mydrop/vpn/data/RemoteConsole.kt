package com.mydrop.vpn.data

import com.mydrop.vpn.remote.RemoteBond
import com.mydrop.vpn.remote.RemoteClient
import com.mydrop.vpn.remote.RemoteCommand
import com.mydrop.vpn.remote.RemoteInvite
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteProbe
import com.mydrop.vpn.remote.RemoteSighting
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteSubscription
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
    val subscriptions: List<RemoteSubscription> = emptyList(),
    val cachedSnapshots: Map<String, RemoteSnapshot> = emptyMap(),
    val cachedNodes: Map<String, List<RemoteNode>> = emptyMap(),
    val cachedSubscriptions: Map<String, List<RemoteSubscription>> = emptyMap(),
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
    private var lastActiveHostId: String? = null

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
                val cur = _state.value
                val updatedActive = cur.active?.let { active ->
                    stored.bonds.firstOrNull { it.hostId == active.hostId }
                }
                _state.value = cur.copy(
                    bonds = stored.bonds,
                    active = updatedActive,
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

    /** Starts driving the given television, or resumes the most recently used one. */
    fun open(hostId: String? = null, forceSearch: Boolean = false) {
        val bonds = store.value.bonds
        val bond = (hostId ?: lastActiveHostId)?.let { id -> bonds.firstOrNull { it.hostId == id } }
            ?: bonds.firstOrNull()
        if (bond == null) {
            stopLoop()
            lastActiveHostId = null
            _state.value = _state.value.copy(
                active = null,
                link = RemoteLinkState.Idle,
                snapshot = null,
                nodes = emptyList(),
                subscriptions = emptyList(),
            )
            look()
            return
        }
        if (!forceSearch && loop?.isActive == true && _state.value.active?.hostId == bond.hostId) return
        stopLoop()
        lastActiveHostId = bond.hostId
        val cur = _state.value
        _state.value = cur.copy(
            active = bond,
            link = RemoteLinkState.Connecting,
            snapshot = cur.cachedSnapshots[bond.hostId],
            nodes = cur.cachedNodes[bond.hostId].orEmpty(),
            subscriptions = cur.cachedSubscriptions[bond.hostId].orEmpty(),
        )
        outbox = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        loop = scope.launch { run(bond, initialSearch = forceSearch) }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
        outbox.close()
    }

    fun close() {
        stopLoop()
        _state.value = _state.value.copy(link = RemoteLinkState.Idle)
    }

    fun reconnect() {
        open(_state.value.active?.hostId, forceSearch = true)
    }

    fun rename(hostId: String, alias: String) {
        store.renameBond(hostId, alias)
    }

    fun send(command: RemoteCommand) {
        outbox.trySend(command)
    }

    /** The introduction, from a code the camera read. */
    suspend fun pair(invite: RemoteInvite): Result<RemoteBond> {
        _state.value = _state.value.copy(pairing = true)
        val result = runCatching { client.pair(invite, deviceName()) }
        result.getOrNull()?.let { paired ->
            store.remember(paired)
            val stored = store.value.bonds.firstOrNull { it.hostId == paired.hostId } ?: paired
            lastActiveHostId = stored.hostId
            _state.value = _state.value.copy(active = stored)
        }
        _state.value = _state.value.copy(pairing = false)
        result.getOrNull()?.let { open(it.hostId) }
        return result
    }

    fun forget(hostId: String) {
        val wasActive = _state.value.active?.hostId == hostId
        if (wasActive) stopLoop()
        if (lastActiveHostId == hostId) lastActiveHostId = null
        store.forgetBond(hostId)
        _state.value = _state.value.let { cur ->
            cur.copy(
                cachedSnapshots = cur.cachedSnapshots - hostId,
                cachedNodes = cur.cachedNodes - hostId,
                cachedSubscriptions = cur.cachedSubscriptions - hostId,
                active = if (wasActive) null else cur.active,
                snapshot = if (wasActive) null else cur.snapshot,
                nodes = if (wasActive) emptyList() else cur.nodes,
                subscriptions = if (wasActive) emptyList() else cur.subscriptions,
            )
        }
        if (wasActive) {
            open(store.value.bonds.firstOrNull()?.hostId)
        }
    }

    private suspend fun run(initial: RemoteBond, initialSearch: Boolean = false) {
        var bond = initial
        var searched = initialSearch
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
                ) { snapshot -> receive(bond.hostId, snapshot) }
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
            )
            delay(RETRY_MILLIS)
        }
    }

    private fun receive(hostId: String, snapshot: RemoteSnapshot) {
        store.rememberMeta(
            hostId = hostId,
            hostName = snapshot.hostName,
            palette = snapshot.settings?.palette,
            visualizer = snapshot.settings?.visualizer,
        )
        val current = _state.value
        val updatedNodes = snapshot.nodes ?: current.cachedNodes[hostId].orEmpty()
        val updatedSubs = snapshot.subscriptions ?: current.cachedSubscriptions[hostId].orEmpty()
        val nextSnapshots = current.cachedSnapshots + (hostId to snapshot)
        val nextNodes = current.cachedNodes + (hostId to updatedNodes)
        val nextSubs = current.cachedSubscriptions + (hostId to updatedSubs)
        _state.value = if (current.active?.hostId == hostId) {
            current.copy(
                link = RemoteLinkState.Online,
                snapshot = snapshot,
                nodes = updatedNodes,
                subscriptions = updatedSubs,
                cachedSnapshots = nextSnapshots,
                cachedNodes = nextNodes,
                cachedSubscriptions = nextSubs,
            )
        } else {
            current.copy(
                cachedSnapshots = nextSnapshots,
                cachedNodes = nextNodes,
                cachedSubscriptions = nextSubs,
            )
        }
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
