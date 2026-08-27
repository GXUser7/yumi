package com.mydrop.vpn.vpn

import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mydrop.vpn.MainActivity
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import java.util.concurrent.atomic.AtomicBoolean
import java.net.NetworkInterface as JavaNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tunnel, and everything Android needs to believe about it.
 *
 * Built on Xray rather than sing-box, and the division of labour is the reverse of what it was.
 * libbox inverted control: it called back into the app to open the tunnel, enumerate interfaces and
 * resolve names, which is why this class used to implement a sixty-method `PlatformInterface`. Xray
 * asks nothing. It is handed a file descriptor and a configuration and gets on with it, so every
 * decision libbox used to request — addresses, routes, per-app rules, MTU — is made here, once, in
 * [establishTunnel].
 *
 * What has not changed is everything that was learned the hard way about Android: the notification
 * deadline, the loss debounce, watching one default network rather than all of them, and keeping
 * the session counters honest across a server switch. Those comments are kept because the
 * conditions that produced them are still true.
 */
class MyDropVpnService : VpnService() {

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
         * How long a lost default interface is given to be replaced before the core is told it has
         * none. Long enough to cover a Wi-Fi → cellular handover, short enough that a real loss is
         * not hidden for a noticeable stretch.
         */
        private const val INTERFACE_LOSS_GRACE_MILLIS = 600L

        /** How often the byte counters are read out of the core for the connect screen. */
        private const val TRAFFIC_POLL_MILLIS = 1_000L

