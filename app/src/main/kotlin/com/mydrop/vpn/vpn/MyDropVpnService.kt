package com.mydrop.vpn.vpn

import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mydrop.vpn.MainActivity
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.net.interfaceCidr
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the platform tunnel and the sing-box core.
 *
 * The class wears three hats on purpose: it is the [VpnService] that owns the TUN descriptor,
 * the libbox [PlatformInterface] the core calls back into, and the [CommandServerHandler] the
 * core uses to ask the app to stop or reload. Splitting them would mean passing the file
 * descriptor and service lifecycle across object boundaries for no benefit.
 */
class MyDropVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "com.mydrop.vpn.action.START"
        const val ACTION_STOP = "com.mydrop.vpn.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_NODE_NAME = "node_name"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mydrop_tunnel"
        private const val NATIVE_TAG = "YumiCore"

        /**
         * How long a lost default interface is given to be replaced before the core is told it
         * has none. Long enough to cover a Wi-Fi → cellular handover, short enough that a real
         * loss is not hidden from the core for a noticeable stretch.
         */
        private const val INTERFACE_LOSS_GRACE_MILLIS = 600L

        /** fd 2 is process-wide, so the redirect is installed exactly once. */
        private val stderrCaptured = AtomicBoolean(false)

        /** Nanoseconds — libbox durations come straight from Go. */
        private const val STATUS_INTERVAL_NANOS = 1_000_000_000L

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats.Zero)
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        fun start(context: Context, config: String, nodeId: String, nodeName: String) {
            val intent = Intent(context, MyDropVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_NODE_NAME, nodeName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MyDropVpnService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises everything that brings the core up or down. Two taps in the server list used to
     * race two startTunnel coroutines through the same fields; whichever finished last decided
     * which server the UI claimed to be on.
     */
    private val tunnelLock = Mutex()

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var nodeId: String = ""
    private var nodeName: String = ""

    /**
     * Traffic that earlier cores in this session moved.
     *
     * Reloading the core resets its counters to zero, so without banking the previous total every
     * server switch would walk the session figure backwards. Written under [tunnelLock], read from
     * the status thread.
     */
    @Volatile private var carriedUploadBytes = 0L

    @Volatile private var carriedDownloadBytes = 0L

    /** Last totals reported by the running core, i.e. what to bank when it is replaced. */
    @Volatile private var lastUploadBytes = 0L

    @Volatile private var lastDownloadBytes = 0L

    /** When the tunnel came up, kept across server switches so the session timer is a session. */
    private var sessionStartedAtMillis = 0L

    /**
     * Bumped every time the status subscription is replaced. A handler carrying an older number
     * writes nothing: a stream that outlives its replacement reports another session's totals into
     * the same [_traffic], and a StateFlow keeps whatever was written last.
     */
    private val statusGeneration = AtomicInteger(0)

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The listener the current monitor reports to, so a late close cannot unregister a new one. */
    private var interfaceListener: InterfaceUpdateListener? = null

    /** The physical network last reported to the core, so onLost can tell it apart. */
    private var reportedInterface: Network? = null

    /**
     * Shared by the monitor's registration, its seed and its loss debounce, so all three run on
     * one thread and `removeCallbacks` can actually find what `postDelayed` queued.
     */
    private val interfaceHandler = Handler(Looper.getMainLooper())

    /** A loss waiting to be confirmed; see the callback's `onLost` for why it waits. */
    private var pendingInterfaceLoss: Runnable? = null

    /**
     * Name of the interface last reported to the core, kept to tell a handover from a first
     * report. Cleared with the monitor, so a fresh tunnel does not inherit the previous one's idea
     * of where it was.
     */
    private var lastReportedInterfaceName: String? = null

    private val logs by lazy { (application as MyDropApplication).container.logs }

    /** Notification copy and failure messages both surface to the user; both follow the setting. */
    private val strings by lazy { (application as MyDropApplication).container.strings }

    // ------------------------------------------------------------ Lifecycle

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnelWhenIdle()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrEmpty()) {
                    _state.value = VpnState.Failed(null, strings.get(R.string.error_empty_config))
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Also handed to startTunnel: the fields are what the notification and openTun
                // read, but the coroutine must decide state from the request it was given rather
                // than from whatever a later request has since overwritten them with.
                nodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
                nodeName = intent.getStringExtra(EXTRA_NODE_NAME).orEmpty()
                startForegroundNotification()
                startTunnel(config, nodeId, nodeName)
            }

            // Anything else is Android starting the service on its own initiative: Always-on VPN
            // turning the tunnel on at boot or on network change, and the restart START_STICKY
            // asks for after the process is killed. Neither carries our extras, so there is no
            // configuration to read — it has to be rebuilt from the stored profile.
            else -> startFromStoredProfile()
        }
        return START_STICKY
    }

    /**
     * Brings the tunnel up without an intent to take instructions from.
     *
     * The foreground notification goes up first and unconditionally: the system started this
     * service and expects `startForeground` within seconds, and it will kill the process for
     * missing that deadline long before a rule-set has been extracted or a server probed.
     */
    private fun startFromStoredProfile() {
        if (_state.value.isActive) return
        startForegroundNotification()

        val container = (application as MyDropApplication).container
        scope.launch {
            val node = container.tunnelLauncher.resolveNode()
            if (node == null) {
                logs.warn(R.string.log_system_start_no_server)
                stopSelf()
                return@launch
            }
            // Consent cannot be asked for from here — there is no Activity and, for Always-on,
            // no user present. The system does grant it implicitly when the user turns Always-on
            // VPN on, so this only fires when consent was revoked afterwards.
            if (VpnService.prepare(this@MyDropVpnService) != null) {
                logs.warn(R.string.log_system_start_no_permission)
                stopSelf()
                return@launch
            }

            val config = container.tunnelConfigs.build(node)
            if (config == null) {
                stopSelf()
                return@launch
            }

            nodeId = node.id
            nodeName = node.name
            updateNotification()
            logs.info(R.string.log_system_start, node.name)
            startTunnel(config, node.id, node.name)
        }
    }

    override fun onRevoke() {
        logs.warn(R.string.log_permission_revoked)
        stopTunnelWhenIdle()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Routes the Go runtime's stderr into logcat and the in-app journal.
     *
     * When the core hits a fatal error it prints the panic and goroutine dump to file descriptor
     * 2 and then raises SIGABRT. Android discards native stderr, so all that survives is a
     * tombstone whose only frame is `runtime.raise` — the place the process was killed, never the
     * place it broke. Without this, every core-level abort looks identical and says nothing.
     *
     * Done once per process, before [Libbox.setup], because the runtime caches nothing about the
     * descriptor and later writes follow whatever fd 2 points at.
     */
    private fun captureNativeStderr() {
        if (!stderrCaptured.compareAndSet(false, true)) return
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            Os.dup2(pipe[1].fileDescriptor, 2)
            pipe[1].close()

            scope.launch {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader()
                    .forEachLine { line ->
                        android.util.Log.e(NATIVE_TAG, line)
                        runCatching { logs.error(R.string.log_core_line, line) }
                    }
            }
        }.onFailure {
            android.util.Log.w(NATIVE_TAG, "stderr capture unavailable: ${it.message}")
        }
    }

    /**
     * Raises the tunnel, or moves a running one onto a new configuration.
     *
     * Both are the same call. `startOrReloadService` is the core's own swap: it closes the running
     * service and starts the new one behind the same command server, so the status and log
     * subscriptions stay put and there is only ever one core.
     *
     * The previous version built a **second** command server for every server switch instead.
     * sing-box unlinks `command.sock` before listening, so that start succeeded — and nothing ever
     * stopped the first core. It kept its connections, its network callback and its status stream,
     * and that stream kept writing another session's totals into the same [_traffic], which is what
     * made the session counters flicker between two sets of numbers.
     */
    private fun startTunnel(config: String, requestedNodeId: String, requestedNodeName: String) {
        scope.launch {
            tunnelLock.withLock {
                val reloading = commandServer != null
                try {
                    nodeId = requestedNodeId
                    nodeName = requestedNodeName
                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Starting)

                    captureNativeStderr()

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Handshaking)

                    val server = commandServer ?: createCommandServer()

                    // The core's counters start over with the new service, so bank what the old one
                    // moved before it goes away.
                    if (reloading) {
                        carriedUploadBytes += lastUploadBytes
                        carriedDownloadBytes += lastDownloadBytes
                    }
                    lastUploadBytes = 0
                    lastDownloadBytes = 0

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.EstablishingTunnel)

                    // Asked before it is handed over. Anything crossing this bridge is parsed by Go
                    // with Must* helpers in places, so a document the core dislikes is a process
                    // abort with a tombstone rather than an error — and now that servers can carry
                    // outbounds written by their provider rather than by us, the app no longer
                    // controls every field in what it submits. checkConfig turns that class of
                    // crash into a sentence on screen.
                    runCatching { Libbox.checkConfig(config) }.onFailure { rejected ->
                        throw IllegalStateException(
                            strings.get(
                                R.string.error_core_rejected_config,
                                rejected.message ?: strings.get(R.string.error_core_no_reason),
                            ),
                        )
                    }

                    // Blocks until the core is up; openTun() is called back from inside it.
                    server.startOrReloadService(config, OverrideOptions())

                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Testing)
                    subscribeToStatus()

                    // A switch continues the session rather than starting one: the user did not
                    // reconnect, and restarting the clock alongside the byte counters would say
                    // they did.
                    if (!reloading || sessionStartedAtMillis == 0L) {
                        sessionStartedAtMillis = System.currentTimeMillis()
                    }
                    _state.value = VpnState.Connected(nodeId, sessionStartedAtMillis)
                    logs.info(
                        if (reloading) R.string.log_tunnel_switched else R.string.log_tunnel_up,
                        nodeName,
                    )
                    updateNotification()
                } catch (error: Throwable) {
                    val message = error.message
                        ?: error::class.simpleName
                        ?: strings.get(R.string.error_core_start_failed)
                    logs.error(
                        if (reloading) R.string.log_switch_failed else R.string.log_start_failed,
                        message,
                    )
                    // Also to logcat: the in-app log is an in-memory ring buffer, so a failure that
                    // kills the service leaves no trace anywhere reachable from a development
                    // machine. Diagnosing the empty-DNS crash meant pulling the config off the
                    // device.
                    android.util.Log.e("MyDropVpn", "core failed to start: $message", error)
                    _state.value = VpnState.Failed(nodeId, message)
                    stopTunnel()
                }
            }
        }
    }

    /** Created once per process and kept until the tunnel is stopped for real. */
    private fun createCommandServer(): CommandServer {
        val workingDir = File(filesDir, "singbox").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = cacheDir.absolutePath
            },
        )
        val server = Libbox.newCommandServer(this@MyDropVpnService, this@MyDropVpnService)
        server.start()
        commandServer = server
        return server
    }

    /**
     * Queues the teardown behind whatever the tunnel lock is doing.
     *
     * Tapping the control while it is connecting — or the core asking to be stopped from inside one
     * of its own callbacks — used to close the command server while [startTunnel] was still blocked
     * inside `startOrReloadService` on it. The state is moved eagerly so the button answers the tap
     * straight away; the actual close waits its turn.
     */
    private fun stopTunnelWhenIdle() {
        if (_state.value.isActive) _state.value = VpnState.Disconnecting
        scope.launch { tunnelLock.withLock { stopTunnel() } }
    }

    private fun stopTunnel() {
        if (_state.value is VpnState.Disconnected) return

        // A failure has to survive the teardown it triggers. The old code set Failed, called this,
        // and had it overwrite the state with Disconnecting on the next line — so the check further
        // down never saw a failure and the connect screen never showed a reason for one.
        val failure = _state.value as? VpnState.Failed
        if (failure == null) _state.value = VpnState.Disconnecting

        // Before the client is torn down: whatever the core sends on its way out belongs to a
        // session that is over.
        statusGeneration.incrementAndGet()
        runCatching { commandClient?.disconnect() }
        commandClient = null

        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null

        stopDefaultInterfaceMonitor()

        runCatching { tunDescriptor?.close() }
        tunDescriptor = null

        carriedUploadBytes = 0
        carriedDownloadBytes = 0
        lastUploadBytes = 0
        lastDownloadBytes = 0
        sessionStartedAtMillis = 0
        _traffic.value = TrafficStats.Zero
        _state.value = failure ?: VpnState.Disconnected

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------- Status

    /**
     * Replaces the status and log subscription, disconnecting whatever held it before.
     *
     * Both halves matter. Dropping the old client without disconnecting it leaves its stream
     * running against a core that may no longer exist, and the generation bump makes even that
     * stream harmless: a handler whose number is stale writes nothing.
     */
    private fun subscribeToStatus() {
        runCatching { commandClient?.disconnect() }
        commandClient = null

        val generation = statusGeneration.incrementAndGet()
        val client = Libbox.newCommandClient(
            StatusHandler(generation),
            CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                addCommand(Libbox.CommandLog)
                statusInterval = STATUS_INTERVAL_NANOS
            },
        )
        runCatching { client.connect() }
            .onFailure {
                logs.warn(R.string.log_status_subscribe_failed, it.message.orEmpty())
                // Without this the counters simply stay at zero and there is nothing on screen or
                // in logcat to say the subscription never happened.
                android.util.Log.w(NATIVE_TAG, "status subscription failed: ${it.message}", it)
            }
        commandClient = client
    }

    private inner class StatusHandler(private val generation: Int) :
        io.nekohasekai.libbox.CommandClientHandler {
        private var warnedUnavailable = false
        private var samples = 0

        /** True once this subscription has been replaced; see [statusGeneration]. */
        private val stale: Boolean get() = generation != statusGeneration.get()

        override fun connected() = Unit

        override fun disconnected(message: String?) {
            if (!message.isNullOrEmpty()) logs.warn(R.string.log_core_line, message)
        }

        override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {
            if (message == null || stale) return

            // The first few, then one every half minute. Counters that sit at zero look identical
            // whether the core is not accounting, the subscription is dead, or the screen is
            // reading the wrong field — and only the core can say which.
            if (samples < 3 || samples % 30 == 0) {
                android.util.Log.i(
                    NATIVE_TAG,
                    "status#$samples: available=${message.trafficAvailable} " +
                        "total=${message.uplinkTotal}/${message.downlinkTotal} " +
                        "rate=${message.uplink}/${message.downlink} " +
                        "conn=${message.connectionsOut} carried=$carriedUploadBytes/$carriedDownloadBytes",
                )
            }
            samples++

            // The core reports both cumulative totals and per-second rates. Take its rates rather
            // than differentiating the totals here: this side only assumes the sampling interval,
            // while the core actually measures it, and a late or dropped status message turned a
            // steady stream into a spike followed by a flat second.
            if (!message.trafficAvailable) {
                if (!warnedUnavailable) {
                    warnedUnavailable = true
                    logs.warn(R.string.log_no_traffic_accounting)
                    android.util.Log.w(NATIVE_TAG, "status: trafficAvailable = false")
                }
                // Totals are kept rather than zeroed. A status message without traffic data says
                // nothing about how much has gone through — the core sends one while a service is
                // being swapped — and replacing the session figure with 0 for that one second is
                // half of what the counters were blinking between.
                _traffic.value = _traffic.value.copy(
                    uploadBytesPerSecond = 0,
                    downloadBytesPerSecond = 0,
                    activeConnections = message.connectionsOut,
                )
                return
            }

            lastUploadBytes = message.uplinkTotal
            lastDownloadBytes = message.downlinkTotal

            _traffic.value = TrafficStats(
                // Plus what earlier cores in this session moved: reloading the core onto another
                // server resets its counters, and the session did not restart just because the
                // server did.
                uploadBytes = carriedUploadBytes + message.uplinkTotal,
                downloadBytes = carriedDownloadBytes + message.downlinkTotal,
                uploadBytesPerSecond = message.uplink.coerceAtLeast(0),
                downloadBytesPerSecond = message.downlink.coerceAtLeast(0),
                activeConnections = message.connectionsOut,
            )
        }

        override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) {
            if (stale) return
            while (messageList?.hasNext() == true) {
                val entry = messageList.next()
                val level = entry.level.toLogLevel()
                logs.log(level, entry.message)
                // Mirrored to logcat as well as the in-app journal. The journal is an in-memory
                // ring buffer that dies with the process, so when the core misbehaves there is
                // otherwise nothing to read from a development machine.
                when (level) {
                    LogEntry.Level.Error -> android.util.Log.e(NATIVE_TAG, entry.message)
                    LogEntry.Level.Warn -> android.util.Log.w(NATIVE_TAG, entry.message)
                    else -> android.util.Log.i(NATIVE_TAG, entry.message)
                }
            }
        }

        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(message: io.nekohasekai.libbox.ConnectionEvents?) = Unit
        override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) = Unit
        override fun writeOutbounds(message: io.nekohasekai.libbox.OutboundGroupItemIterator?) = Unit
    }

    /** sing-box log levels follow syslog severity: lower is more severe. */
    private fun Int.toLogLevel(): LogEntry.Level = when (this) {
        0, 1, 2, 3 -> LogEntry.Level.Error
        4 -> LogEntry.Level.Warn
        5, 6 -> LogEntry.Level.Info
        7 -> LogEntry.Level.Debug
        else -> LogEntry.Level.Trace
    }

    // --------------------------------------------------- CommandServerHandler

    override fun serviceStop() {
        // Reached from inside a core callback, so the teardown is queued rather than run here:
        // closing the service from within one of its own calls is how a reentrancy abort starts.
        stopTunnelWhenIdle()
    }

    override fun serviceReload() {
        logs.info(R.string.log_core_reload_requested)
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun writeDebugMessage(message: String?) {
        message?.let { logs.debug(it) }
    }

    override fun connectSSHAgent(): Int = throw UnsupportedOperationException("SSH agent")

    override fun triggerNativeCrash() = throw UnsupportedOperationException("crash trigger")

    // ------------------------------------------------------ PlatformInterface

    override fun openTun(options: TunOptions): Int {
        val builder = Builder()
        builder.setSession(nodeName.ifEmpty { "MyDrop" })
        builder.setMtu(options.mtu)

        options.inet4Address.forEachPrefix { builder.addAddress(it.address(), it.prefix()) }
        // Recorded while iterating: these iterators are one-shot, so asking `hasNext()` further
        // down returns false no matter what the core actually gave us.
        var hasInet6 = false
        options.inet6Address.forEachPrefix {
            builder.addAddress(it.address(), it.prefix())
            hasInet6 = true
        }

        // An empty route list from the core means "everything"; VpnService needs that spelled out.
        var routeCount = 0
        options.inet4RouteAddress.forEachPrefix {
            builder.addRoute(it.address(), it.prefix())
            routeCount++
        }
        options.inet6RouteAddress.forEachPrefix {
            builder.addRoute(it.address(), it.prefix())
            routeCount++
        }
        if (routeCount == 0) {
            builder.addRoute("0.0.0.0", 0)
            if (hasInet6) builder.addRoute("::", 0)
        }

        runCatching { options.dnsServerAddress }.getOrNull()?.forEachString {
            builder.addDnsServer(it)
        }

        options.includePackage.forEachString {
            runCatching { builder.addAllowedApplication(it) }
        }
        options.excludePackage.forEachString {
            runCatching { builder.addDisallowedApplication(it) }
        }

        // Without this the app's own subscription refreshes would be routed into the tunnel that
        // is still coming up.
        runCatching { builder.addDisallowedApplication(packageName) }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(options.httpProxyServer, options.httpProxyServerPort),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val descriptor = builder.establish()
            ?: throw IllegalStateException(strings.get(R.string.error_establish_null))
        // The core dups whatever descriptor it is handed ("dup tun file descriptor"), so this side
        // keeps ownership of the original and has to close it. On a reload openTun runs again while
        // the previous one is still open — without this, every server switch leaked a descriptor
        // and left the old interface half-alive.
        runCatching { tunDescriptor?.close() }
        tunDescriptor = descriptor
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // Sockets the core opens itself must bypass the tunnel or they would loop back into it.
        if (!protect(fd)) throw IllegalStateException(strings.get(R.string.error_protect_failed, fd))
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        // API 29. The core calls this because useProcFS() says no, and on Android 8 and 9 the
        // method simply does not exist — the call would be a NoSuchMethodError raised inside a JNI
        // callback from Go, which takes the process down rather than surfacing as an exception.
        // This method is declared to throw, so refusing is the supported answer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException(strings.get(R.string.error_no_connection_owner))
        }

        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(parseAddress(sourceAddress), sourcePort),
            InetSocketAddress(parseAddress(destinationAddress), destinationPort),
        )
        if (uid == -1) throw IllegalStateException(strings.get(R.string.error_no_connection_owner))

        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(packages.toList().toStringIterator())
        }
    }

    /**
     * Enumerates the host's interfaces for the core.
     *
     * Read straight from `java.net.NetworkInterface` and nothing else. An earlier version merged
     * in `ConnectivityManager` metadata (transport type, metered, per-network DNS) to help the
     * core pick a default network; that is more information, but it also made this method depend
     * on three Android APIs agreeing about interface names, and none of it is required by the
     * core's own contract. When the tunnel is aborting at startup, the correct trade is fewer
     * moving parts.
     */
    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .mapNotNull { nif ->
                runCatching {
                    io.nekohasekai.libbox.NetworkInterface().apply {
                        name = nif.name
                        index = nif.index
                        mtu = nif.mtu
                        addresses = nif.interfaceAddresses
                            .mapNotNull {
                                // Zone suffixes must be stripped here — see [interfaceCidr];
                                // handing one to the core panics Go and kills the process.
                                interfaceCidr(it.address.hostAddress, it.networkPrefixLength.toInt())
                            }
                            .toStringIterator()
                        flags = nif.linuxFlags()
                    }
                }.getOrNull()
            }
        // The core reports "no available network interface" without saying what it was offered,
        // so what we hand it gets logged. This is the only view of that hand-off.
        android.util.Log.i(
            NATIVE_TAG,
            "getInterfaces -> " + interfaces.joinToString { "${it.name}#${it.index} flags=${it.flags}" },
        )
        return interfaces.toInterfaceIterator()
    }

    /**
     * Watches the system's default network and reports it to the core.
     *
     * Two deliberate choices.
     *
     * A callback that tracks the single system default — `registerBestMatchingNetworkCallback` on
     * Android 12+, `registerDefaultNetworkCallback` below it — rather than
     * `registerNetworkCallback(request)`, which reports every matching network at once and let the
     * core dial through whichever one reported last. Both are pure observation, so neither needs
     * `CHANGE_NETWORK_STATE`; `requestNetwork`, which would, is avoided. See the registration for
     * the failure this cost.
     *
     * And the current network is reported through [Handler.post] instead of inline. The core calls
     * this method and waits; calling `listener.updateDefaultInterface` before returning re-enters
     * Go while it is still inside that call, before the listener it just handed us is necessarily
     * installed. `updateDefaultInterface` is not declared to throw, so anything that goes wrong on
     * the Go side of that re-entry surfaces as a panic — a native SIGABRT with no Java stack,
     * which is exactly the symptom being chased. Posting keeps the seed but lets the core finish
     * first.
     */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Registering without unregistering first leaked a callback on every server switch, because
        // reloading the core starts a fresh monitor. Android caps a process at 100 registered
        // callbacks and then throws, so the leak had a crash at the end of it.
        stopDefaultInterfaceMonitor()
        interfaceListener = listener

        fun notify(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
            // The one thing this monitor must never report is our own tunnel. Once the TUN is up
            // it becomes the system's default network, so the obvious "what is the default?"
            // answer is `tun0` — and telling the core to dial out through its own inbound is what
            // produced "dial UDP connection: no available network interface" on every request.
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            val name = connectivityManager.getLinkProperties(network)?.interfaceName ?: return
            val index = runCatching {
                JavaNetworkInterface.getByName(name)?.index ?: -1
            }.getOrDefault(-1)
            if (index < 0) return

            // A usable network arrived, so whatever loss was waiting to be confirmed did not
            // happen — this is the ordinary end of a handover.
            cancelPendingInterfaceLoss()

            val expensive =
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            // Kept before the report, because the reset below needs to know whether this is a
            // handover or the first interface this tunnel ever had.
            val previousName = lastReportedInterfaceName
            reportedInterface = network
            lastReportedInterfaceName = name
            android.util.Log.i(NATIVE_TAG, "defaultInterface -> $name#$index expensive=$expensive")
            setUnderlying(network)
            runCatching { listener.updateDefaultInterface(name, index, expensive, false) }

            // Telling the core about the new interface does not rescue the connections that were
            // pinned to the old one — those are already dead, and nothing else reaps them. The
            // watchdog would only notice forty-odd seconds later and then draw the wrong
            // conclusion, switching servers over a server that was never at fault.
            //
            // Only on an actual handover: the first interface a tunnel gets is not a change, and
            // resetting there would throw away the connections the core has just opened.
            if (previousName != null && previousName != name) {
                android.util.Log.i(NATIVE_TAG, "handover $previousName -> $name, resetting network")
                resetCoreNetwork()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(network)
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = notify(network)

            override fun onLost(network: Network) {
                // Only the interface we actually reported: any other network going away leaves
                // the core's underlying route perfectly usable.
                if (network != reportedInterface) return

                // Not blanked here, and that is the whole point of this change.
                //
                // On a clean Wi-Fi/cellular handover `onAvailable` for the replacement arrives
                // first and `notify` cancels this before it ever runs. On an unclean one — Wi-Fi
                // simply vanishing, which is the common case walking out of range — the
                // replacement is a few hundred milliseconds behind, and reporting ("", -1) into
                // that gap is what writes `network: missing default interface` to the journal and
                // takes every in-flight connection down with `software caused connection abort`
                // in the same second. The wait costs nothing when the loss is real: the interface
                // is gone either way, and half a second later the core is told so.
                cancelPendingInterfaceLoss()
                val confirm = Runnable {
                    pendingInterfaceLoss = null
                    // A reload may have installed a newer monitor while this was queued.
                    if (interfaceListener != listener) return@Runnable

                    // A replacement that came up without a callback of its own still counts.
                    val current = connectivityManager.activeNetwork
                    val currentCaps = current?.let(connectivityManager::getNetworkCapabilities)
                    if (current != null && currentCaps != null &&
                        !currentCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        notify(current)
                        return@Runnable
                    }

                    reportedInterface = null
                    android.util.Log.i(NATIVE_TAG, "defaultInterface -> (none)")
                    setUnderlying(null)
                    runCatching { listener.updateDefaultInterface("", -1, false, false) }
                }
                pendingInterfaceLoss = confirm
                interfaceHandler.postDelayed(confirm, INTERFACE_LOSS_GRACE_MILLIS)
            }
        }
        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // NOT_RESTRICTED, matching the upstream client: a restricted network is one the app is
            // not allowed to use anyway, so dialling the core out through it would only fail. The
            // implicit NOT_VPN of a NetworkRequest keeps our own tunnel out of the match.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        val handler = interfaceHandler
        // The API tier is the whole point of this method, and it was the wrong one. The code used
        // registerNetworkCallback(request), which fires for *every* network matching the request.
        // With Wi-Fi and cellular both up — cellular is routinely kept warm behind Wi-Fi — the
        // last callback to arrive won, so the core could be handed cellular as "the default" while
        // the route and the real default were Wi-Fi; and onLost for Wi-Fi then did nothing, because
        // the reported interface was cellular. Outbound dialling stalled until some later
        // capability change happened to re-report the right one — the internet "dropping" for a
        // stretch with nothing on screen to explain it. Both calls below track exactly one network,
        // the system default, and follow it across a Wi-Fi/cellular handover.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivityManager.registerBestMatchingNetworkCallback(request, callback, handler)
        } else {
            // This app excludes itself from its own tunnel (addDisallowedApplication above), so its
            // default network stays the underlying physical one rather than becoming the VPN —
            // which is what makes the plain default callback report the right interface here and
            // not tun0.
            connectivityManager.registerDefaultNetworkCallback(callback, handler)
        }

        // A belt-and-suspenders seed. All the registrations above deliver an initial callback with
        // the current match, so this is rarely what reports first; when it is, activeNetwork is the
        // real default, where the old seed took the arbitrary first entry of allNetworks.
        handler.post {
            // Not if a reload has installed a newer monitor in the meantime: the seed would then
            // be reporting to a listener the core has already discarded.
            if (interfaceListener != listener) return@post
            connectivityManager.activeNetwork?.let(::notify)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Only the monitor that is actually current. During a reload the core closes the outgoing
        // service's monitor, and it is free to do so after the incoming one has been installed;
        // unregistering then would leave the new core with no default interface at all.
        if (listener != interfaceListener) return
        stopDefaultInterfaceMonitor()
    }

    private fun stopDefaultInterfaceMonitor() {
        cancelPendingInterfaceLoss()
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        interfaceListener = null
        reportedInterface = null
        lastReportedInterfaceName = null
    }

    private fun cancelPendingInterfaceLoss() {
        pendingInterfaceLoss?.let(interfaceHandler::removeCallbacks)
        pendingInterfaceLoss = null
    }

    /**
     * Drops the connections the core is holding, so they are rebuilt on the interface that now
     * exists.
     *
     * A Wi-Fi → cellular handover leaves every socket the core opened bound to an interface that
     * is gone. They do not fail fast; they sit until their own timeouts, which is the stall the
     * user sees after the network changes even once the new interface has been reported. The core
     * exposes exactly this on its command server, and it is what the upstream client calls in the
     * same situation.
     *
     * `resetNetwork` is declared without `throws`, which on a gomobile binding means the Go side
     * will not turn an exception into an error — so nothing may escape into it, and nothing here
     * may escape out of it either.
     */
    private fun resetCoreNetwork() {
        runCatching { commandServer?.resetNetwork() }
            .onFailure { android.util.Log.w(NATIVE_TAG, "resetNetwork failed: ${it.message}") }
    }

    /**
     * Names the physical network the tunnel is riding on.
     *
     * `VpnService.Builder` decides the TUN's capabilities once, at `establish`, and nothing
     * revisits them afterwards — including the `setMetered(false)` in [openTun]. So after a
     * Wi-Fi → cellular handover the VPN network still advertises whatever was true when the
     * tunnel came up, and applications inside it read a stale validated/metered state: the core
     * is dialling fine while the phone insists there is no internet. Handing the platform the
     * current underlying network keeps that derived state honest across the handover.
     *
     * Null gives the decision back to the system default, which is the only sensible answer when
     * there is no underlying network left to name.
     */
    private fun setUnderlying(network: Network?) {
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java)
            ?.notify(coreNotificationId(notification.identifier), builder.build())
    }

    /**
     * Takes back a notification the core sent earlier.
     *
     * `typeID` groups notifications on platforms that have such a concept; Android identifies them
     * by the integer passed to `notify`, so the identifier alone decides which one this is and the
     * type is ignored. It has to derive the id exactly the way [sendNotification] did — hence
     * [coreNotificationId] rather than the hash written out twice.
     */
    override fun cancelNotification(identifier: String?, typeID: Int) {
        if (identifier.isNullOrEmpty()) return
        getSystemService(NotificationManager::class.java)?.cancel(coreNotificationId(identifier))
    }

    /**
     * The core names its notifications with a string; Android wants an int. Kept away from
     * [NOTIFICATION_ID] so a colliding hash cannot take down the foreground notification and with
     * it the tunnel.
     */
    private fun coreNotificationId(identifier: String): Int {
        val hashed = identifier.hashCode()
        return if (hashed == NOTIFICATION_ID) hashed + 1 else hashed
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun registerMyInterface(interfaceName: String?) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = throw UnsupportedOperationException("platform shell")

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("bridge")

    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("sftp")

    override fun lookupUser(name: String?): PlatformUser =
        throw UnsupportedOperationException("platform user")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        args: StringIterator?,
        term: String?,
        width: Int,
        height: Int,
    ): ShellSession = throw UnsupportedOperationException("shell session")

    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("ssh key")

    // ------------------------------------------------------- Notification

    private fun createNotificationChannel() {
        // Through the resolver rather than getString: the channel name shows up in the system
        // notification settings, and it should say what the user chose in the app rather than
        // what the phone is set to.
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.get(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.get(R.string.vpn_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): AndroidNotification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MyDropVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (nodeName.isEmpty()) "Yumi" else nodeName)
            .setContentText(
                strings.get(
                    when (_state.value) {
                        is VpnState.Connected -> R.string.notification_tunnel_active
                        is VpnState.Connecting -> R.string.notification_connecting
                        else -> R.string.notification_stopping
                    },
                ),
            )
            .setContentIntent(openApp)
            .addAction(0, strings.get(R.string.notification_disconnect), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    // ----------------------------------------------------------- Helpers

    private fun parseAddress(value: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            InetAddresses.parseNumericAddress(value)
        } else {
            @Suppress("DEPRECATION")
            java.net.InetAddress.getByName(value)
        }

    private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEachPrefix(
        action: (io.nekohasekai.libbox.RoutePrefix) -> Unit,
    ) {
        while (hasNext()) action(next())
    }

    private inline fun StringIterator.forEachString(action: (String) -> Unit) {
        while (hasNext()) action(next())
    }

    private fun List<String>.toStringIterator(): StringIterator = object : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < size
        override fun len(): Int = size
        override fun next(): String = this@toStringIterator[index++]
    }

    private fun List<io.nekohasekai.libbox.NetworkInterface>.toInterfaceIterator():
        NetworkInterfaceIterator = object : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < size
        override fun next(): io.nekohasekai.libbox.NetworkInterface =
            this@toInterfaceIterator[index++]
    }

    /**
     * Linux IFF_* flags, read from the interface itself rather than inferred from Android's
     * network capabilities. An interface that is up is up regardless of whether the system
     * currently considers it to have internet.
     */
    private fun JavaNetworkInterface.linuxFlags(): Int {
        var flags = 0
        runCatching { if (isUp) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING }
        runCatching { if (isLoopback) flags = flags or OsConstants.IFF_LOOPBACK }
        runCatching { if (isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT }
        runCatching { if (supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST }
        runCatching {
            if (interfaceAddresses.any { it.broadcast != null && it.address is Inet4Address }) {
                flags = flags or OsConstants.IFF_BROADCAST
            }
        }
        return flags
    }
}
