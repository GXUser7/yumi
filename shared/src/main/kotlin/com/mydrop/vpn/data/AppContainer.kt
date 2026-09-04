package com.mydrop.vpn.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.NodeIdMigration
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mydrop.vpn.pairing.PairingClient

/**
 * Manual dependency graph. The object count here is small enough that a DI framework would add
 * build time and indirection without buying anything.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val versionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.substringBefore('-') ?: "0"

    // Order matters here: the journal writes localized lines, so it needs the resolver, and the
    // resolver needs somewhere to read the chosen language from.
    /**
     * Indirection because the journal is built after the first store that reports into it. Writes
     * only ever happen after construction, so by the time this is called it points somewhere.
     */
    private var reportWriteFailure: (Throwable) -> Unit = {}
    private val writeFailure: (Throwable) -> Unit = { reportWriteFailure(it) }

    val settings = SettingsRepository(context.filesDir, applicationScope, writeFailure)
    val strings = Strings(context) { settings.value.language }
    /**
     * On disk only when the build is debuggable — the flag Android itself sets, so no build
     * plumbing and no way for a release to switch it on by accident. See [DiagnosticLog] for why
     * this is not something to hand to everybody.
     */
    private val debuggable = (appContext.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val diagnostics = DiagnosticLog(
        directory = java.io.File(context.filesDir, "diagnostics"),
        enabled = debuggable,
    )

    /**
     * Lines written by cores that should no longer exist, and nothing else.
     *
     * Debug builds only — this is an instrument for finding one fault, not a feature. It stays
     * empty on a healthy phone, which is itself the reading: a file with anything in it is a leak
     * that happened, with the time it started and how old the leaked core was.
     *
     * Separate from [diagnostics] because that journal is the casualty. A leak writes hundreds of
     * lines a second, so a record kept inside the ring it is overflowing is a record that rotates
     * itself away before anybody looks — which is exactly what happened on the third of September,
     * where fifty megabytes of journal covered two minutes and said nothing about the three cores
     * that filled it.
     */
    val coreLeakLog = DiagnosticLog(
        directory = java.io.File(context.filesDir, "diagnostics"),
        enabled = debuggable,
        name = DiagnosticLog.CORES_FILE_NAME,
        maxBytes = 4L * 1024 * 1024,
    )

    /** True in debug builds, where the leak hunt's instruments are allowed to run. */
    val diagnosticBuild: Boolean get() = debuggable

    /** Says out loud what the app would otherwise fix in silence. */
    val alerts = AlertNotifier(appContext, strings, enabled = { kind ->
        settings.value.let {
            when (kind) {
                AlertKind.Server -> it.alertServer
                AlertKind.Dns -> it.alertDns
                AlertKind.Update -> it.alertUpdate
                AlertKind.Lists -> it.alertLists
            }
        }
    })

    val logs = LogRepository(strings, diagnostics)
    val profiles = ProfileRepository(context.filesDir, applicationScope, writeFailure)
    val latencyTester = LatencyTester()
    val tunnelHealth = TunnelHealthCheck(logs)
    val speedTester = SpeedTester(logs, strings)
    val pairingClient = PairingClient(appContext)

    /**
     * Identity handed to panels that count devices. Created on first use and kept in settings, so
     * it survives restarts — a panel that saw a new identifier on every refresh would burn through
     * the user's device limit in a week.
     */
    val subscriptionService = SubscriptionService(
        logs = logs,
        strings = strings,
        probeEndpoint = { tunnelConfigs.probe.value },
        // Named like every other client does it — version, platform, model. Panels read the agent
        // to decide which format to emit, and the caching front-ends in front of them key on it
        // too, so a bare "Yumi (Android)" shares one cache entry with every other install and can
        // be served an answer that was refused to somebody else.
        userAgent = "Yumi/$versionName (Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL})",
        identity = { deviceIdentity() },
    )

    val tunnelConfigs = TunnelConfigBuilder(
        context = context.applicationContext,
        settings = settings,
        logs = logs,
        selectedDns = { profiles.selectedDnsProfile()?.url },
        switchableGroup = ::switchableGroup,
        // A lambda, so the two can depend on each other: the store needs the tunnel's loopback
        // inbound to download through, and the builder needs to know whether the files are there.
        // Neither is asked until a configuration is actually being built.
        geoAvailable = { geoAssets.available() },
    )

    /**
     * Every server the tunnel may be moved onto without a restart.
     *
     * Deliberately not the whole subscription: each member is an outbound in the document the core
     * parses at startup, and three hundred of them would be paid for on every connect by everyone,
     * to make instant a switch that will never happen. The membership is exactly the places the
     * app itself can decide to go — the chosen server, the mobile list, and the failover pool —
     * and anything outside it still works, just the old expensive way.
     */
    private fun switchableGroup(selected: com.mydrop.vpn.core.model.ProxyNode):
        List<com.mydrop.vpn.core.model.ProxyNode> {
        val state = profiles.state.value
        val current = settings.value
        val byId = state.nodes.associateBy { it.id }
        // Truncated here rather than by the `take` at the end, which trims the tail — and the
        // tail is the ordinary spares. A long mobile list used to consume every slot before they
        // were ever reached.
        val named = current.mobileNodeIds.mapNotNull(byId::get)
        val mobile = named.take(com.mydrop.vpn.core.model.FailoverGroup.mobileSlots(named.size))
        val failover = com.mydrop.vpn.core.model.FailoverGroup.candidates(
            nodes = state.nodes,
            selected = selected,
            latencies = state.latencies,
            limit = com.mydrop.vpn.core.model.FailoverGroup.roomFor(named.size),
            chosen = current.failoverNodeIds,
            // Excluded here for the same reason FailoverWatchdog.swapAwayFrom excludes them: the
            // mobile servers are added above, by name, and a candidate list that can also contain
            // them spends slots on duplicates that `distinctBy` then throws away — a group smaller
            // than the twenty-four it was sized for. More than tidiness: the watchdog and this
            // have to compute the same list, or the lot can fall on a server the core was never
            // told about and an instant switch becomes a reconnect.
            exclude = current.mobileNodeIds,
        )
        // The chosen server first, so a truncated group still contains the one that matters.
        return (listOf(selected) + mobile + failover).distinctBy { it.id }.take(SWITCHABLE_LIMIT)
    }

    /**
     * The real Xray tunnel. [SimulatedTunnelController] is kept around deliberately: it is the
     * only way to exercise the connect screen's states without a working server, and swapping it
     * back in is a one-line change.
     */
    init {
        // The other half of the id move. The servers live in the profile store and the two lists
        // the user curated live in the settings store, and only this class holds both — so the
        // mapping is computed there and applied here, before anything reads either.
        val moved = profiles.nodeIdMigration
        if (moved.isNotEmpty()) {
            settings.update { current ->
                current.copy(
                    failoverNodeIds = NodeIdMigration.follow(current.failoverNodeIds, moved),
                    mobileNodeIds = NodeIdMigration.follow(current.mobileNodeIds, moved),
                )
            }
            logs.debug(R.string.log_node_ids_migrated, moved.size)
        }
    }

    val tunnel: TunnelController = XrayTunnelController(
        context = context.applicationContext,
        configs = tunnelConfigs,
        logs = logs,
    )

    /**
     * Fetches the geo databases the first time a tunnel comes up, and never again while running.
     *
     * After a connection rather than at startup, and the reason is the whole design of the store:
     * the files live on GitHub, the networks that make this app worth having are the ones where
     * GitHub does not answer, and the only road that works there is the tunnel itself. Until it is
     * up there is nothing to fall back to.
     *
     * A failure is not retried in a loop and not surfaced beyond the journal. Without the databases
     * the routing rules that name them are simply left out of the configuration, which the factory
     * already does — the tunnel comes up either way, carrying everything through the proxy.
     */
    val geoAssets: GeoAssetStore =
        GeoAssetStore(context.applicationContext, logs) { tunnelConfigs.probe.value }

    private val geoOnFirstConnection = applicationScope.launch {
        tunnel.state.first { it is VpnState.Connected }
        if (!geoAssets.available()) geoAssets.refresh()
    }

    /**
     * Shared by every entry point that can raise the tunnel: the UI, the Quick Settings tile,
     * the boot receiver and the service's own restart path.
     */
    val tunnelLauncher = TunnelLauncher(
        profiles = profiles,
        settings = settings,
        tunnel = tunnel,
        latencyTester = latencyTester,
        logs = logs,
        strings = strings,
    )

    /**
     * Moves the tunnel off a server that has stopped answering. Runs for the life of the process
     * and does nothing until a tunnel is up and the setting is on.
     */
    val failoverWatchdog = FailoverWatchdog(
        profiles = profiles,
        settings = settings,
        tunnel = tunnel,
        launcher = tunnelLauncher,
        latencyTester = latencyTester,
        tunnelHealth = tunnelHealth,
        configs = tunnelConfigs,
        logs = logs,
        alerts = alerts,
        scope = applicationScope,
    )

    /**
     * New versions of the app itself. The user agent is the same one subscriptions use — GitHub
     * refuses requests without one outright.
     */
    val updates = UpdateRepository(
        context = context.applicationContext,
        service = UpdateService(
            userAgent = "Yumi/$versionName (Android ${android.os.Build.VERSION.RELEASE}; " +
                "${android.os.Build.MODEL})",
            strings = strings,
            assetPrefix = if (appContext.packageName.startsWith("com.mydrop.vpn.tv")) "yumi-tv" else "yumi",
        ),
        settings = settings,
        logs = logs,
        alerts = alerts,
        strings = strings,
        currentVersion = versionName,
        scope = applicationScope,
    )

    private val updateScheduler = UpdateScheduler(
        settings = settings,
        updates = updates,
        scope = applicationScope,
    )

    val subscriptionRefresher = SubscriptionRefresher(
        profiles = profiles,
        service = subscriptionService,
        logs = logs,
        strings = strings,
    )

    /** Re-reads server lists on the cadence chosen in settings. */
    val subscriptionScheduler = SubscriptionScheduler(
        profiles = profiles,
        settings = settings,
        refresher = subscriptionRefresher,
        logs = logs,
        scope = applicationScope,
    )

    /** Hands a running tunnel a new configuration when a setting it was built from changes. */
    val tunnelSettingsApplier = TunnelSettingsApplier(
        settings = settings,
        profiles = profiles,
        tunnel = tunnel,
        launcher = tunnelLauncher,
        logs = logs,
        scope = applicationScope,
    )

    /** Keeps the failover list free of servers a subscription refresh has taken away. */
    val staleSelectionPruner = StaleSelectionPruner(
        profiles = profiles,
        settings = settings,
        logs = logs,
        alerts = alerts,
        scope = applicationScope,
    )

    private companion object {
        /** Members past this buy nothing: the app only ever chooses from the lists above. */
        const val SWITCHABLE_LIMIT = com.mydrop.vpn.core.model.FailoverGroup.SWITCHABLE
    }

    init {
        // A store that cannot write is losing the user's servers silently; now it says so.
        reportWriteFailure = { error ->
            logs.error(R.string.log_store_write_failed, error.message ?: error::class.simpleName.orEmpty())
        }
        failoverWatchdog.start()
        staleSelectionPruner.start()
        tunnelSettingsApplier.start()
        subscriptionScheduler.start()
        updateScheduler.start()
    }

    /** Whether the current connection is metered; see MainViewModel.speedTestIsMetered. */
    fun isActiveNetworkMetered(): Boolean = runCatching {
        appContext.getSystemService(android.net.ConnectivityManager::class.java)
            ?.isActiveNetworkMetered == true
    }.getOrDefault(false)

    private fun deviceIdentity(): DeviceIdentity {
        val stored = settings.value.deviceId.ifEmpty {
            java.util.UUID.randomUUID().toString().also { generated ->
                settings.update { it.copy(deviceId = generated) }
            }
        }
        return DeviceIdentity(
            hwid = stored,
            os = "Android",
            osVersion = android.os.Build.VERSION.RELEASE.orEmpty(),
            model = android.os.Build.MODEL.orEmpty(),
        )
    }
}
