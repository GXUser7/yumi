package com.mydrop.vpn.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.mydrop.vpn.R

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

    /** Says out loud what the app would otherwise fix in silence. */
    val alerts = AlertNotifier(appContext, strings, enabled = { settings.value.faultAlerts })

    val logs = LogRepository(strings, diagnostics)
    val profiles = ProfileRepository(context.filesDir, applicationScope, writeFailure)
    val latencyTester = LatencyTester()
    val tunnelHealth = TunnelHealthCheck(logs)
    val speedTester = SpeedTester(logs, strings)

    /**
     * Identity handed to panels that count devices. Created on first use and kept in settings, so
     * it survives restarts — a panel that saw a new identifier on every refresh would burn through
     * the user's device limit in a week.
     */
    val subscriptionService = SubscriptionService(
        logs = logs,
        strings = strings,
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

    /** Keeps the failover list free of servers a subscription refresh has taken away. */
    val staleSelectionPruner = StaleSelectionPruner(
        profiles = profiles,
        settings = settings,
        logs = logs,
        scope = applicationScope,
    )

    init {
        // A store that cannot write is losing the user's servers silently; now it says so.
        reportWriteFailure = { error ->
            logs.error(R.string.log_store_write_failed, error.message ?: error::class.simpleName.orEmpty())
        }
        failoverWatchdog.start()
        staleSelectionPruner.start()
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
