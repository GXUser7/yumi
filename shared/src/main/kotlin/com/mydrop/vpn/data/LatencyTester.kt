package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.xray.XrayConfigFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Measures nodes through an ephemeral Xray core probe session and streams results back as
 * `(nodeId, millisOrNull)` as each node finishes.
 */
fun interface CoreProber {
    suspend fun probe(
        nodes: List<ProxyNode>,
        settings: AppSettings,
        timeoutMillis: Int,
        concurrency: Int,
        onResult: (nodeId: String, millis: Int?) -> Unit,
    )
}

/**
 * Reachability and real-delay probes against a server's endpoint.
 *
 * When a [CoreProber] is provided, [PingMode.UrlTest] measures every node end-to-end by fetching
 * the probe URL through an ephemeral Xray outbound, and fast socket modes ([PingMode.Tcp],
 * [PingMode.Tls], [PingMode.Median]) automatically upgrade QUIC, WireGuard, and fragmented TLS
 * nodes to the core as well. All modes optionally resolve server hostnames through DoH first so
 * operator DNS poisoning does not hide working nodes.
 */
class LatencyTester(
    private val maxConcurrency: Int = 16,
    private val timeoutProvider: () -> Int = { LatencyResult.TIMEOUT_MILLIS },
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val coreProber: CoreProber? = null,
    private val isTunnelRunning: () -> Boolean = { false },
    private val resolveNode: (ProxyNode, String) -> ProxyNode = ServerAddressResolver::resolveNode,
) {

    suspend fun measure(node: ProxyNode, mode: PingMode = PingMode.UrlTest): LatencyResult {
        val settings = settingsProvider()
        if (needsCoreProbe(node, mode, settings)) {
            return measureAll(listOf(node), mode).single()
        }
        val resolved = withContext(Dispatchers.IO) { maybeResolve(node, settings) }
        val group = resolved.group
        if (group != null && group.members.isNotEmpty()) return measureGroup(resolved, group, mode)
        return measureOne(resolved, mode)
    }

    /**
     * A group answers for the member that would carry it: every member probed at once, and the
     * result is the one [NodeGroup.pick] chooses — the provider's order, inside its limit.
     *
     * This used to probe the group's own endpoint, which is its first member, and nothing else.
     * On Quattro's "LTE Авто" servers the first member is the cheapest one, a server abroad, and
     * the six behind it are relays inside the country — which is the whole point of the group on
     * a mobile network that lets only domestic addresses through. So exactly where the group
     * works, its first member is the one address that cannot answer, and the list showed every
     * one of them as unreachable while the tunnel, choosing among all seven, rode a relay fine.
     */
    private suspend fun measureGroup(node: ProxyNode, group: NodeGroup, mode: PingMode): LatencyResult {
        val results = coroutineScope {
            group.members.map { member -> async { measureOne(member, mode) } }.awaitAll()
        }
        // At least a millisecond: [NodeGroup.pick] reads zero as "did not answer", and a member on
        // the same network can answer inside one.
        val delays = results.withIndex()
            .filter { (_, result) -> !result.failed }
            .associate { (index, result) -> index to result.millis.coerceAtLeast(1) }
        val chosen = group.pick(delays)
        return LatencyResult(
            nodeId = node.id,
            millis = chosen?.let(delays::get) ?: 0,
            measuredAtEpochMillis = System.currentTimeMillis(),
            failed = chosen == null,
        )
    }

    private suspend fun measureOne(node: ProxyNode, mode: PingMode): LatencyResult =
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

    /** One socket probe. Returns the round trip in milliseconds, or null if it did not answer. */
    private fun probe(node: ProxyNode, mode: PingMode): Int? {
        val timeout = effectiveTimeoutMillis()
        val start = System.nanoTime()
        val ok = when {
            node.protocol.isQuicBased -> quicProbe(node, timeout)
            mode == PingMode.Tls && node.tls?.enabled == true -> tlsHandshake(node, timeout)
            else -> tcpHandshake(node, timeout)
        }
        return if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
    }

    private fun tcpHandshake(node: ProxyNode, timeout: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(node.server, node.port), timeout) }
    }.isSuccess

    /**
     * Connect plus a full TLS handshake. Costs two or three round trips instead of one, and in
     * exchange proves the endpoint really terminates TLS — which for a REALITY or masquerading
     * node is the difference between "the port is open" and "the disguise is up".
     *
     * Certificates are not validated: see [probeSocketFactory] for why that is the only setting
     * under which this mode measures anything at all.
     */
    private fun tlsHandshake(node: ProxyNode, timeout: Int): Boolean = runCatching {
        Socket().use { raw ->
            raw.connect(InetSocketAddress(node.server, node.port), timeout)
            val name = node.tls?.serverName?.takeIf(String::isNotEmpty) ?: node.server
            (probeSocketFactory.createSocket(raw, name, node.port, false)
                as SSLSocket).use { tls ->
                tls.soTimeout = timeout
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
     * Fallback QUIC probe when no [CoreProber] is available. Connects the UDP socket so ICMP Port
     * Unreachable fails immediately and retries once on timeout inside the budget.
     */
    private fun quicProbe(node: ProxyNode, timeout: Int): Boolean = runCatching {
        DatagramSocket().use { socket ->
            val address = InetAddress.getByName(node.server)
            socket.connect(address, node.port)
            val packet = versionNegotiationProbe()
            val reply = ByteArray(1500)
            val deadlineNanos = System.nanoTime() + timeout * 1_000_000L
            val retryGapMillis = minOf(QUIC_RETRY_GAP_MILLIS, timeout / 3).coerceAtLeast(100)
            var attempt = 0
            while (attempt < QUIC_MAX_ATTEMPTS) {
                val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMillis <= 0) break
                val sliceMillis = if (attempt == 0 && remainingMillis > retryGapMillis + 150) {
                    retryGapMillis
                } else {
                    remainingMillis
                }
                socket.soTimeout = sliceMillis
                socket.send(DatagramPacket(packet, packet.size))
                try {
                    socket.receive(DatagramPacket(reply, reply.size))
                    return@runCatching true
                } catch (_: SocketTimeoutException) {
                    attempt++
                }
            }
            false
        }
    }.getOrDefault(false)

    private fun versionNegotiationProbe(): ByteArray {
        val packet = ByteArray(QUIC_DATAGRAM_SIZE)
        packet[0] = 0xC0.toByte()
        packet[1] = 0x0A; packet[2] = 0x0A; packet[3] = 0x0A; packet[4] = 0x0A
        packet[5] = 8
        Random.nextBytes(packet, 6, 14)
        packet[14] = 8
        Random.nextBytes(packet, 15, 23)
        return packet
    }

    /** Probes many servers at once, preserving input order and streaming results as each completes. */
    suspend fun measureAll(
        nodes: List<ProxyNode>,
        mode: PingMode = PingMode.UrlTest,
        onResult: (LatencyResult) -> Unit = {},
    ): List<LatencyResult> {
        if (nodes.isEmpty()) return emptyList()
        val settings = settingsProvider()
        val timeout = effectiveTimeoutMillis()

        val coreItems = ArrayList<IndexedValue<ProxyNode>>()
        val socketItems = ArrayList<IndexedValue<ProxyNode>>()
        nodes.forEachIndexed { index, node ->
            if (needsCoreProbe(node, mode, settings)) {
                coreItems.add(IndexedValue(index, node))
            } else {
                socketItems.add(IndexedValue(index, node))
            }
        }

        val orderedResults = AtomicReferenceArray<LatencyResult>(nodes.size)
        coroutineScope {
            if (coreItems.isNotEmpty()) {
                launch {
                    measureWithCore(coreItems, settings, timeout, orderedResults, onResult)
                }
            }
            if (socketItems.isNotEmpty()) {
                val gate = Semaphore(maxConcurrency)
                socketItems.forEach { (index, node) ->
                    launch {
                        gate.withPermit {
                            val resolved = withContext(Dispatchers.IO) { maybeResolve(node, settings) }
                            val group = resolved.group
                            val result = if (group != null && group.members.isNotEmpty()) {
                                measureGroup(resolved, group, mode)
                            } else {
                                measureOne(resolved, mode)
                            }
                            orderedResults.set(index, result)
                            onResult(result)
                        }
                    }
                }
            }
        }

        return List(nodes.size) { index ->
            orderedResults.get(index) ?: LatencyResult(
                nodeId = nodes[index].id,
                millis = 0,
                measuredAtEpochMillis = System.currentTimeMillis(),
                failed = true,
            )
        }
    }

    private suspend fun measureWithCore(
        items: List<IndexedValue<ProxyNode>>,
        settings: AppSettings,
        timeout: Int,
        orderedResults: AtomicReferenceArray<LatencyResult>,
        onResult: (LatencyResult) -> Unit,
    ) {
        val prober = coreProber ?: return

        val uniqueMembers = LinkedHashMap<String, ProxyNode>()
        val memberToPlainTargets = HashMap<String, MutableList<IndexedValue<ProxyNode>>>()
        val memberToGroups = HashMap<String, MutableList<GroupTracker>>()
        val trackers = ArrayList<GroupTracker>()

        withContext(Dispatchers.IO) {
            for ((index, node) in items) {
                val group = node.group
                if (group != null && group.members.isNotEmpty()) {
                    val resolvedMembers = group.members.map { maybeResolve(it, settings) }
                    resolvedMembers.forEach { member -> uniqueMembers.putIfAbsent(member.id, member) }
                    val tracker = GroupTracker(index = index, node = node, group = group)
                    trackers.add(tracker)
                    resolvedMembers.mapTo(HashSet()) { it.id }.forEach { memberId ->
                        memberToGroups.getOrPut(memberId) { ArrayList(2) }.add(tracker)
                    }
                } else {
                    val resolved = maybeResolve(node, settings)
                    uniqueMembers.putIfAbsent(resolved.id, resolved)
                    memberToPlainTargets
                        .getOrPut(resolved.id) { ArrayList(1) }
                        .add(IndexedValue(index, node))
                }
            }
        }

        val lock = Any()
        val reportedIndices = HashSet<Int>(items.size)
        val concurrency = if (isTunnelRunning()) CORE_CONCURRENCY_CONNECTED else CORE_CONCURRENCY_IDLE

        prober.probe(uniqueMembers.values.toList(), settings, timeout, concurrency) { memberId, millis ->
            val readyToEmit = ArrayList<LatencyResult>(2)
            val now = System.currentTimeMillis()
            synchronized(lock) {
                memberToPlainTargets[memberId]?.forEach { (index, node) ->
                    if (reportedIndices.add(index)) {
                        val ok = millis != null && millis > 0
                        val lr = LatencyResult(
                            nodeId = node.id,
                            millis = if (ok) millis!! else 0,
                            measuredAtEpochMillis = now,
                            failed = !ok,
                        )
                        orderedResults.set(index, lr)
                        readyToEmit.add(lr)
                    }
                }
                memberToGroups[memberId]?.forEach { tracker ->
                    tracker.record(memberId, millis)
                    if (tracker.isComplete() && reportedIndices.add(tracker.index)) {
                        val lr = tracker.buildResult(now)
                        orderedResults.set(tracker.index, lr)
                        readyToEmit.add(lr)
                    }
                }
            }
            readyToEmit.forEach(onResult)
        }

        if (!coroutineContext.isActive) return

        val missing = ArrayList<LatencyResult>()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (tracker in trackers) {
                if (reportedIndices.add(tracker.index)) {
                    val lr = tracker.buildResult(now)
                    orderedResults.set(tracker.index, lr)
                    missing.add(lr)
                }
            }
            for ((index, node) in items) {
                if (reportedIndices.add(index)) {
                    val lr = LatencyResult(
                        nodeId = node.id,
                        millis = 0,
                        measuredAtEpochMillis = now,
                        failed = true,
                    )
                    orderedResults.set(index, lr)
                    missing.add(lr)
                }
            }
        }
        missing.forEach(onResult)
    }

    internal fun needsCoreProbe(node: ProxyNode, mode: PingMode, settings: AppSettings): Boolean {
        if (coreProber == null) return false
        if (mode == PingMode.UrlTest) return true
        val members = node.group?.members?.takeIf { it.isNotEmpty() } ?: listOf(node)
        return members.any { member ->
            member.protocol.isQuicBased ||
                member.protocol == Protocol.WIREGUARD ||
                (mode == PingMode.Tls && XrayConfigFactory.usesFragment(member, settings))
        }
    }

    private fun maybeResolve(node: ProxyNode, settings: AppSettings): ProxyNode =
        if (settings.resolveServerAddress) {
            resolveNode(node, settings.resolveDohUrl)
        } else {
            node
        }

    private fun effectiveTimeoutMillis(): Int = timeoutProvider().coerceIn(
        AppSettings.MIN_PROBE_TIMEOUT_MILLIS,
        AppSettings.MAX_PROBE_TIMEOUT_MILLIS,
    )

    private class GroupTracker(
        val index: Int,
        val node: ProxyNode,
        val group: NodeGroup,
    ) {
        private val expectedMemberIds: Set<String> = group.members.mapTo(HashSet()) { it.id }
        private val memberDelays = HashMap<String, Int?>(expectedMemberIds.size)

        fun record(memberId: String, millis: Int?) {
            memberDelays[memberId] = millis
        }

        fun isComplete(): Boolean = memberDelays.size >= expectedMemberIds.size

        fun buildResult(now: Long): LatencyResult {
            val delays = HashMap<Int, Int>(group.members.size)
            group.members.forEachIndexed { idx, member ->
                val ms = memberDelays[member.id]
                if (ms != null && ms > 0) {
                    delays[idx] = ms.coerceAtLeast(1)
                }
            }
            val chosen = group.pick(delays)
            return LatencyResult(
                nodeId = node.id,
                millis = chosen?.let(delays::get) ?: 0,
                measuredAtEpochMillis = now,
                failed = chosen == null,
            )
        }
    }

    private companion object {
        const val MEDIAN_SAMPLES = 3
        const val QUIC_DATAGRAM_SIZE = 1200
        const val QUIC_MAX_ATTEMPTS = 2
        const val QUIC_RETRY_GAP_MILLIS = 600
        const val CORE_CONCURRENCY_IDLE = 12
        const val CORE_CONCURRENCY_CONNECTED = 8
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
