package com.mydrop.vpn.remote

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import javax.net.SocketFactory
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/**
 * One conversation, sealed frame by frame.
 *
 * The counters never travel: each side keeps its own and the numbers go into the tag instead, so a
 * frame that arrives twice, or out of order, or is played back in the wrong direction, is one the
 * receiver cannot open at all. There is nothing to check and therefore nothing to forget to check.
 */
internal class RemoteLink(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val key: ByteArray,
    private val sessionId: String,
    /** True on the phone, which writes the "up" direction and reads the "down" one. */
    private val phoneSide: Boolean,
) : Closeable {
    // Both start at one: zero belonged to the greeting and to the welcome that answered it.
    private var sent = 1L
    private var received = 1L
    private val writeLock = ReentrantLock()

    fun write(plain: ByteArray) = writeLock.withLock {
        val sealed = RemoteCrypto.seal(plain, key, RemoteCrypto.aad(sessionId, phoneSide, sent))
        sent++
        output.writeFrame(RemoteCrypto.json.encodeToString(sealed).toByteArray())
    }

    fun read(): ByteArray {
        val sealed = RemoteCrypto.json.decodeFromString<RemoteSealed>(
            input.readFrame().decodeToString(),
        )
        val plain = RemoteCrypto.open(
            sealed,
            key,
            RemoteCrypto.aad(sessionId, !phoneSide, received),
        )
        received++
        return plain
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** The television, as the wire sees it. */
data class RemoteIdentity(
    val id: String,
    val name: String,
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

/**
 * Who the television is willing to talk to.
 *
 * Kept as an interface so the socket code never touches the store: everything about trust — the
 * code currently on screen, the phones already introduced, whether the window is still open — is
 * one implementation away, in [com.mydrop.vpn.data.RemoteRepository].
 */
interface RemoteAuthority {
    fun identity(): RemoteIdentity

    /** The code on screen right now, or null when no pairing window is open. */
    fun openPairingCode(): String?

    /** Mints and stores a phone, closing the window behind it. Null if it has already shut. */
    fun acceptPairing(deviceName: String): RemotePeer?

    fun secretOf(peerId: String): String?

    fun seen(peerId: String)
}

/**
 * The television's end: a socket that stays open and answers with what it is doing.
 *
 * Bound to the wildcard address rather than to the Wi-Fi interface, which is what lets a set keep
 * answering after its lease changes: the alternative is noticing the change and rebinding, and a
 * remote that stops working until the app is restarted is worse than one bound a little wider.
 * Nothing is trusted for being on the right interface anyway — see [RemoteCrypto.sessionKey].
 */
class RemoteServer(
    private val scope: CoroutineScope,
    private val authority: RemoteAuthority,
    private val snapshots: StateFlow<RemoteSnapshot>,
    private val onCommand: suspend (RemoteCommand) -> Unit,
) {
    private val _port = MutableStateFlow(0)

    /** The port actually bound, or zero while the listener is down. */
    val port: StateFlow<Int> = _port.asStateFlow()

    private var job: Job? = null
    private var listener: ServerSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            // The fixed port first, so a phone with a remembered address can try it before it
            // starts shouting at the whole subnet. Anything already sitting on it — another copy
            // of this app, most likely — and the beacon hands out whatever we get instead.
            val server = runCatching { ServerSocket(REMOTE_PORT, BACKLOG) }
                .getOrElse { runCatching { ServerSocket(0, BACKLOG) }.getOrNull() }
                ?: return@launch
            server.soTimeout = ACCEPT_POLL_MILLIS
            listener = server
            _port.value = server.localPort
            try {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: SocketException) {
                        // How stop() reaches this thread: accept() has no suspension point for a
                        // cancel to land on, so the socket is closed under it and the wake arrives
                        // as an exception. Uncaught, it goes to the thread's default handler and
                        // takes the app down — which is exactly how leaving the pairing screen
                        // used to crash the television.
                        return@launch
                    }
                    scope.launch(Dispatchers.IO) { serve(client) }
                }
            } finally {
                runCatching { server.close() }
                if (listener === server) listener = null
                _port.value = 0
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { listener?.close() }
        listener = null
        _port.value = 0
    }

    private suspend fun serve(client: Socket) {
        val link = runCatching { handshake(client) }.getOrNull()
        if (link == null) {
            runCatching { client.close() }
            return
        }
        // A phone that has gone away stops reading long before TCP admits it, so the writer is
        // what usually notices first. Closing the link from there is what unblocks the reader,
        // which is parked in a blocking read that no cancellation can reach.
        val writer = scope.launch(Dispatchers.IO) {
            runCatching { pump(link) }
            link.close()
        }
        val outcome = runCatching {
            while (currentCoroutineContext().isActive) {
                val command = RemoteCrypto.json.decodeFromString<RemoteCommand>(
                    link.read().decodeToString(),
                )
                onCommand(command)
            }
        }
        writer.cancel()
        link.close()
        // onCommand suspends, so runCatching above catches the cancellation that stopping the
        // server throws as well as the socket errors it is there for. Only the socket errors are
        // this function's business.
        (outcome.exceptionOrNull() as? kotlin.coroutines.cancellation.CancellationException)
            ?.let { throw it }
    }

    private fun handshake(client: Socket): RemoteLink? {
        client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        client.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        val greeting = RemoteCrypto.json.decodeFromString<RemoteGreeting>(
            input.readFrame().decodeToString(),
        )
        if (greeting.version != REMOTE_VERSION) return null
        val sender = RemoteCrypto.publicKeyOf(greeting.senderPublicKey) ?: return null
        val identity = authority.identity()
        val introducing = greeting.peerId.isEmpty()
        // Answered by hanging up rather than by saying which of the two it was: "no such phone"
        // and "no code on screen" are different facts, and neither is one a stranger has earned.
        val secret =
            if (introducing) authority.openPairingCode() else authority.secretOf(greeting.peerId)
        if (secret.isNullOrEmpty()) return null

        val key = RemoteCrypto.sessionKey(identity.privateKey, sender, secret, greeting.sessionId)
        // The first thing sealed with that key. A wrong code or a stranger's secret fails here,
        // as an authentication tag that does not check out, and never gets further.
        val opening = RemoteCrypto.json.decodeFromString<RemoteOpening>(
            RemoteCrypto.open(
                RemoteSealed(greeting.iv, greeting.ciphertext),
                key,
                RemoteCrypto.aad(greeting.sessionId, fromPhone = true, counter = 0),
            ).decodeToString(),
        )

        val peer = if (introducing) {
            authority.acceptPairing(opening.deviceName.take(96)) ?: return null
        } else {
            authority.seen(greeting.peerId)
            RemotePeer(id = greeting.peerId, name = opening.deviceName, secret = secret)
        }

        val access = RemoteAccess(
            accepted = true,
            // Handed over exactly once, on the introduction. A phone that already has it is not
            // told it again — there is no reason for a secret to be on the wire twice.
            secret = if (introducing) peer.secret else "",
            peerId = peer.id,
            hostId = identity.id,
            hostName = identity.name,
        )
        output.writeFrame(
            RemoteCrypto.json.encodeToString(
                RemoteCrypto.seal(
                    RemoteCrypto.json.encodeToString(access).toByteArray(),
                    key,
                    RemoteCrypto.aad(greeting.sessionId, fromPhone = false, counter = 0),
                ),
            ).toByteArray(),
        )
        // Long enough that the phone's five-second keepalive can miss three in a row on a busy
        // network without the set deciding it has gone.
        client.soTimeout = IDLE_TIMEOUT_MILLIS
        return RemoteLink(client, input, output, key, greeting.sessionId, phoneSide = false)
    }

    /**
     * The whole picture, on every change and once a second regardless.
     *
     * The server list is the only expensive part and it changes about as often as a subscription
     * refreshes, so it is sent when [RemoteSnapshot.nodesRevision] says it has and left out of
     * every snapshot in between — three hundred servers at one hertz is eighteen kilobytes a
     * second spent on a list that has not moved since the app opened.
     */
    private suspend fun pump(link: RemoteLink) {
        var revision = -1
        while (currentCoroutineContext().isActive) {
            val snapshot = snapshots.value
            val payload = if (snapshot.nodesRevision == revision) snapshot.copy(nodes = null) else snapshot
            revision = snapshot.nodesRevision
            link.write(RemoteCrypto.json.encodeToString(payload).toByteArray())
            withTimeoutOrNull(HEARTBEAT_MILLIS) { snapshots.first { it != snapshot } }
        }
    }

    private companion object {
        const val BACKLOG = 4
        const val ACCEPT_POLL_MILLIS = 1_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 8_000
        const val IDLE_TIMEOUT_MILLIS = 20_000
        const val HEARTBEAT_MILLIS = 1_000L
    }
}

/** What a session ended as, so the phone can say something truer than "not connected". */
sealed interface RemoteOutcome {
    data object Closed : RemoteOutcome
    data class Refused(val reason: String) : RemoteOutcome
}

/**
 * The phone's end.
 *
 * Nothing here is a long-lived object: a session is a suspending call that runs until it fails or
 * the caller cancels it, which is how the caller gets to decide what "reconnect" means without
 * this class holding an opinion about it.
 */
class RemoteClient(private val context: Context) {

    /** The introduction. Returns the bond to keep, or throws if the television refused. */
    suspend fun pair(invite: RemoteInvite, deviceName: String): RemoteBond =
        withContext(Dispatchers.IO) {
            require(isPrivateIpv4(invite.host)) { "The TV address is not on a local network" }
            val hostKey = requireNotNull(RemoteCrypto.publicKeyOf(invite.hostPublicKey))
            open(invite.host, invite.port) { socket ->
                val (link, access) = greet(socket, hostKey, invite.code, peerId = "", deviceName)
                link.close()
                check(access.accepted && access.peerId.isNotEmpty() && access.secret.isNotEmpty()) {
                    access.reason.ifEmpty { "The TV refused the pairing code" }
                }
                RemoteBond(
                    hostId = access.hostId.ifEmpty { invite.hostId },
                    hostName = access.hostName.ifEmpty { invite.hostName },
                    hostPublicKey = invite.hostPublicKey,
                    peerId = access.peerId,
                    secret = access.secret,
                    lastHost = invite.host,
                    lastPort = invite.port,
                )
            }
        }

    /**
     * One session, held open until it breaks or the caller lets go.
     *
     * Commands arrive through [commands] rather than through a method, because the socket is
     * written from one place and only one: a `send` callable from anywhere would need a lock
     * around a stream that a cancelled session may already have closed.
     */
    suspend fun session(
        bond: RemoteBond,
        host: String,
        port: Int,
        deviceName: String,
        commands: ReceiveChannel<RemoteCommand>,
        onSnapshot: (RemoteSnapshot) -> Unit,
    ): RemoteOutcome = withContext(Dispatchers.IO) {
        if (!isPrivateIpv4(host)) return@withContext RemoteOutcome.Refused("not local")
        val hostKey = RemoteCrypto.publicKeyOf(bond.hostPublicKey)
            ?: return@withContext RemoteOutcome.Refused("bad key")
        open(host, port) { socket ->
            val (link, access) = greet(socket, hostKey, bond.secret, bond.peerId, deviceName)
            if (!access.accepted) {
                link.close()
                return@open RemoteOutcome.Refused(access.reason.ifEmpty { "refused" })
            }
            coroutineScope {
                val pump = launch(Dispatchers.IO) {
                    runCatching {
                        while (isActive) {
                            // Five seconds against the set's twenty: the point of a keepalive is
                            // for a link that has quietly died to be noticed by somebody, and the
                            // phone is the end with a user waiting in front of it.
                            val command = withTimeoutOrNull(KEEPALIVE_MILLIS) { commands.receive() }
                                ?: RemoteCommand.Look
                            link.write(RemoteCrypto.json.encodeToString(command).toByteArray())
                        }
                    }
                    // Whatever went wrong up there, the reader below is parked in a blocking read
                    // that no cancellation can reach. Closing the socket is what wakes it.
                    link.close()
                }
                val outcome = runCatching {
                    while (isActive) {
                        val snapshot = RemoteCrypto.json.decodeFromString<RemoteSnapshot>(
                            link.read().decodeToString(),
                        )
                        onSnapshot(snapshot)
                    }
                }
                pump.cancel()
                link.close()
                outcome.exceptionOrNull()?.let { throw it }
                RemoteOutcome.Closed
            }
        }
    }

    /**
     * Opens a socket on the local network rather than on whatever the system calls default.
     *
     * A phone with its own tunnel up has more than one network, and while this app is excluded
     * from its own VPN, "excluded" only decides which routes apply — it does not choose the
     * interface. Binding by name is what stops a remote from failing on exactly the phones this
     * whole app exists for.
     */
    private suspend fun <T> open(host: String, port: Int, body: suspend (Socket) -> T): T {
        var failure: Throwable? = null
        for (factory in localNetworks(context).map { it.socketFactory } + SocketFactory.getDefault()) {
            val socket = runCatching {
                (factory.createSocket() as Socket).also {
                    it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                }
            }.getOrElse { failure = it; null } ?: continue
            return try {
                body(socket)
            } finally {
                runCatching { socket.close() }
            }
        }
        throw failure ?: IllegalStateException("The TV did not answer")
    }

    private fun greet(
        socket: Socket,
        hostKey: PublicKey,
        secret: String,
        peerId: String,
        deviceName: String,
    ): Pair<RemoteLink, RemoteAccess> {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val ephemeral = RemoteCrypto.keyPair()
        val sessionId = RemoteCrypto.token()
        val key = RemoteCrypto.sessionKey(ephemeral.private, hostKey, secret, sessionId)
        val opening = RemoteCrypto.seal(
            RemoteCrypto.json.encodeToString(RemoteOpening(deviceName.take(96))).toByteArray(),
            key,
            RemoteCrypto.aad(sessionId, fromPhone = true, counter = 0),
        )
        output.writeFrame(
            RemoteCrypto.json.encodeToString(
                RemoteGreeting(
                    version = REMOTE_VERSION,
                    sessionId = sessionId,
                    peerId = peerId,
                    senderPublicKey = RemoteCrypto.encode(ephemeral.public),
                    iv = opening.iv,
                    ciphertext = opening.ciphertext,
                ),
            ).toByteArray(),
        )
        val welcome = RemoteCrypto.json.decodeFromString<RemoteSealed>(
            input.readFrame().decodeToString(),
        )
        val access = RemoteCrypto.json.decodeFromString<RemoteAccess>(
            RemoteCrypto.open(
                welcome,
                key,
                RemoteCrypto.aad(sessionId, fromPhone = false, counter = 0),
            ).decodeToString(),
        )
        socket.soTimeout = IDLE_TIMEOUT_MILLIS
        return RemoteLink(socket, input, output, key, sessionId, phoneSide = true) to access
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 8_000
        const val IDLE_TIMEOUT_MILLIS = 20_000
        const val KEEPALIVE_MILLIS = 5_000L
    }
}
