package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Reachability probes against a server's endpoint.
 *
 * None of these say anything about whether the credentials work — that would need the core
 * running and a separate outbound per node. What they do is separate live endpoints from blocked
 * or dead ones and give a number worth sorting by, which is the job the list actually needs.
 *
 * The transport dictates the probe: TCP protocols get a handshake, QUIC ones (Hysteria2, TUIC)
 * get a UDP probe, because they have no TCP port to connect to and used to be reported as
 * unmeasurable for that reason.
 */
class LatencyTester(private val maxConcurrency: Int = 16) {

    suspend fun measure(node: ProxyNode, mode: PingMode = PingMode.Tcp): LatencyResult =
        withContext(Dispatchers.IO) {
            val samples = if (mode == PingMode.Median) MEDIAN_SAMPLES else 1
            val measurements = ArrayList<Int>(samples)

            repeat(samples) {
                probe(node, mode)?.let(measurements::add)
            }

            // A median needs a majority of successes to mean anything; one lucky probe out of
            // three says the endpoint is flaky, not fast.
            val ok = measurements.size > samples / 2
            LatencyResult(
                nodeId = node.id,
                millis = if (ok) measurements.sorted()[measurements.size / 2] else 0,
                measuredAtEpochMillis = System.currentTimeMillis(),
                failed = !ok,
            )
        }

    /** One probe. Returns the round trip in milliseconds, or null if it did not answer. */
    private fun probe(node: ProxyNode, mode: PingMode): Int? {
        val start = System.nanoTime()
        val ok = when {
            node.protocol.isQuicBased -> quicProbe(node)
            mode == PingMode.Tls -> tlsHandshake(node)
            else -> tcpHandshake(node)
        }
        return if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
    }

    private fun tcpHandshake(node: ProxyNode): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(node.server, node.port), TIMEOUT) }
    }.isSuccess

    /**
     * Connect plus a full TLS handshake. Costs two or three round trips instead of one, and in
     * exchange proves the endpoint really terminates TLS — which for a REALITY or masquerading
     * node is the difference between "the port is open" and "the disguise is up".
     *
     * Certificates are not validated: see [probeSocketFactory] for why that is the only setting
     * under which this mode measures anything at all.
     */
    private fun tlsHandshake(node: ProxyNode): Boolean = runCatching {
        Socket().use { raw ->
            raw.connect(InetSocketAddress(node.server, node.port), TIMEOUT)
            val name = node.tls?.serverName?.takeIf(String::isNotEmpty) ?: node.server
            (probeSocketFactory.createSocket(raw, name, node.port, false)
                as SSLSocket).use { tls ->
                tls.soTimeout = TIMEOUT
                tls.startHandshake()
            }
        }
    }.isSuccess

    /**
     * A factory that trusts every chain, for probes only.
     *
     * This used to be `SSLSocketFactory.getDefault()`, whose trust managers validate the chain
     * inside `startHandshake()` — the exact opposite of what this probe needs. A REALITY node
     * serves a forged certificate by design and a self-signed Hysteria2 or Trojan node chains to
     * no system root, so the handshake threw for all of them and every such server was recorded
     * as unreachable. That reading was not merely cosmetic: [FailoverWatchdog] treats a failed
     * probe as a dead server, so TLS ping mode moved the tunnel off servers that were working.
     *
     * Trusting everything is safe here in a way it never is on a data path: the socket carries no
     * traffic and no credentials, it is closed the moment the handshake completes, and the core
     * does its own TLS with its own settings and its own trust store.
     */
    private val probeSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(TrustEveryChain), null) }
            .socketFactory
    }

    /**
     * QUIC endpoints answer an unknown version with a Version Negotiation packet — RFC 9000 §6
     * requires it. So we send a long-header packet carrying a reserved version and wait for any
     * reply; anything coming back means a QUIC server is listening.
     *
     * Two honest limits: a datagram carrying an Initial must be padded to 1200 bytes or servers
     * drop it, and endpoints with obfuscation (Hysteria2's salamander, say) will not answer at
     * all, so those still read as unreachable.
     */
    private fun quicProbe(node: ProxyNode): Boolean = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT
            val address = InetAddress.getByName(node.server)
            val packet = versionNegotiationProbe()
            socket.send(DatagramPacket(packet, packet.size, address, node.port))

            val reply = ByteArray(1500)
            socket.receive(DatagramPacket(reply, reply.size))
            true
        }
    }.getOrDefault(false)

    private fun versionNegotiationProbe(): ByteArray {
        val packet = ByteArray(QUIC_DATAGRAM_SIZE)
        // Long header with the fixed bit set; the low bits are packet-type and are ignored by a
        // server that has already decided it does not know the version.
        packet[0] = 0xC0.toByte()
        // A reserved "greasing" version no server implements, which is what forces the answer.
        packet[1] = 0x0A; packet[2] = 0x0A; packet[3] = 0x0A; packet[4] = 0x0A
        packet[5] = 8                                   // destination connection id length
        Random.nextBytes(packet, 6, 14)
        packet[14] = 8                                  // source connection id length
        Random.nextBytes(packet, 15, 23)
        // The remainder stays zero: token length, length and payload all read as empty, and the
        // padding to 1200 bytes is exactly what the anti-amplification rule wants to see.
        return packet
    }

    /** Probes many servers at once, capped so a large subscription cannot exhaust sockets. */
    suspend fun measureAll(
        nodes: List<ProxyNode>,
        mode: PingMode = PingMode.Tcp,
        onResult: (LatencyResult) -> Unit = {},
    ): List<LatencyResult> = coroutineScope {
        val gate = Semaphore(maxConcurrency)
        nodes.map { node ->
            async { gate.withPermit { measure(node, mode).also(onResult) } }
        }.awaitAll()
    }

    private companion object {
        const val TIMEOUT = LatencyResult.TIMEOUT_MILLIS
        const val MEDIAN_SAMPLES = 3
        const val QUIC_DATAGRAM_SIZE = 1200
    }
}

/**
 * Accepts any certificate. Reachable only through [LatencyTester.probeSocketFactory], which
 * documents why a probe must not validate; nothing else in the app may use it.
 */
private object TrustEveryChain : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
