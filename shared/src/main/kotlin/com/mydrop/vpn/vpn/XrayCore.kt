package com.mydrop.vpn.vpn

import android.util.Log
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.ProbeTargets
import com.mydrop.vpn.xray.yumi.Logger
import com.mydrop.vpn.xray.yumi.Protector
import com.mydrop.vpn.xray.yumi.Yumi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
object XrayCore {

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
     */
    fun measureOutbounds(tags: Collection<String>, timeoutMillis: Int): Map<String, Int> {
        if (tags.isEmpty()) return emptyMap()
        val answer = runCatching {
            Yumi.measureOutbounds(
                tags.joinToString(" "),
                ProbeTargets.url(ProbeTargets.TUNNEL),
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

    /** The tag the balancer is pinned to, or empty when nothing is running. */
    val activeOutbound: String
        get() = runCatching { Yumi.activeOutbound() }.getOrDefault("")

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
     */
    fun start(config: String, tunFd: Int, pinnedTag: String, protect: (Int) -> Boolean) {
        Yumi.start(config, tunFd.toLong(), pinnedTag, Protector { fd -> protect(fd.toInt()) })
    }

    /** Safe when nothing is running, because Android will call it that way. */
    fun stop() {
        Yumi.stop()
    }
}
