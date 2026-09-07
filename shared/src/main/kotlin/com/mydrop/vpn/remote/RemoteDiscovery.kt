package com.mydrop.vpn.remote

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/*
 * Finding the television again.
 *
 * A phone that was introduced last week knows a secret and an address, and only one of those keeps.
 * Leases move: the set is rebooted, the router hands it something else, somebody moves it to the
 * other band. So the address is a guess to try first and this is the fallback — a shout on the
 * local network and whatever answers to it.
 *
 * Ours rather than mDNS, deliberately. NSD on Android has a permission story that changed twice in
 * three releases and fails by finding nothing rather than by throwing, which is the worst way for
 * a feature to break: it looks like the television is off. Sixty lines of datagram does the one
 * thing needed here, in code that can be read end to end, and a broadcast crosses exactly the same
 * routers that multicast would.
 */

private const val PROBE = "yumi-remote-probe-v1"

@Serializable
private data class Beacon(val v: Int, val id: String, val name: String, val port: Int)

/** A television that answered. */
data class RemoteSighting(
    val hostId: String,
    val hostName: String,
    val host: String,
    val port: Int,
)

/**
 * The television's answer to "is anybody there".
 *
 * Says only what a phone standing in the same room can already read off the screen: which set this
 * is, what it is called, and where its control socket is. Nothing about the tunnel, nothing about
 * the subscription — anybody on the Wi-Fi can ask, and none of them have been introduced yet.
 */
class RemoteBeacon(
    private val scope: CoroutineScope,
    private val identity: () -> RemoteIdentity,
    private val port: () -> Int,
) {
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val server = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(REMOTE_BEACON_PORT))
                    soTimeout = 1_000
                }
            }.getOrNull() ?: return@launch
            socket = server
            val buffer = ByteArray(256)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        server.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: SocketException) {
                        // stop() closing the socket under a blocked receive, which arrives here as
                        // an exception rather than as cancellation. The same shape as the accept
                        // loop, and for the same reason: uncaught it would take the app down.
                        return@launch
                    }
                    val asked = String(packet.data, packet.offset, packet.length).trim()
                    if (asked != PROBE) continue
                    val listening = port()
                    if (listening == 0) continue
                    val me = identity()
                    val reply = RemoteCrypto.json.encodeToString(
                        Beacon(REMOTE_VERSION, me.id, me.name, listening),
                    ).toByteArray()
                    runCatching {
                        server.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } finally {
                runCatching { server.close() }
                if (socket === server) socket = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }
}

/** The phone's shout, and everything that answers it inside the window. */
class RemoteProbe(private val context: Context) {

    suspend fun sweep(windowMillis: Long = 1_400L): List<RemoteSighting> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, RemoteSighting>()
        val networks = localNetworks(context)
        // One socket per local network rather than one for all of them: a phone on Wi-Fi with an
        // Ethernet dock has two, and a socket bound to neither goes out whichever the system likes.
        val sockets = (networks.map { network ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(0))
                    network.bindSocket(this)
                }
            }.getOrNull()
        } + runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0))
            }
        }.getOrNull()).filterNotNull()
        if (sockets.isEmpty()) return@withContext emptyList()

        val question = PROBE.toByteArray()
        val addresses = broadcastAddresses(context)
        val deadline = System.currentTimeMillis() + windowMillis
        try {
            for (socket in sockets) {
                socket.soTimeout = POLL_MILLIS
                for (address in addresses) {
                    runCatching {
                        socket.send(
                            DatagramPacket(question, question.size, address, REMOTE_BEACON_PORT),
                        )
                    }
                }
            }
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                for (socket in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val heard = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!heard) continue
                    val beacon = runCatching {
                        RemoteCrypto.json.decodeFromString<Beacon>(
                            String(packet.data, packet.offset, packet.length),
                        )
                    }.getOrNull() ?: continue
                    if (beacon.v != REMOTE_VERSION || beacon.port !in 1..65535) continue
                    val host = packet.address?.hostAddress ?: continue
                    found[beacon.id] = RemoteSighting(
                        hostId = beacon.id,
                        hostName = beacon.name.take(96),
                        host = host,
                        port = beacon.port,
                    )
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
        found.values.toList()
    }

    private companion object {
        /** Short, because the loop above visits every socket in turn and must not park on one. */
        const val POLL_MILLIS = 120
    }
}
