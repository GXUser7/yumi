package com.mydrop.vpn.vpn

import android.util.Log
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.ProbeTargets
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.xray.yumi.HTTPResponse
import com.mydrop.vpn.xray.yumi.Logger
import com.mydrop.vpn.xray.yumi.ProbeCallback
import com.mydrop.vpn.xray.yumi.Protector
import com.mydrop.vpn.xray.yumi.TrafficGuard
import com.mydrop.vpn.xray.yumi.TunnelConn
import com.mydrop.vpn.xray.yumi.Yumi
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface TunnelBridge {
    val isRunning: Boolean

    fun probeHTTP(targetURL: String, timeoutMillis: Int, resolveDNS: Boolean): Int

    fun openHTTP(
        method: String,
        targetURL: String,
        headersJSON: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        resolveDNS: Boolean,
    ): TunnelHttpResponse

    fun dialTunnelTLS(
        host: String,
        port: Int,
        serverName: String,
        timeoutMillis: Int,
    ): TunnelStream
}

interface TunnelHttpResponse : Closeable {
    val statusCode: Int
    val contentLength: Long
    val contentEncoding: String
    val location: String
    val headersJSON: String
    fun header(name: String): String
    fun read(maxBytes: Int = 65536): ByteArray?
    fun readAll(maxBytes: Long = -1L): ByteArray
}

interface TunnelStream : Closeable {
    fun setReadTimeout(timeoutMillis: Int)
    fun setWriteTimeout(timeoutMillis: Int)
    fun read(maxBytes: Int = 65536): ByteArray?
    fun write(data: ByteArray): Int
}

/**
 * The Kotlin face of the Xray core.
 *
 * Everything gomobile generates is static, and it speaks `long` where the rest of the app says
 * `Int` — a Go `int` is 64-bit and crosses the boundary as a Java `long`, so file descriptors and
 * log levels arrive here as one. Wrapping it once keeps that detail from spreading and gives the
 * failure modes somewhere to live.
 *
 * The core is a process-wide singleton whether anyone likes it or not: it registers itself in the
 * package-level tables of the Go runtime inside this process, and there is exactly one of those. So
 * this is an object rather than something injectable, and [running] is the truth about the process
 * rather than about any particular service instance.
 *
 * The division of labour is the opposite of libbox's. That core inverted control and called back
 * into a sixty-method `PlatformInterface` to open the tunnel, enumerate interfaces and resolve
 * names. Xray asks for two things — a descriptor and a way to protect a socket — and decides the
 * rest from the document it was handed.
 */
object XrayCore : TunnelBridge {

    private const val TAG = "YumiCore"

    /**
     * The revision this core was built from, or null when the native library will not load at all.
     *
     * Read defensively because it is the first thing that touches the .so, and a missing library or
     * an ABI mismatch surfaces here as an `UnsatisfiedLinkError` from a static initialiser — which
     * would otherwise take down whatever happened to touch the core first, with a stack trace
     * pointing at the caller rather than at the cause.
     */
    val version: String? by lazy {
        runCatching { Yumi.version() }
            .onFailure { Log.e(TAG, "xray core will not load", it) }
            .getOrNull()
    }

    val running: Boolean
        get() = runCatching { Yumi.isRunning() }.getOrDefault(false)

    /**
     * Names the directory holding `geoip.dat` and `geosite.dat`.
     *
     * Must be set before [start] whenever the configuration names a `geoip:` or `geosite:` rule:
     * those are resolved while the document is being parsed, and one that cannot be resolved rejects
     * the whole configuration rather than skipping the rule that needed it.
     */
    fun setAssetPath(dir: String) {
        runCatching { Yumi.setAssetPath(dir) }
            .onFailure { Log.e(TAG, "asset path rejected", it) }
    }