        /** fd 2 is process-wide, so the redirect is installed exactly once. */
        private val stderrCaptured = AtomicBoolean(false)

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats.Zero)
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        /**
         * Whether the phone has a default network at all — false from the moment the core is told
         * it has no interface until it is told about one again.
         *
         * The failover watchdog needs this to tell two things apart that look identical from a
         * probe: a server that stopped working, and a phone that stopped having internet. Without
         * it, walking out of Wi-Fi range reads as the current server being dead, and at a threshold
         * of one failed probe that verdict arrives within seconds of the signal going.
         */
        private val _hasNetwork = MutableStateFlow(true)
        val hasNetwork: StateFlow<Boolean> = _hasNetwork.asStateFlow()

        /**
         * Emitted with the new interface name whenever the tunnel moves between physical
         * networks — Wi-Fi to cellular and back, or the same one going away and returning.
         *
         * This exists so the failover watchdog does not have to wait out its own clock to find out
         * that the ground moved. Replay is zero and the buffer drops the oldest on overflow: a
         * handover is only interesting while it is current, and a subscriber that was not listening
         * at the time has nothing to catch up on.
         */
        private val _handovers = MutableSharedFlow<String>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val handovers: SharedFlow<String> = _handovers.asSharedFlow()

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

    private var tunDescriptor: ParcelFileDescriptor? = null
    private var nodeId: String = ""
    private var nodeName: String = ""

    /**
     * The configuration the running core was started on.
     *
     * Kept because a network handover is answered by restarting the core on the same document and
     * the same descriptor, and there is nowhere else to get it from at that point.
     */
    private var currentConfig: String? = null

    /**
     * The per-app rules the current tunnel was built with.
     *
     * A server switch reuses the descriptor, which is only correct while the tunnel itself should
     * not change. These rules are the part of it a settings change can invalidate, so they are
     * remembered and compared rather than assumed.
     */
    private var tunnelShape: TunnelShape? = null

    private var trafficPoller: Job? = null

    /**
     * Traffic that earlier cores in this session moved.
     *
     * Restarting the core resets its counters to zero, so without banking the previous total every
     * server switch would walk the session figure backwards.
     */
    @Volatile private var carriedUploadBytes = 0L

    @Volatile private var carriedDownloadBytes = 0L

    /** When the tunnel came up, kept across server switches so the session timer is a session. */
    private var sessionStartedAtMillis = 0L

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The physical network last reported, so onLost can tell it apart. */
    private var reportedInterface: Network? = null

    /**
     * Shared by the monitor's registration and its loss debounce, so both run on one thread and
     * `removeCallbacks` can actually find what `postDelayed` queued.
     */
    private val interfaceHandler = Handler(Looper.getMainLooper())

    /** A loss waiting to be confirmed; see the callback's `onLost` for why it waits. */
    private var pendingInterfaceLoss: Runnable? = null

    /**
     * Name of the interface last seen, kept to tell a handover from a first report. Cleared with
     * the monitor, so a fresh tunnel does not inherit the previous one's idea of where it was.
     */
    private var lastReportedInterfaceName: String? = null

    /** Part of what makes an interface report worth repeating; see the check in `notify`. */
    private var lastReportedExpensive: Boolean? = null

    /**
     * True from the moment the tunnel is left with no default network until one is seen again.
     *
     * Without it, a network that goes away and comes back under the same name is invisible: the
     * handover check compares names, `wlan0` equals `wlan0`, and nothing is restarted. But every
     * connection pinned to that interface died while it was gone, and Wi-Fi dropping and returning
     * is the commonest form of this there is.
     */
    private var interfaceWasLost = false

    private val logs by lazy { (application as MyDropApplication).container.logs }

    /** Notification copy and failure messages both surface to the user; both follow the setting. */
    private val strings by lazy { (application as MyDropApplication).container.strings }

    private val settings by lazy { (application as MyDropApplication).container.settings }

    // ------------------------------------------------------------ Lifecycle

    override fun onCreate() {
        super.onCreate()
        // Instance identity in the trace. A tunnel that "restarts itself" looks identical in the
        // journal whether the service restarted the core or Android destroyed and recreated the
        // whole service — and those have completely different causes.
        logs.trace(NATIVE_TAG, "service onCreate #${hashCode()}")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logs.trace(
            NATIVE_TAG,
            "onStartCommand #${hashCode()} action=${intent?.action ?: "(none)"} " +
                "flags=$flags startId=$startId",
        )
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnelWhenIdle("ACTION_STOP intent")
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrEmpty()) {
                    _state.value = VpnState.Failed(null, strings.get(R.string.error_empty_config))
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Also handed to startTunnel: the fields are what the notification reads, but the
                // coroutine must decide state from the request it was given rather than from
                // whatever a later request has since overwritten them with.
                nodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
                nodeName = intent.getStringExtra(EXTRA_NODE_NAME).orEmpty()
                startForegroundNotification()
                startTunnel(config, nodeId, nodeName)
            }

            // Anything else is Android starting the service on its own initiative: Always-on VPN
            // turning the tunnel on at boot or on network change, and the restart START_STICKY asks
            // for after the process is killed. Neither carries our extras, so there is no
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
     * missing that deadline long before a server has been probed.
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
            // Consent cannot be asked for from here — there is no Activity and, for Always-on, no
            // user present. The system does grant it implicitly when the user turns Always-on VPN
            // on, so this only fires when consent was revoked afterwards.
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
        stopTunnelWhenIdle("onRevoke: system withdrew VPN consent")
    }

    override fun onDestroy() {
        logs.trace(NATIVE_TAG, "service onDestroy #${hashCode()}")
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Routes the Go runtime's stderr into logcat and the in-app journal.
     *
     * When the core hits a fatal error it prints the panic and goroutine dump to file descriptor 2
     * and then raises SIGABRT. Android discards native stderr, so all that survives is a tombstone
     * whose only frame is `runtime.raise` — the place the process was killed, never the place it
     * broke. Without this, every core-level abort looks identical and says nothing.
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

    // ------------------------------------------------------------ The tunnel

    /**
     * Raises the tunnel, or moves a running one onto a new configuration.
     *
     * A switch reuses the descriptor. Xray does not dup what it is handed — `AndroidTun` keeps the
     * number, its `Close()` is empty, and gVisor's fd-based endpoint closes nothing either — so the
     * same tunnel survives the core being stopped and started, and Android never sees the VPN go
     * down. The key in the status bar does not blink and no connection outside the tunnel is
     * exposed for the moment in between.
     *
     * The exception is a change to the tunnel itself rather than to the server: per-app rules, MTU
     * or address family. Those live in the descriptor, so [TunnelShape] decides when it has to be
     * rebuilt instead.
     */
    private fun startTunnel(config: String, requestedNodeId: String, requestedNodeName: String) {
        scope.launch {
            tunnelLock.withLock {
                val reloading = XrayCore.running
                try {
                    nodeId = requestedNodeId
                    nodeName = requestedNodeName
                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Starting)

                    captureNativeStderr()
                    installLogBridge()

                    // The core's counters start over with the new instance, so bank what the old
                    // one moved before it goes away.
                    if (reloading) {
                        carriedUploadBytes += XrayCore.uploadBytes
                        carriedDownloadBytes += XrayCore.downloadBytes
                        stopCore()
                    }

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.EstablishingTunnel)

                    val shape = TunnelShape.of(settings.value)
                    if (tunDescriptor == null || tunnelShape != shape) {
                        runCatching { tunDescriptor?.close() }
                        tunDescriptor = establishTunnel(shape)
                        tunnelShape = shape
                    }
                    val descriptor = tunDescriptor
                        ?: throw IllegalStateException(strings.get(R.string.error_establish_null))

                    // Before the configuration is parsed, because the geo databases are resolved
                    // while it is being read and a reference that cannot be resolved rejects the
                    // whole document rather than the rule that needed it.
                    XrayCore.setAssetPath(geoDirectory())

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Handshaking)

                    XrayCore.start(config, descriptor.fd) { fd -> protect(fd) }
                    currentConfig = config

                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Testing)
                    startDefaultInterfaceMonitor()
                    startTrafficPolling()

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
                    // machine.
                    android.util.Log.e("MyDropVpn", "core failed to start: $message", error)
                    _state.value = VpnState.Failed(nodeId, message)
                    stopTunnel()
                }
            }
        }
    }

    /**
     * Everything about the tunnel that a running core cannot be moved between.
     *
     * Compared rather than assumed: a server switch reuses the descriptor, and reusing one built
     * for different per-app rules would silently keep routing the wrong applications.
     */
    private data class TunnelShape(
        val mtu: Int,
        val ipv6: Boolean,
        val splitMode: SplitTunnelMode,
        val packages: Set<String>,
    ) {
        companion object {
            fun of(settings: com.mydrop.vpn.core.model.AppSettings) = TunnelShape(
                mtu = settings.mtu,
                ipv6 = settings.enableIpv6,
                splitMode = settings.splitTunnelMode,
                packages = settings.splitTunnelPackages,
            )
        }
    }

    /**
     * Opens the tunnel Android will route through.
     *
     * Under sing-box these values arrived from the core and this method only transcribed them.
     * Xray asks for none of it, so the numbers are chosen here — and they are the same numbers,
     * because they were never really the core's opinion in the first place.
     */
    private fun establishTunnel(shape: TunnelShape): ParcelFileDescriptor {
        val builder = Builder()
        builder.setSession(nodeName.ifEmpty { "MyDrop" })
        builder.setMtu(shape.mtu)

        // A private range nothing routes to, so the tunnel's own addresses cannot collide with the
        // network it sits on. /30 and /126 because exactly one address is needed at each end.
        builder.addAddress("172.19.0.1", 30)
        builder.addRoute("0.0.0.0", 0)
        if (shape.ipv6) {
            builder.addAddress("fdfe:dcba:9876::1", 126)
            builder.addRoute("::", 0)
        }

        // Any address will do: every query is caught by the routing rule on port 53 and answered by
        // the core's own resolver. What matters is that Android is told there IS a DNS server
        // inside the tunnel, or it keeps using the network's and the queries never arrive.
        builder.addDnsServer("172.19.0.2")
        if (shape.ipv6) builder.addDnsServer("fdfe:dcba:9876::2")

        when (shape.splitMode) {
            SplitTunnelMode.Off -> Unit
            SplitTunnelMode.AllowList -> shape.packages.sorted().forEach {
                runCatching { builder.addAllowedApplication(it) }
            }
            SplitTunnelMode.BlockList -> shape.packages.sorted().forEach {
                runCatching { builder.addDisallowedApplication(it) }
            }
        }

        // Without this the app's own subscription refreshes and latency probes would be routed into
        // the tunnel that is still coming up — and the probe that measures the tunnel has to leave
        // the phone directly or it measures itself.
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        return builder.establish()
            ?: throw IllegalStateException(strings.get(R.string.error_establish_null))
    }

    /** Where [com.mydrop.vpn.data.GeoAssetStore] keeps the databases the routing rules read. */
    private fun geoDirectory(): String =
        java.io.File(filesDir, com.mydrop.vpn.data.GeoAssetStore.DIRECTORY).absolutePath

    /**
     * Sends the core's output to the journal.
     *
     * Installed on every start because the bridge inside the binding is registered after the core
     * comes up, and the level mapping is the core's own — see [XrayCore.levelOf] for why reading it
     * as anything else is a mistake this project has already made once.
     */
    private fun installLogBridge() {
        XrayCore.setLogger { level, message ->
            // The per-connection flood belongs in logcat and the file, not in the journal the user
            // reads: at trace and debug the core prints a line for every socket it opens.
            if (level == LogEntry.Level.Trace || level == LogEntry.Level.Debug) {
                logs.trace(NATIVE_TAG, message)
            } else {
                logs.log(level, message)
            }
        }
    }

    /**
     * Reads the byte counters out of the core once a second.
     *
     * Xray has no status stream to subscribe to, so the numbers are pulled rather than pushed. The
     * rate is a compromise: the connect screen shows a speed, and a speed needs two samples.
     */
    private fun startTrafficPolling() {
        trafficPoller?.cancel()
        trafficPoller = scope.launch {
            var previousUp = 0L
            var previousDown = 0L
            logs.trace(NATIVE_TAG, "traffic counters: ${XrayCore.statsReport}")
            while (isActive) {
                val up = XrayCore.uploadBytes
                val down = XrayCore.downloadBytes
                _traffic.value = TrafficStats(
                    uploadBytes = carriedUploadBytes + up,
                    downloadBytes = carriedDownloadBytes + down,
                    // Per second because the interval is a second; clamped at zero because a core
                    // that has just restarted reports less than the sample before it.
                    uploadBytesPerSecond = (up - previousUp).coerceAtLeast(0),
                    downloadBytesPerSecond = (down - previousDown).coerceAtLeast(0),
                )
                previousUp = up
                previousDown = down
                delay(TRAFFIC_POLL_MILLIS)
            }
        }
    }

    /**
     * Queues the teardown behind whatever the tunnel lock is doing.
     *
     * Tapping the control while it is connecting used to tear the core down while [startTunnel] was
     * still inside it. The state is moved eagerly so the button answers the tap straight away; the
     * actual close waits its turn.
     */
    private fun stopTunnelWhenIdle(reason: String) {
        logs.trace(NATIVE_TAG, "stop requested: $reason")
        if (_state.value.isActive) _state.value = VpnState.Disconnecting
        scope.launch { tunnelLock.withLock { stopTunnel() } }
    }

    /** Stops the core without touching the tunnel, so the descriptor can be handed to the next one. */
    private fun stopCore() {
        trafficPoller?.cancel()
        trafficPoller = null
        XrayCore.setLogger(null)
        runCatching { XrayCore.stop() }
            .onFailure { logs.trace(NATIVE_TAG, "core stop complained: ${it.message}") }
    }

    private fun stopTunnel() {
        if (_state.value is VpnState.Disconnected) return
        logs.trace(NATIVE_TAG, "tearing the core down")

        // A failure has to survive the teardown it triggers. The old code set Failed, called this,
        // and had it overwrite the state with Disconnecting on the next line — so the check further
        // down never saw a failure and the connect screen never showed a reason for one.
        val failure = _state.value as? VpnState.Failed
        if (failure == null) _state.value = VpnState.Disconnecting

        stopCore()
        stopDefaultInterfaceMonitor()

        // Only here. The core reads and writes this descriptor but never closes it, so it stays
        // open across every server switch and is closed exactly once, when the tunnel really ends.
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        tunnelShape = null
        currentConfig = null

        carriedUploadBytes = 0
        carriedDownloadBytes = 0
        sessionStartedAtMillis = 0
        _traffic.value = TrafficStats.Zero
        _state.value = failure ?: VpnState.Disconnected

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ The network underneath

    /**
     * Restarts the core on the same tunnel after the ground moved.
     *
     * sing-box had `ResetNetwork` for this: one call that closed the connections pinned to the old
     * interface and made the core re-dial. Xray has no equivalent, and telling it nothing leaves
     * every one of those connections hanging on a route that no longer exists — which is what a
     * user experiences as the internet stopping until they toggle the tunnel by hand.
     *
     * Stopping and starting the instance is the blunt version of the same thing, and on the same
     * descriptor it costs no more than the connections that were already dead: Android never sees
     * the VPN go down, no traffic escapes the tunnel in the gap, and the geo databases and
     * configuration are already in memory.
     */
    private fun restartCoreOnSameTunnel(why: String) {
        val config = currentConfig ?: return
        val descriptor = tunDescriptor ?: return
        scope.launch {
            tunnelLock.withLock {
                if (!XrayCore.running) return@withLock
                logs.trace(NATIVE_TAG, "$why, restarting the core on the same tunnel")
                carriedUploadBytes += XrayCore.uploadBytes
                carriedDownloadBytes += XrayCore.downloadBytes
                stopCore()
                installLogBridge()
                runCatching { XrayCore.start(config, descriptor.fd) { fd -> protect(fd) } }
                    .onFailure {
                        logs.error(R.string.log_start_failed, it.message.orEmpty())
                        _state.value = VpnState.Failed(nodeId, it.message.orEmpty())
                        stopTunnel()
                        return@withLock
                    }
                startTrafficPolling()
            }
        }
    }

    /**
     * Watches the one network the tunnel actually rides on.
     *
     * `registerNetworkCallback(request)` was the mistake here: it fires for *every* network
     * matching the request rather than for the current default, so with Wi-Fi and cellular both up
     * the core followed whichever callback arrived last. `registerBestMatchingNetworkCallback`
     * reports the default and only the default.
     */
    private fun startDefaultInterfaceMonitor() {
        stopDefaultInterfaceMonitor()

        fun notify(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
            // The one thing this monitor must never report is our own tunnel. Once the TUN is up it
            // becomes the system's default network, and treating it as the way out is how a tunnel
            // ends up dialling into itself.
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

            // `onCapabilitiesChanged` fires for every capability the system revises, which on a
            // busy Wi-Fi network is several times a minute and almost never about anything this
            // cares about. Acting on an unchanged interface would restart the core for nothing.
            if (!interfaceWasLost &&
                network == reportedInterface &&
                name == lastReportedInterfaceName &&
                expensive == lastReportedExpensive
            ) {
                return
            }

            val previousName = lastReportedInterfaceName
            val recovered = interfaceWasLost
            interfaceWasLost = false
            _hasNetwork.value = true
            reportedInterface = network
            lastReportedInterfaceName = name
            lastReportedExpensive = expensive
            logs.trace(NATIVE_TAG, "defaultInterface -> $name#$index expensive=$expensive")
            setUnderlying(network)

            // Two shapes of the same event. A different interface is the obvious one; the same
            // interface returning after there was none is the one that hid for a long time, and it
            // is what Wi-Fi dropping and reconnecting looks like from here.
            //
            // Only on an actual change: the first interface a tunnel gets is not one, and
            // restarting there would throw away the connections the core has just opened.
            val changed = previousName != null && previousName != name
            if (changed || recovered) {
                val what = if (changed) "handover $previousName -> $name" else "recovered on $name"
                restartCoreOnSameTunnel(what)
                // And tell the watchdog, which would otherwise learn about it from a probe that is
                // up to a full interval away.
                _handovers.tryEmit(name)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(network)
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = notify(network)

            override fun onLost(network: Network) {
                // Only the interface we actually reported: any other network going away leaves the
                // route perfectly usable.
                if (network != reportedInterface) return

                // Not acted on immediately, and that is the whole point. On a clean handover
                // `onAvailable` for the replacement arrives first and cancels this before it runs.
                // On an unclean one — Wi-Fi simply vanishing, which is the common case walking out
                // of range — the replacement is a few hundred milliseconds behind, and declaring
                // the loss into that gap takes every in-flight connection down for nothing.
                cancelPendingInterfaceLoss()
                val confirm = Runnable {
                    pendingInterfaceLoss = null

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
                    interfaceWasLost = true
                    _hasNetwork.value = false
                    logs.trace(NATIVE_TAG, "defaultInterface -> (none)")
                    setUnderlying(null)
                }
                pendingInterfaceLoss = confirm
                interfaceHandler.postDelayed(confirm, INTERFACE_LOSS_GRACE_MILLIS)
            }
        }

        networkCallback = callback
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivityManager.registerBestMatchingNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                    interfaceHandler,
                )
            } else {
                connectivityManager.registerDefaultNetworkCallback(callback, interfaceHandler)
            }
        }.onFailure { logs.trace(NATIVE_TAG, "interface monitor unavailable: ${it.message}") }
    }

    private fun stopDefaultInterfaceMonitor() {
        cancelPendingInterfaceLoss()
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        reportedInterface = null
        lastReportedInterfaceName = null
        lastReportedExpensive = null
        interfaceWasLost = false
        _hasNetwork.value = true
    }

    private fun cancelPendingInterfaceLoss() {
        pendingInterfaceLoss?.let(interfaceHandler::removeCallbacks)
        pendingInterfaceLoss = null
    }

    /**
     * Names the physical network the tunnel is riding on.
     *
     * `VpnService.Builder` decides the TUN's capabilities once, at `establish`, and nothing
     * revisits them afterwards — including `setMetered(false)`. So after a handover the VPN network
     * still advertises whatever was true when the tunnel came up, and applications inside it read a
     * stale validated/metered state: the core is dialling fine while the phone insists there is no
     * internet. Handing the platform the current underlying network keeps that derived state honest.
     */
    private fun setUnderlying(network: Network?) {
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
    }

    // ------------------------------------------------------------ Notification

    private fun createNotificationChannel() {
        // Through the resolver rather than getString: the channel name shows up in the system
        // notification settings, and it should say what the user chose in the app rather than what
        // the phone is set to.
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
}
