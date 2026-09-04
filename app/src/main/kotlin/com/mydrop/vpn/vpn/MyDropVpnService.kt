package com.mydrop.vpn.vpn

import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mydrop.vpn.MainActivity
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.CoreChatter
import com.mydrop.vpn.core.model.CoreGenerations
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.net.interfaceCidr
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.data.GeoAssetStore
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

/**
 * Owns the platform tunnel and the Xray core.
 *
 * It used to wear three hats: the [VpnService] holding the TUN descriptor, the libbox
 * `PlatformInterface` the old core called back into, and the `CommandServerHandler` it used to ask
 * the app to stop or reload. Two of the three are gone with the port, and that is the shape of the
 * change: libbox inverted control and asked the app to open tunnels, enumerate interfaces and
 * resolve names, while Xray is handed a descriptor and a way to protect a socket and decides the
 * rest itself. Four hundred and seventy-nine lines of answering questions went with it.
 *
 * What did not change is everything around the core — the notification, the state machine, the
 * default-interface monitor, the wake monitor, the core-generation counter and the journal budget.
 * Those describe Android and this app, not the core, and they are the parts that took the longest
 * to get right.
 */
class MyDropVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mydrop.vpn.action.START"
        const val ACTION_STOP = "com.mydrop.vpn.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_NODE_NAME = "node_name"

        /**
         * Which outbound the balancer starts pointed at.
         *
         * Carried in the intent rather than derived here, because only the side that built the
         * document knows which of its outbounds is the server the user chose.
         */
        const val EXTRA_PINNED_TAG = "pinned_tag"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mydrop_tunnel"
        private const val NATIVE_TAG = "YumiCore"

        /** gRPC's word for "the caller hung up", which on these streams is always us. */
        private const val CANCELLED = "context canceled"

        /**
         * How long a lost default interface is given to be replaced before the core is told it
         * has none. Long enough to cover a Wi-Fi → cellular handover and transient Wi-Fi signal
         * glitches, short enough that a real loss is not hidden from the core for a noticeable stretch.
         */
        private const val INTERFACE_LOSS_GRACE_MILLIS = 2000L

        /**
         * How often the byte counters are read.
         *
         * One second, which is what sing-box pushed and what the connect screen was built to
         * animate. Faster shows noise as speed; slower makes the figure lag the traffic visibly.
         */
        private const val TRAFFIC_POLL_MILLIS = 1_000L

        /**
         * How long [onDestroy] will wait for the tunnel lock before tearing down without it.
         *
         * Android's own limit for `onDestroy` is around twenty seconds; five leaves room for the
         * rest of the teardown and is far longer than a stop has ever taken.
         */
        private const val DESTROY_LOCK_TIMEOUT_MILLIS = 5_000L

        /**
         * The addresses the tunnel carries.
         *
         * Arbitrary and private on purpose — nothing routes to them, and they exist so the
         * interface has a family to install a default route for. The same pair the sing-box
         * configuration used, kept so a phone upgrading across the port sees no change.
         */
        private const val TUN_ADDRESS_V4 = "172.19.0.1"
        private const val TUN_PREFIX_V4 = 30
        private const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1"
        private const val TUN_PREFIX_V6 = 126

        /** Advertised to applications; see [establishTunnel] for why the value does not matter. */
        private const val TUN_DNS_V4 = "172.19.0.2"

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats.Zero)
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        /**
         * Round trips the core measured *through* each server of the selector group, by node tag.
         *
         * The only numbers in the app that describe the thing that actually breaks. Everything
         * else measures the server's port from the phone, which under interference answers
         * cheerfully while the sessions behind it die — the failure this app exists to escape.
         * These come from the core pulling a page through each outbound in turn, so a server that
         * accepts connections and carries nothing scores no delay at all and simply is not here.
         */
        private val _coreDelays = MutableStateFlow<Map<String, Int>>(emptyMap())
        val coreDelays: StateFlow<Map<String, Int>> = _coreDelays.asStateFlow()

        /**
         * Empties the table before a fresh test is asked for.
         *
         * Without it the answer to "has the core measured yet" is yes before it has started: the
         * flow still holds whatever the last test found, and a caller waiting for numbers is
         * handed the previous outage's.
         */
        fun forgetCoreDelays() {
            _coreDelays.value = emptyMap()
        }

        /**
         * Emitted with the new interface name whenever the tunnel moves between physical
         * networks — Wi-Fi to cellular and back.
         *
         * This exists so the failover watchdog does not have to wait out its own clock to find
         * out that the ground moved. A handover kills every connection pinned to the old
         * interface, and whether the current server survives that is a question worth asking at
         * once rather than up to twenty seconds later.
         *
         * Replay is zero and the buffer drops the oldest on overflow: a handover is only
         * interesting while it is current, and a subscriber that was not listening at the time has
         * nothing to catch up on.
         */
        /**
         * Whether the phone has working internet of its own, underneath the tunnel.
         *
         * Not "is there an interface" but "does that interface carry anything", which is Android's
         * own verdict: `NET_CAPABILITY_VALIDATED` is set when the platform has reached the internet
         * over that network and cleared when it cannot. It is what the system uses to decide
         * whether to show the no-internet warning, and it costs nothing to read.
         *
         * The failover watchdog needs it to tell apart two things that are identical from a probe
         * and call for opposite actions: a dead server, which should be left, and a phone in a
         * lift, which should be waited out. A journal caught the difference — three different
         * servers timing out within two minutes while their owner was in a lift. The tunnel was
         * moved off a server that was working perfectly well, every open connection went with it,
         * and the replacement failed a moment later for exactly the same reason.
         *
         * Deliberately not an HTTP request of our own: whatever host it asked would have to be
         * reachable *directly*, from a country where the reason this app exists is that things are
         * not. A check that answered "no internet" every time would switch failover off entirely.
         */
        private val _hasNetwork = MutableStateFlow(true)
        val hasNetwork: StateFlow<Boolean> = _hasNetwork.asStateFlow()

        private val _handovers = MutableSharedFlow<String>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val handovers: SharedFlow<String> = _handovers.asSharedFlow()

        /**
         * What kind of network is under the tunnel right now.
         *
         * Read from the physical network rather than the tunnel: the app excludes itself from its
         * own VPN, so the default network it sees stays the real one. Anything reading this
         * through the tunnel would be told `TRANSPORT_VPN` and learn nothing.
         */
        private val _transport = MutableStateFlow(NetworkTransport.None)
        val transport: StateFlow<NetworkTransport> = _transport.asStateFlow()

        /**
         * Whether somebody is looking at the phone.
         *
         * Not a nicety. Several firmwares — Huawei and some Samsung and Xiaomi builds — switch
         * Wi-Fi off when the screen goes off and fall back to cellular, which means that without
         * this every press of the power button would read as leaving the house. Anything that acts
         * on the network changing has to be able to wait until there is somebody to act for.
         */
        private val _screenOn = MutableStateFlow(true)
        val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

        private val _wakeups = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** See [com.mydrop.vpn.data.TunnelController.wakeups] for what this is for. */
        val wakeups: SharedFlow<Unit> = _wakeups.asSharedFlow()

        /**
         * The live service, so a switch that does not restart anything can still tell the truth.
         *
         * Cleared in `onDestroy`. A static reference to a Service is a leak worth naming, and this
         * one is bounded: the service is a foreground singleton that exists exactly while a tunnel
         * does, and this companion already holds its state, traffic and handovers for the same
         * reason — the watchdog and the UI live in the same process and need them.
         */
        private var live: MyDropVpnService? = null

        /**
         * Records which server is carrying traffic now, without touching the core.
         *
         * Called after the selector has been pointed somewhere else. Without it the tunnel would
         * be on one server while the notification and the connect screen named another — the exact
         * confusion this app has already been bitten by, where the screen showed a server nobody
         * had chosen and nothing explained why.
         */
        fun noteNode(nodeId: String, nodeName: String) {
            val service = live ?: return
            service.nodeId = nodeId
            service.nodeName = nodeName
            if (_state.value is VpnState.Connected) {
                _state.value = VpnState.Connected(nodeId, service.sessionStartedAtMillis)
            }
            service.updateNotification()
        }

        fun start(
            context: Context,
            config: String,
            pinnedTag: String,
            nodeId: String,
            nodeName: String,
        ) {
            val intent = Intent(context, MyDropVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_PINNED_TAG, pinnedTag)
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

    /** The document a running core was started from, and the outbound it was pinned to. */
    private data class RunningConfig(val json: String, val pinnedTag: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises everything that brings the core up or down. Two taps in the server list used to
     * race two startTunnel coroutines through the same fields; whichever finished last decided
     * which server the UI claimed to be on.
     */
    private val tunnelLock = Mutex()

    /**
     * Which connect request is the current one. Every call to [startTunnel] takes the next number
     * and keeps it; a queued call whose number is no longer the latest has been superseded and
     * gives up its turn.
     *
     * The lock alone was not enough. It serialises the requests, which is what stops two of them
     * corrupting each other's fields — but serialising a hundred taps means performing a hundred
     * switches. A monkey test settled it: taps stopped at 17:57:53 and the tunnel went on moving
     * between servers by itself until 17:58:01, eight seconds of the screen naming countries
     * nobody was choosing any more, and thirty more cores raised and torn down for nothing.
     *
     * Only the newest request describes what the user wants. The rest are history.
     */
    private val connectRequests = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Counts the cores that are actually writing, which is not the same as the cores this service
     * thinks it started. See [CoreGenerations] — the app believes there is one, and a journal has
     * shown five.
     */
    private val coreGenerations = CoreGenerations()

    /**
     * The core's budget for writing to the journal. See [CoreChatter] — a storm of its lines once
     * left a fifty-megabyte journal covering two minutes, with the app's own account of what it
     * had done evicted from it entirely.
     */
    private val coreChatter = CoreChatter()

    /**
     * The tunnel, and this service owns it outright.
     *
     * sing-box dup'd whatever descriptor it was handed, so both sides held one and both closed it.
     * Xray does not: `AndroidTun` takes the number as given and its `Close()` is empty
     * (`proxy/tun/tun_android.go:46-48`), and the gVisor endpoint closes nothing either. That is
     * better rather than worse — the descriptor outlives a core restart, so a network handover or a
     * server switch never takes the interface down, the key never blinks in the status bar and no
     * packet escapes through the gap. It also means nothing else will ever close this.
     */
    private var tunDescriptor: ParcelFileDescriptor? = null

    /** Polls the core's byte counters; see [startTrafficPolling] for why polling at all. */
    private var trafficJob: Job? = null

    /**
     * What the running core was started from.
     *
     * Kept because a restart needs it and nobody else is holding it by then: a network handover
     * rebuilds the core on the same descriptor, and the document it is rebuilt from has to be the
     * one the tunnel is already carrying rather than whatever the settings happen to say now.
     */
    private var runningConfig: RunningConfig? = null
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

    /** Screen-on and idle-mode listener; see [startWakeMonitor]. */
    private var wakeReceiver: BroadcastReceiver? = null

    /**
     * Which default-interface monitor is the current one.
     *
     * A number rather than the core's listener object, which is what it used to be — the core no
     * longer hands one out. The purpose is unchanged: a callback queued by a monitor that has since
     * been replaced must not report to, or unregister, the monitor that replaced it.
     */
    private var monitorGeneration = 0

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

    /** Where the lines of cores that should not exist go. Empty on a healthy phone. */
    private val coreLeakLog by lazy { (application as MyDropApplication).container.coreLeakLog }

    private val alerts by lazy { (application as MyDropApplication).container.alerts }

    /** Whether the leak hunt's instruments may run at all — debug builds only. */
    private val diagnosticBuild by lazy {
        (application as MyDropApplication).container.diagnosticBuild
    }

    /** Notification copy and failure messages both surface to the user; both follow the setting. */
    private val strings by lazy { (application as MyDropApplication).container.strings }

    // ------------------------------------------------------------ Lifecycle

    override fun onCreate() {
        super.onCreate()
        // Instance identity in the trace. A tunnel that "restarts itself" looks identical in the
        // journal whether the service reloaded the core or Android destroyed and recreated the
        // whole service — and those have completely different causes.
        logs.trace(NATIVE_TAG, "service onCreate #${hashCode()}")
        live = this
        createNotificationChannel()
    }

    /**
     * Swiping the app out of Recents must not take the tunnel with it.
     *
     * Stock Android leaves a started foreground service alone here, and the default implementation
     * does nothing — but several OEM shells treat the swipe as "the user is done with this app"
     * and tear down its services unless the app says otherwise. Overriding it and declining to
     * stop is what says otherwise. There is a deliberate asymmetry with the notification's own
     * stop action: leaving the app is not the same gesture as switching the tunnel off, and only
     * one of them should disconnect anything.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logs.trace(NATIVE_TAG, "task removed from recents — tunnel stays up")
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
                // Also handed to startTunnel: the fields are what the notification and openTun
                // read, but the coroutine must decide state from the request it was given rather
                // than from whatever a later request has since overwritten them with.
                nodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
                nodeName = intent.getStringExtra(EXTRA_NODE_NAME).orEmpty()
                startForegroundNotification()
                startTunnel(
                    config,
                    intent.getStringExtra(EXTRA_PINNED_TAG).orEmpty(),
                    nodeId,
                    nodeName,
                )
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

            val document = container.tunnelConfigs.build(node)
            if (document == null) {
                stopSelf()
                return@launch
            }

            nodeId = node.id
            nodeName = node.name
            updateNotification()
            logs.info(R.string.log_system_start, node.name)
            startTunnel(document.json, document.pinnedTag, node.id, node.name)
        }
    }

    override fun onRevoke() {
        logs.warn(R.string.log_permission_revoked)
        stopTunnelWhenIdle("onRevoke: system withdrew VPN consent")
    }

    /**
     * The one teardown that cannot queue behind the lock, so it takes it the only way it can.
     *
     * Every other path into [stopTunnel] goes through [stopTunnelWhenIdle], which waits its turn.
     * This one used to call it directly and off the lock — and `onDestroy` runs on the main thread
     * while [startTunnel] runs on IO, so a service destroyed mid-connect had two threads in the
     * core at once: one starting it, one closing the descriptor and nulling the fields underneath.
     *
     * `runBlocking` on the main thread is not free and is not usually the answer. Here it is: the
     * work inside the lock is a stop, the system is already tearing the process down, and the
     * alternative is a race against native code. The timeout is what keeps a wedged core from
     * turning a teardown into an ANR — past it, the stop proceeds unsynchronised, which is exactly
     * where this started but only after five seconds of trying to do better.
     */
    override fun onDestroy() {
        logs.trace(NATIVE_TAG, "service onDestroy #${hashCode()}")
        if (live === this) live = null
        val orderly = runBlocking {
            withTimeoutOrNull(DESTROY_LOCK_TIMEOUT_MILLIS) { tunnelLock.withLock { stopTunnel() } }
        }
        if (orderly == null) {
            logs.trace(NATIVE_TAG, "teardown could not take the lock in time, stopping anyway")
            stopTunnel()
        }
        scope.cancel()
        super.onDestroy()
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
    private fun startTunnel(
        config: String,
        pinnedTag: String,
        requestedNodeId: String,
        requestedNodeName: String,
    ) {
        val request = connectRequests.incrementAndGet()
        scope.launch {
            tunnelLock.withLock {
                // Checked inside the lock, not before it: what matters is whether anything newer
                // arrived while this one was waiting its turn, and that is only knowable here.
                //
                // Nothing is undone by skipping, because nothing has been done — the fields below
                // and the state are still whatever the request in front left them. The newest
                // request is guaranteed to run, since no later number exists to displace it.
                if (request != connectRequests.get()) {
                    logs.trace(
                        NATIVE_TAG,
                        "connect request $request superseded by ${connectRequests.get()}, skipping",
                    )
                    return@withLock
                }
                val reloading = XrayCore.running
                try {
                    nodeId = requestedNodeId
                    nodeName = requestedNodeName
                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Starting)

                    NativeStderrCapture.install(logs)

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Handshaking)

                    // Stopped before the counters are banked, not after: the poller reads the same
                    // numbers this is about to zero, and one tick landing in between would put the
                    // outgoing core's figure back into `lastUploadBytes` and get it banked a second
                    // time — a session counter that grows by a server's worth of traffic on every
                    // switch.
                    stopTrafficPolling()
                    if (reloading) {
                        carriedUploadBytes += lastUploadBytes
                        carriedDownloadBytes += lastDownloadBytes
                    }
                    lastUploadBytes = 0
                    lastDownloadBytes = 0

                    _state.value =
                        VpnState.Connecting(nodeId, VpnState.Connecting.Phase.EstablishingTunnel)

                    // The descriptor outlives the core, and that is the whole reason a server
                    // switch is not a visible reconnect. Xray never closes what it is handed
                    // (`proxy/tun/tun_android.go:46-48`), so the interface stays up across the stop
                    // and start below: the key does not blink in the status bar, and no packet
                    // finds its way out through a gap that never opens.
                    val descriptor = tunDescriptor ?: establishTunnel()

                    // Named before the document is parsed, because `geoip:`/`geosite:` are resolved
                    // during parsing and a path set afterwards would be set too late.
                    XrayCore.setAssetPath(GeoAssetStore(this@MyDropVpnService, logs).directory.absolutePath)
                    XrayCore.setLogger(::writeCoreLine)

                    // Xray refuses to start on top of itself, and this is also where the old core
                    // actually goes away. sing-box's reload closed the previous instance and threw
                    // away whatever the close returned — which is how a field journal came to show
                    // five cores alive at once. Here the stop is a separate call with its own
                    // answer, and a stop that fails is visible instead of silent.
                    if (reloading) {
                        val closeStartedAt = SystemClock.elapsedRealtime()
                        runCatching { XrayCore.stop() }
                            .onSuccess {
                                logs.trace(
                                    NATIVE_TAG,
                                    "old core closed in ${SystemClock.elapsedRealtime() - closeStartedAt}ms",
                                )
                            }
                            .onFailure { failure ->
                                logs.trace(
                                    NATIVE_TAG,
                                    "old core refused to close after " +
                                        "${SystemClock.elapsedRealtime() - closeStartedAt}ms: ${failure.message}",
                                )
                            }
                    }

                    val startedAt = SystemClock.elapsedRealtime()
                    XrayCore.start(config, descriptor.fd, pinnedTag, ::protectSocket)
                    logs.trace(
                        NATIVE_TAG,
                        "core start took ${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "(${if (reloading) "switch" else "first start"})",
                    )
                    runningConfig = RunningConfig(config, pinnedTag)
                    // Started by this side now. The core used to ask for it through the platform
                    // interface, which is also why it had to be stopped when the core went away —
                    // that half is unchanged, in stopTunnel.
                    startDefaultInterfaceMonitor()

                    _state.value = VpnState.Connecting(nodeId, VpnState.Connecting.Phase.Testing)
                    startTrafficPolling()

                    // A switch continues the session rather than starting one: the user did not
                    // reconnect, and restarting the clock alongside the byte counters would say
                    // they did.
                    if (!reloading || sessionStartedAtMillis == 0L) {
                        sessionStartedAtMillis = System.currentTimeMillis()
                    }
                    startWakeMonitor()
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


    /**
     * Queues the teardown behind whatever the tunnel lock is doing.
     *
     * Tapping the control while it is connecting — or the core asking to be stopped from inside one
     * of its own callbacks — used to close the command server while [startTunnel] was still blocked
     * inside `startOrReloadService` on it. The state is moved eagerly so the button answers the tap
     * straight away; the actual close waits its turn.
     */
    private fun stopTunnelWhenIdle(reason: String) {
        logs.trace(NATIVE_TAG, "stop requested: $reason")
        // Retires every connect still waiting for the lock. Without this, tapping through a few
        // servers and then tapping off would stop the tunnel and immediately watch the last queued
        // connect raise it again — the one instruction the user gave last would be the one ignored.
        connectRequests.incrementAndGet()
        if (_state.value.isActive) _state.value = VpnState.Disconnecting
        scope.launch { tunnelLock.withLock { stopTunnel() } }
    }

    private fun stopTunnel() {
        if (_state.value is VpnState.Disconnected) return
        logs.trace(NATIVE_TAG, "tearing the core down")

        // A failure has to survive the teardown it triggers. The old code set Failed, called this,
        // and had it overwrite the state with Disconnecting on the next line — so the check further
        // down never saw a failure and the connect screen never showed a reason for one.
        val failure = _state.value as? VpnState.Failed
        if (failure == null) _state.value = VpnState.Disconnecting

        // Before the core is torn down: whatever it says on its way out belongs to a session that
        // is over, and the poller must not read counters from an instance that is going away.
        statusGeneration.incrementAndGet()
        stopTrafficPolling()
        XrayCore.setLogger(null)
        runCatching { XrayCore.stop() }

        stopDefaultInterfaceMonitor()
        stopWakeMonitor()
        runningConfig = null
        coreGenerations.reset()
        coreChatter.reset()

        runCatching { tunDescriptor?.close() }
        tunDescriptor = null

        carriedUploadBytes = 0
        carriedDownloadBytes = 0
        lastUploadBytes = 0
        lastDownloadBytes = 0
        sessionStartedAtMillis = 0
        _traffic.value = TrafficStats.Zero
        _coreDelays.value = emptyMap()
        _state.value = failure ?: VpnState.Disconnected

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
     * And the current network is seeded through [Handler.post] rather than inline. That began as a
     * defence against re-entering Go while the old core was still inside this call, and it outlived
     * the core that needed it: the seed still has to run after the callbacks are registered rather
     * than in the middle of registering them.
     */
    private fun startDefaultInterfaceMonitor() {
        // Registering without unregistering first leaked a callback on every server switch, because
        // reloading the core starts a fresh monitor. Android caps a process at 100 registered
        // callbacks and then throws, so the leak had a crash at the end of it.
        stopDefaultInterfaceMonitor()
        val generation = ++monitorGeneration

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
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _transport.value = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    NetworkTransport.Cellular

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    NetworkTransport.Wifi

                else -> NetworkTransport.Other
            }

            // `onCapabilitiesChanged` fires for every capability the system revises, which on a
            // busy Wi-Fi network is several times a minute and almost never about anything this
            // cares about. Re-reporting an unchanged interface costs a call into the core and a
            // line in the journal each time — the field logs showed the same `wlan0` reported
            // seven times in half a minute — and tells the core nothing it did not already know.
            if (!interfaceWasLost &&
                network == reportedInterface &&
                name == lastReportedInterfaceName &&
                expensive == lastReportedExpensive &&
                validated == _hasNetwork.value
            ) {
                return
            }
            // Kept before the report, because the reset below needs to know whether this is a
            // handover or the first interface this tunnel ever had.
            val previousName = lastReportedInterfaceName
            val recovered = interfaceWasLost
            interfaceWasLost = false
            // Validation is what the watchdog reads. A network that is attached but carrying
            // nothing — a lift, a captive portal before sign-in, a hotspot with no upstream —
            // reports here as no internet, which is exactly what it is.
            if (_hasNetwork.value != validated) {
                logs.trace(NATIVE_TAG, "internet on $name: ${if (validated) "yes" else "no"}")
            }
            _hasNetwork.value = validated
            reportedInterface = network
            lastReportedInterfaceName = name
            lastReportedExpensive = expensive
            logs.trace(NATIVE_TAG, "defaultInterface -> $name#$index expensive=$expensive")
            setUnderlying(network)

            // Telling the core about the new interface does not rescue the connections that were
            // pinned to the old one — those are already dead, and nothing else reaps them. The
            // watchdog would only notice a probe interval later and then draw the wrong
            // conclusion, blaming a server that was never at fault.
            //
            // Two shapes of the same event. A different interface is the obvious one; the same
            // interface returning after the core was told there was none is the one that hid for a
            // long time, and it is what Wi-Fi dropping and reconnecting looks like from here.
            //
            // Both reset, and the second one is worth spelling out, because it was briefly removed
            // on the argument that a reset "drops active sockets" and a link that merely hiccuped
            // should be allowed to recover its TCP streams on its own. By this point there are no
            // streams left to spare. `recovered` is set in exactly one place — the confirmation
            // runnable in `onLost` — immediately before the core is told `("", -1)`, and that
            // report is itself what takes every in-flight connection down. Anything reaching here
            // was absent for longer than INTERFACE_LOSS_GRACE_MILLIS, which means Android has
            // since handed out a new netid and the old sockets cannot carry anything regardless.
            // Skipping the reset therefore preserves nothing; it only leaves the corpses to time
            // out on their own, fifteen to forty-five seconds each, and leaves the watchdog to
            // discover the mess and blame the server for it.
            //
            // Only on an actual change: the first interface a tunnel gets is not one, and resetting
            // there would throw away the connections the core has just opened.
            val changed = previousName != null && previousName != name
            if (changed || recovered) {
                val what = if (changed) "handover $previousName -> $name" else "recovered on $name"
                logs.trace(NATIVE_TAG, "$what, resetting network")
                resetCoreNetwork()
                // And tell the watchdog, which would otherwise learn about it from a probe that
                // is up to a full interval away.
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
                    if (generation != monitorGeneration) return@Runnable

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
                    _transport.value = NetworkTransport.None
                    logs.trace(NATIVE_TAG, "defaultInterface -> (none)")
                    setUnderlying(null)
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
            if (generation != monitorGeneration) return@post
            connectivityManager.activeNetwork?.let(::notify)
        }
    }

    private fun stopDefaultInterfaceMonitor() {
        cancelPendingInterfaceLoss()
        // Belongs to the monitor being torn down, not to the next one. Left standing, the first
        // callback of a freshly started monitor reads as "the interface came back" and resets the
        // core's network — throwing away the connections a brand-new tunnel has just opened.
        interfaceWasLost = false
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        monitorGeneration++
        reportedInterface = null
        lastReportedInterfaceName = null
    }

    /**
     * Tells the watchdog the phone is awake again.
     *
     * Registered in code rather than the manifest because `ACTION_SCREEN_ON` cannot be declared
     * there — a deliberate platform restriction, since it would otherwise wake every installed app
     * dozens of times a day. Here it costs nothing: the receiver lives exactly as long as a
     * tunnel does, and the broadcasts it listens for are sent whether or not anybody is listening.
     *
     * Two events rather than one. The screen coming on is the moment somebody is about to use the
     * connection; leaving idle mode is the same moment for a phone that woke without its screen —
     * an alarm, a call, a sync window.
     */
    private fun startWakeMonitor() {
        if (wakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> _screenOn.value = false

                    Intent.ACTION_SCREEN_ON -> {
                        _screenOn.value = true
                        _wakeups.tryEmit(Unit)
                    }

                    // Fires on the way into idle as well as out of it, and the old `else` branch
                    // read both as "the phone woke up" — so going to sleep raised the screen flag
                    // and asked for a probe, which is the one moment the phone should be left
                    // alone. Only the way out is a wake-up, and even that is not a screen: a
                    // maintenance window is the phone doing chores with nobody looking, so the
                    // flag that guards moving servers between networks stays where it was.
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java)?.isDeviceIdleMode
                        // Told to the core either way, which is the point of listening at all.
                        //
                        // sing-box could be told to pause its timers going into Doze and to
                        // re-dial coming out of it. Xray has no such call, so the first half is
                        // simply lost — its keepalives will fire into a suspended network and
                        // accomplish nothing, which costs wakeups but breaks nothing.
                        //
                        // The half that mattered to the user is not lost: connections that died
                        // quietly during the night are still re-dialled on the way out, because
                        // the wakeup emitted below sends the watchdog to look at the tunnel.
                        logs.trace(NATIVE_TAG, "device idle mode -> ${idle == true}")
                        if (idle == true) return
                        _wakeups.tryEmit(Unit)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        // Exported, and that is not a slip. These are broadcasts from the system, not from this
        // app, and a receiver registered NOT_EXPORTED accepts only the app's own — it registers
        // without complaint and then never fires, which is exactly how this went out the first
        // time. Protected system broadcasts cannot be forged by other apps, so the flag costs
        // nothing here.
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            wakeReceiver = receiver
            // Asked once at startup rather than assumed: a tunnel raised by Always-on VPN or by the
            // boot receiver comes up with the screen off, and starting from "on" would let the
            // first network change through the gate it exists to hold.
            _screenOn.value = runCatching {
                getSystemService(PowerManager::class.java)?.isInteractive != false
            }.getOrDefault(true)
            logs.trace(NATIVE_TAG, "wake monitor on, screen ${if (_screenOn.value) "on" else "off"}")
        }.onFailure {
            // Loud, because a silent failure here is invisible: the app goes on working and simply
            // never notices anything while the screen is off.
            logs.trace(NATIVE_TAG, "wake monitor unavailable: ${it.message}")
        }
    }

    /**
     * Feeds one line from the core to the generation counter, and says so when there is more than
     * one core writing.
     *
     * Called for every line the core emits, so it does as little as possible: the parse gives up
     * on the first character that is not part of an age prefix, and the warning is rate-limited
     * inside [CoreGenerations] rather than here.
     *
     * The message goes to the journal at warning level because it is not diagnostic trivia — while
     * it is true, several cores are fighting over one TUN and cutting each other's connections,
     * and the person holding the phone is experiencing that as the app being broken.
     */
    private fun noteCoreGeneration(message: String, level: LogEntry.Level) {
        val now = SystemClock.elapsedRealtime()
        val line = coreGenerations.observe(message, now)

        // Written whole and outside the budget, because this file exists for exactly these lines
        // and there are only ever a few of them on a healthy phone: none. Kept apart from the main
        // journal so the leak's own noise cannot rotate away the record of the leak.
        if (line.leaked && diagnosticBuild) {
            coreLeakLog.write(
                level.name.first(),
                "core+${line.ageMillis / 1000}s",
                message,
            )
        }

        val report = coreGenerations.dueReport(now) ?: return
        logs.warn(
            R.string.log_cores_alive,
            report.liveCores,
            report.oldestAgeMillis / 60_000,
        )
        android.util.Log.w(
            NATIVE_TAG,
            "core leak: ${report.liveCores} instances alive, oldest ${report.oldestAgeMillis}ms",
        )
        // Past the user's alert switches deliberately, and only where the instruments run: those
        // switches are about work done on the owner's behalf, and this is the app saying it is in
        // a state it cannot get out of.
        if (diagnosticBuild) {
            alerts.coresAlive(report.liveCores, report.oldestAgeMillis / 60_000)
        }
    }

    private fun stopWakeMonitor() {
        wakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        wakeReceiver = null
    }

    /**
     * True from the moment the core is told it has no default interface until it is told about one
     * again.
     *
     * Without it, a network that goes away and comes back under the same name is invisible: the
     * handover check compares names, `wlan0` equals `wlan0`, and nothing is reset. But every
     * connection pinned to that interface died while it was gone, and the core has just been told
     * "no interface" and then "wlan0" — which is at least as much of a change as swapping Wi-Fi
     * for cellular. Wi-Fi dropping and returning is also the commonest form of this there is.
     */
    private var interfaceWasLost = false

    /** Part of what makes an interface report worth repeating; see the check in `notify`. */
    private var lastReportedExpensive: Boolean? = null

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
     * sing-box exposed exactly this on its command server. Xray has no equivalent — there is no
     * method on `core.Instance` that drops its connections — so the tunnel is rebuilt instead: the
     * core is stopped and started again on the *same* descriptor, which takes about as long as a
     * server switch and is invisible from outside because the interface never goes down.
     *
     * Rebuilding does more than the old call did: it also re-reads the configuration. That is
     * harmless here, and it is why this is a restart rather than a reconnect.
     */
    private fun resetCoreNetwork() {
        val descriptor = tunDescriptor ?: return
        val config = runningConfig ?: return
        scope.launch {
            tunnelLock.withLock {
                if (!XrayCore.running) return@withLock
                runCatching {
                    XrayCore.stop()
                    XrayCore.start(config.json, descriptor.fd, config.pinnedTag, ::protectSocket)
                }.onFailure { logs.trace(NATIVE_TAG, "core restart after handover failed: ${it.message}") }
            }
        }
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


    // ------------------------------------------------------------- The core

    /**
     * Builds the platform tunnel.
     *
     * This used to be `openTun`, and the core decided what went into it: sing-box was told the
     * addresses, routes and per-app rules in its own configuration and handed them back through the
     * platform interface. Xray asks for none of that — it is handed a descriptor — so the decisions
     * are made here, from the same settings the document was built from.
     *
     * The IPv6 address is unconditional, and that is the whole fix for a leak rather than a
     * preference. A VpnService captures only the routes it is given, and a route can only be given
     * for a family the interface has an address in. With no v6 address there is no `::/0` route, so
     * Android leaves the *physical* interface as the default gateway for every IPv6 packet — on any
     * dual-stack Wi-Fi or carrier network that is the real address of the phone, in the clear,
     * while the app shows a connected tunnel. "IPv6 off" has to mean "no IPv6 leaves this phone";
     * what happens to the captured traffic is then decided in the routing rules.
     */
    private fun establishTunnel(): ParcelFileDescriptor {
        val settings = (application as MyDropApplication).container.settings.value
        val builder = Builder()
        builder.setSession(nodeName.ifEmpty { "Yumi" })
        builder.setMtu(settings.mtu)

        builder.addAddress(TUN_ADDRESS_V4, TUN_PREFIX_V4)
        builder.addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)

        // Any address will do: with DNS hijacking on, the core answers whatever is asked of port 53
        // regardless of who it was addressed to. What matters is that applications are handed a
        // resolver inside the tunnel rather than the one the physical network advertises.
        builder.addDnsServer(TUN_DNS_V4)

        when (settings.splitTunnelMode) {
            SplitTunnelMode.Off -> Unit
            // An allow-list with nothing in it does not mean "allow nothing" to VpnService — it
            // means everything, because a list it never receives is a list it never applies. The
            // screen promises the opposite, so an empty selection is left as the mode being off.
            SplitTunnelMode.AllowList -> settings.splitTunnelPackages.sorted().forEach {
                runCatching { builder.addAllowedApplication(it) }
            }
            SplitTunnelMode.BlockList -> settings.splitTunnelPackages.sorted().forEach {
                runCatching { builder.addDisallowedApplication(it) }
            }
        }

        // Without this the subscription refreshes and latency probes the app makes would be routed
        // into the tunnel that is still coming up — and the speed test would measure the connection
        // of the phone rather than the server it is supposed to be rating.
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val descriptor = builder.establish()
            ?: throw IllegalStateException(strings.get(R.string.error_establish_null))
        tunDescriptor = descriptor
        return descriptor
    }

    /**
     * Excludes a socket the core opened from the tunnel the core is serving.
     *
     * Called from Go, on whatever thread opened the socket. Returning false fails that dial, which
     * is the right answer: an unprotected socket loops its traffic back into the tunnel it came
     * from and hangs there with no error anywhere.
     */
    private fun protectSocket(fd: Int): Boolean = protect(fd)

    /**
     * Reads the byte counters of the core once a second.
     *
     * sing-box pushed a status message and this side only had to listen. Xray keeps counters and
     * answers when asked, so the asking lives here. The rates are differences rather than numbers
     * the core reports, and the totals carry across a core restart — see the banking in
     * [startTunnel] for why that matters.
     */
    private fun startTrafficPolling() {
        stopTrafficPolling()
        trafficJob = scope.launch {
            var previousUp = 0L
            var previousDown = 0L
            var previousAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TRAFFIC_POLL_MILLIS)
                if (!XrayCore.running) continue

                val up = XrayCore.uploadBytes
                val down = XrayCore.downloadBytes
                val now = SystemClock.elapsedRealtime()
                val seconds = (now - previousAt).coerceAtLeast(1L) / 1000.0

                lastUploadBytes = up
                lastDownloadBytes = down
                _traffic.value = TrafficStats(
                    uploadBytes = carriedUploadBytes + up,
                    downloadBytes = carriedDownloadBytes + down,
                    // Clamped at zero because a restarted core counts again from nothing, and a
                    // negative speed on the connect screen is worse than a missed tick.
                    uploadBytesPerSecond = ((up - previousUp) / seconds).toLong().coerceAtLeast(0),
                    downloadBytesPerSecond = ((down - previousDown) / seconds).toLong()
                        .coerceAtLeast(0),
                    // Xray keeps no count of open connections and there is no honest number to put
                    // here, so the connect screen loses one figure. Zero rather than a guess.
                    activeConnections = 0,
                )
                previousUp = up
                previousDown = down
                previousAt = now
            }
        }
    }

    private fun stopTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = null
    }

    /**
     * One line from the core, delivered on a Go thread.
     *
     * Everything the old status handler did with a log line happens here. The generation counter
     * reads every line, because the count of live cores is the reason the budget exists and reading
     * it off a sample of the evidence would be reading it off the symptom; the budget then decides
     * what reaches the journal.
     */
    private fun writeCoreLine(level: LogEntry.Level, message: String) {
        noteCoreGeneration(message, level)
        val verdict = coreChatter.admit(SystemClock.elapsedRealtime())
        if (verdict.suppressed > 0) {
            logs.warn(R.string.log_core_flood, strings.plural(R.plurals.core_lines, verdict.suppressed))
        }
        if (!verdict.write) return

        logs.log(level, message)
        // Mirrored to logcat as well as the in-app journal. The journal is an in-memory ring buffer
        // that dies with the process, so when the core misbehaves there is otherwise nothing to
        // read from a development machine.
        when (level) {
            LogEntry.Level.Error -> android.util.Log.e(NATIVE_TAG, message)
            LogEntry.Level.Warn -> android.util.Log.w(NATIVE_TAG, message)
            else -> android.util.Log.i(NATIVE_TAG, message)
        }
    }

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

}
