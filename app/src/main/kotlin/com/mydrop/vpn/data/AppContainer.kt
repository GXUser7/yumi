package com.mydrop.vpn.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. The object count here is small enough that a DI framework would add
 * build time and indirection without buying anything.
 */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val versionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.substringBefore('-') ?: "0"

    val logs = LogRepository()
    val profiles = ProfileRepository(context.filesDir, applicationScope)
    val settings = SettingsRepository(context.filesDir, applicationScope)
    val latencyTester = LatencyTester()
    val speedTester = SpeedTester(logs)

    /**
     * Identity handed to panels that count devices. Created on first use and kept in settings, so
     * it survives restarts — a panel that saw a new identifier on every refresh would burn through
     * the user's device limit in a week.
     */
    val subscriptionService = SubscriptionService(
        logs = logs,
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
    )

    /**
     * The real sing-box tunnel. [SimulatedTunnelController] is kept around deliberately: it is
     * the only way to exercise the connect screen's states without a working server, and
     * swapping it back in is a one-line change.
     */
    val tunnel: TunnelController = SingBoxTunnelController(
        context = context.applicationContext,
        configs = tunnelConfigs,
        logs = logs,
    )

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
        logs = logs,
        scope = applicationScope,
    )

    val subscriptionRefresher = SubscriptionRefresher(
        profiles = profiles,
        service = subscriptionService,
        logs = logs,
    )

    /** Re-reads server lists on the cadence chosen in settings. */
    val subscriptionScheduler = SubscriptionScheduler(
        profiles = profiles,
        settings = settings,
        refresher = subscriptionRefresher,
        logs = logs,
        scope = applicationScope,
    )

    /** Keeps the failover list free of servers a subscription refresh has taken away. */
    val staleSelectionPruner = StaleSelectionPruner(
        profiles = profiles,
        settings = settings,
        logs = logs,
        scope = applicationScope,
    )

    init {
        seedDemoContentOnFirstRun()
        failoverWatchdog.start()
        staleSelectionPruner.start()
        subscriptionScheduler.start()
    }

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

    private fun seedDemoContentOnFirstRun() {
        if (profiles.state.value.seeded) return
        val nodes = DemoData.nodes()
        if (nodes.isEmpty()) {
            profiles.markSeeded()
            return
        }
        profiles.addNodes(nodes)
        profiles.addSubscription(DemoData.subscription(nodes.map { it.id }))
        logs.info("Добавлен демонстрационный набор: ${nodes.size} серверов")
    }
}
