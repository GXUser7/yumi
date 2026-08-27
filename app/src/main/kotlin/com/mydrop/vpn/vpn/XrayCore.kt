package com.mydrop.vpn.vpn

import android.util.Log
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.xray.yumi.Logger
import com.mydrop.vpn.xray.yumi.Protector
import com.mydrop.vpn.xray.yumi.Yumi

/**
 * The Kotlin face of the Xray core.
 *
 * Everything gomobile generates is static, and it speaks `long` where the rest of the app says
 * `Int` — a Go `int` is 64-bit and crosses the boundary as a Java `long`, so file descriptors and
 * log levels arrive here as one. Wrapping it once keeps that detail from spreading and gives the
 * failure modes somewhere to live.
 *
 * The core is a process-wide singleton whether anyone likes it or not: it registers itself in the
 * package-level tables of the Go runtime inside this process, and there is exactly one of those.
 * So this is an object rather than something injectable, and [running] is the truth about the
 * process rather than about any particular service instance.
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
     * those are resolved while the document is being parsed, and one that cannot be resolved
     * rejects the whole configuration rather than skipping the rule that needed it.
     */
    fun setAssetPath(dir: String) {
        runCatching { Yumi.setAssetPath(dir) }
            .onFailure { Log.e(TAG, "asset path rejected", it) }
    }

    /**
     * Sends the core's own output somewhere, or nowhere when [sink] is null.
     *
     * Xray writes through a single process-wide handler rather than to a stream anything can
     * subscribe to, so this is the only way to see what it has to say.
     */
    fun setLogger(sink: ((LogEntry.Level, String) -> Unit)?) {
        runCatching {
            Yumi.setLogger(
                sink?.let { forward -> Logger { level, message -> forward(levelOf(level), message) } },
            )
        }.onFailure { Log.e(TAG, "log bridge rejected", it) }
    }

    /**
     * The core's numbering, which is neither the app's nor syslog's: 0 unknown, 1 error,
     * 2 warning, 3 info, 4 debug (`common/log/log.pb.go:26-32`).
     *
     * Reading these as anything else is a mistake this project has already made once with the other
     * core, where every routine line arrived painted as a warning and the handful that mattered had
     * nothing to stand out against.
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

    /** Bytes sent and received on the proxy outbound since this core came up. */
    val uploadBytes: Long get() = runCatching { Yumi.uplink() }.getOrDefault(0L)
    val downloadBytes: Long get() = runCatching { Yumi.downlink() }.getOrDefault(0L)

    /**
     * @param tunFd the descriptor from `VpnService.Builder.establish()`. Unlike sing-box, Xray does
     *   not dup it — `AndroidTun` takes the number as given, its `Close()` is empty, and gVisor's
     *   fd-based endpoint closes nothing either. So the caller keeps the `ParcelFileDescriptor`
     *   open for as long as the core runs and is the one that closes it, and the same descriptor
     *   can be handed to a second [start] after a [stop] without rebuilding the tunnel.
     * @param protect excludes a socket from the tunnel; returning false fails that dial rather than
     *   letting it loop back into the tunnel it came from and hang with no error anywhere.
     */
    fun start(config: String, tunFd: Int, protect: (Int) -> Boolean) {
        Yumi.start(config, tunFd.toLong(), Protector { fd -> protect(fd.toInt()) })
    }

    fun stop() {
        Yumi.stop()
    }
}