    /**
     * Sends the core's own output somewhere, or nowhere when [sink] is null.
     *
     * Xray writes through a single process-wide handler rather than to a stream anything can
     * subscribe to, so this is the only way to see what it has to say. The binding registers the
     * bridge *after* the instance starts, because `app/log` installs its own while coming up and
     * only one handler exists.
     *
     * [sink] is called from whatever Go thread produced the line, which is never the main one.
     */
    fun setLogger(sink: ((LogEntry.Level, String) -> Unit)?) {
        runCatching {
            Yumi.setLogger(
                sink?.let { forward -> Logger { level, message -> forward(levelOf(level), message) } },
            )
        }.onFailure { Log.e(TAG, "log bridge rejected", it) }
    }

    /**
     * Xray's own numbering, which is neither the app's nor sing-box's: 0 unknown, 1 error,
     * 2 warning, 3 info, 4 debug (`common/log/log.pb.go`).
     *
     * Worth spelling out because the other core counted the other way — logrus order, where 2 is
     * *error* and 4 is *info* — and reading one scale as the other is a mistake this project has
     * already paid for once: every routine line arrived painted as a warning, and the handful that
     * mattered had nothing to stand out against.
     *
     * Anything past the known range is treated as trace rather than as an error: a level this side
     * does not recognise is a newer, more verbose one, not a more urgent one.
     */
    fun levelOf(coreLevel: Long): LogEntry.Level = when (coreLevel.toInt()) {
        1 -> LogEntry.Level.Error
        2 -> LogEntry.Level.Warn
        3 -> LogEntry.Level.Info
        4 -> LogEntry.Level.Debug
        else -> LogEntry.Level.Trace
    }

    /** Why the counters read what they do; see the binding for what it can say. */
    val statsReport: String get() = runCatching { Yumi.statsReport() }.getOrElse { "unavailable" }

    /**
     * Bytes moved through the proxy since this core came up, summed across every node the balancer
     * can choose between — so a server switch does not walk the session counter backwards.
     *
     * Pull, not push. sing-box streamed a status message once a second and the app only had to
     * listen; Xray keeps counters and answers when asked, so somebody upstairs has to do the asking.
     */
    val uploadBytes: Long get() = runCatching { Yumi.uplink() }.getOrDefault(0L)
    val downloadBytes: Long get() = runCatching { Yumi.downlink() }.getOrDefault(0L)

    /**
     * Points the balancer at another server, which moves the tunnel without rebuilding it.
     *
     * The replacement for libbox's `selectOutbound`, and it keeps that method's most useful
     * property: an unknown tag is refused rather than accepted. The core itself would take any
     * string and route every connection into an outbound nobody can dial, so the binding checks the
     * tag against the running document first.
     *
     * @return false when the tag is not in the running configuration, or the core is not running —
     *   both ordinary, and both leaving the caller to rebuild the tunnel instead.
     */
    fun selectOutbound(tag: String): Boolean = runCatching {
        Yumi.selectOutbound(tag)
        true
    }.getOrElse {
        Log.i(TAG, "selectOutbound refused: ${it.message}")
        false
    }

    /**
     * Times a small request through each of [tags], in parallel, and answers in milliseconds.
     *
     * The replacement for libbox's `urlTest`. A tag that did not answer is absent from the result
     * rather than present with a zero, because "asked and got nothing" and "answered instantly" are
     * the two readings that must never be confused: the second would make a dead server the fastest
     * candidate in the list.
     *
     * The probe endpoint is the one the health check already uses — plain HTTP, 204, no body, run
     * by an operator whose business is answering it quickly — and it is dialled *through* each
     * server, so it sees the failure this app exists to escape: a port that accepts connections
     * while nothing crosses them.
     *
     * @param probeUrl what to fetch. The tunnel probe unless somebody is asking a narrower question
     *   — see [ProbeTargets.IPV6_URL].
     */
    fun measureOutbounds(
        tags: Collection<String>,
        timeoutMillis: Int,
        probeUrl: String = ProbeTargets.url(ProbeTargets.TUNNEL),
    ): Map<String, Int> {
        if (tags.isEmpty()) return emptyMap()
        val answer = runCatching {
            Yumi.measureOutbounds(
                tags.joinToString(" "),
                probeUrl,
                timeoutMillis.toLong(),
            )
        }.getOrElse {
            Log.i(TAG, "measure refused: ${it.message}")
            return emptyMap()
        }
        return runCatching {
            Json.parseToJsonElement(answer).jsonObject.mapNotNull { (tag, value) ->
                value.jsonPrimitive.content.toIntOrNull()?.let { tag to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * Probes [nodes] through an isolated ephemeral Xray host without disturbing any running VPN
     * tunnel, streaming `(nodeId, millisOrNull)` back as each node finishes.
     */
    suspend fun probeNodes(
        nodes: List<ProxyNode>,
        settings: AppSettings,
        timeoutMillis: Int,
        concurrency: Int,
        onResult: (nodeId: String, millis: Int?) -> Unit,
    ) {
        if (nodes.isEmpty()) return
        val supported = ArrayList<ProxyNode>(nodes.size)
        for (node in nodes) {
            if (XrayConfigFactory.unsupported(node) == null) {
                supported.add(node)
            } else {
                onResult(node.id, null)
            }
        }
        if (supported.isEmpty()) return
        if (version == null) {
            supported.forEach { onResult(it.id, null) }
            return
        }

        val configJson = XrayConfigFactory.buildProbeConfig(supported, settings)
        val tagToNodeId = supported.associate { XrayConfigFactory.nodeTag(it.id) to it.id }
        val tags = tagToNodeId.keys.joinToString(" ")
        val probeUrl = settings.probeUrl.takeIf(ProbeTargets::isValidProbeUrl) ?: ProbeTargets.DEFAULT_URL

        withContext(Dispatchers.IO) {
            val reported = ConcurrentHashMap.newKeySet<String>(supported.size)
            val session = runCatching {
                Yumi.startProbe(
                    configJson,
                    tags,
                    probeUrl,
                    timeoutMillis.toLong(),
                    concurrency.toLong(),
                    ProbeCallback { tag, delay ->
                        val nodeId = tagToNodeId[tag] ?: return@ProbeCallback
                        if (reported.add(nodeId)) {
                            onResult(nodeId, delay.toInt().takeIf { it > 0 })
                        }
                    },
                )
            }.getOrElse { error ->
                Log.w(TAG, "startProbe failed: ${error.message}")
                supported.forEach { node ->
                    if (reported.add(node.id)) onResult(node.id, null)
                }
                return@withContext
            }

            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    runCatching { session.cancel() }
                }
            }
            try {
                session.await()
            } finally {
                cancelHandle?.dispose()
            }

            if (isActive) {
                supported.forEach { node ->
                    if (reported.add(node.id)) onResult(node.id, null)
                }
            }
        }
    }

    /** The tag the balancer is pinned to, or empty when nothing is running. */
    val activeOutbound: String
        get() = runCatching { Yumi.activeOutbound() }.getOrDefault("")

    override val isRunning: Boolean
        get() = running

    override fun probeHTTP(targetURL: String, timeoutMillis: Int, resolveDNS: Boolean): Int =
        Yumi.probeHTTP(targetURL, timeoutMillis.toLong(), resolveDNS).toInt()

    override fun openHTTP(
        method: String,
        targetURL: String,
        headersJSON: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        resolveDNS: Boolean,
    ): TunnelHttpResponse {
        val resp = Yumi.openHTTP(
            method,
            targetURL,
            headersJSON,
            connectTimeoutMillis.toLong(),
            readTimeoutMillis.toLong(),
            resolveDNS,
        )
        return JniTunnelHttpResponse(resp)
    }

    override fun dialTunnelTLS(
        host: String,
        port: Int,
        serverName: String,
        timeoutMillis: Int,
    ): TunnelStream {
        val conn = Yumi.dialTunnelTLS(host, port.toLong(), serverName, timeoutMillis.toLong())
        return JniTunnelStream(conn)
    }

    private class JniTunnelHttpResponse(private val delegate: HTTPResponse) : TunnelHttpResponse {
        override val statusCode: Int get() = delegate.statusCode.toInt()
        override val contentLength: Long get() = delegate.contentLength
        override val contentEncoding: String get() = delegate.contentEncoding.orEmpty()
        override val location: String get() = delegate.location.orEmpty()
        override val headersJSON: String get() = delegate.headersJSON.orEmpty()
        override fun header(name: String): String = delegate.header(name).orEmpty()
        override fun read(maxBytes: Int): ByteArray? = delegate.read(maxBytes.toLong())
        override fun readAll(maxBytes: Long): ByteArray = delegate.readAll(maxBytes)
        override fun close() = delegate.close()
    }

    private class JniTunnelStream(private val delegate: TunnelConn) : TunnelStream {
        override fun setReadTimeout(timeoutMillis: Int) = delegate.setReadTimeout(timeoutMillis.toLong())
        override fun setWriteTimeout(timeoutMillis: Int) = delegate.setWriteTimeout(timeoutMillis.toLong())
        override fun read(maxBytes: Int): ByteArray? = delegate.read(maxBytes.toLong())
        override fun write(data: ByteArray): Int = delegate.write(data).toInt()
        override fun close() = delegate.close()
    }

    /**
     * @param tunFd the descriptor from `VpnService.Builder.establish()`. Unlike sing-box, Xray does
     *   not dup it — `AndroidTun` takes the number as given, its `Close()` is empty, and gVisor's
     *   fd-based endpoint closes nothing either. So the caller keeps the `ParcelFileDescriptor` open
     *   for as long as the core runs and is the one that closes it, and the same descriptor can be
     *   handed to a second [start] after a [stop] without rebuilding the tunnel — which is what
     *   makes a network handover cost a core restart rather than a visible reconnect.
     * @param pinnedTag the outbound the balancer must point at before the core dispatches anything.
     *   Set inside the binding rather than here: an unpinned balancer picks for itself, so pinning
     *   it a moment after starting would route whatever the core managed to dispatch in between
     *   through a server nobody chose.
     * @param protect excludes a socket from the tunnel; returning false fails that dial rather than
     *   letting it loop back into the tunnel it came from and hang with no error anywhere. Called
     *   from Go, on whatever thread opened the socket.
     * @param allowConnection always-on fail-closed split-tunneling UID guard evaluated per TCP SYN
     *   and UDP 5-tuple entering `tun-in`.
     */
    fun start(
        config: String,
        tunFd: Int,
        pinnedTag: String,
        protect: (Int) -> Boolean,
        allowConnection: (proto: Int, srcIP: ByteArray, srcPort: Int, dstIP: ByteArray, dstPort: Int) -> Boolean,
    ) {
        val guard = TrafficGuard { proto, srcIP, srcPort, dstIP, dstPort ->
            runCatching {
                if (srcIP == null || dstIP == null) return@runCatching false
                if (proto !in 0L..255L || srcPort !in 1L..65535L || dstPort !in 1L..65535L) {
                    return@runCatching false
                }
                allowConnection(proto.toInt(), srcIP, srcPort.toInt(), dstIP, dstPort.toInt())
            }.getOrDefault(false)
        }
        Yumi.startWithGuard(
            config,
            tunFd.toLong(),
            pinnedTag,
            Protector { fd -> runCatching { protect(fd.toInt()) }.getOrDefault(false) },
            guard,
        )
    }

    /** Safe when nothing is running, because Android will call it that way. */
    fun stop() {
        Yumi.stop()
    }
}

/**
 * Adapts [XrayCore.TunnelHttpResponse.read] into a standard blocking [InputStream] for streaming
 * gzip decompression and chunked file writes without buffering entire responses in memory.
 */
internal class TunnelResponseInputStream(
    private val response: TunnelHttpResponse,
    private val chunkSize: Int = 65536,
) : InputStream() {
    private var current: ByteArray? = null
    private var offset = 0
    private var eof = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (eof) return -1
        var chunk = current
        while (chunk == null || offset >= chunk.size) {
            val next = response.read(chunkSize)
            if (next == null) {
                eof = true
                current = null
                return -1
            }
            if (next.isEmpty()) continue
            chunk = next
            current = next
            offset = 0
        }
        val available = chunk.size - offset
        val copy = minOf(len, available)
        System.arraycopy(chunk, offset, b, off, copy)
        offset += copy
        if (offset >= chunk.size) {
            current = null
            offset = 0
        }
        return copy
    }

    override fun close() {
        runCatching { response.close() }
    }
}
