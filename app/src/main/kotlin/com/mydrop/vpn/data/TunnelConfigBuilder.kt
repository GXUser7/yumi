package com.mydrop.vpn.data

import android.content.Context
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns a chosen server into the document the core reads at startup.
 *
 * This lives apart from [SingBoxTunnelController] because the service needs it too: when Android
 * starts the tunnel by itself — Always-on VPN, or the restart that follows the process being
 * killed — there is no controller call and no intent extras to read the configuration from, so
 * the service rebuilds it from the stored profile through this same path.
 */
class TunnelConfigBuilder(
    private val context: Context,
    private val settings: SettingsRepository,
    private val logs: LogRepository,
    /** Resolver chosen on the DNS screen, if any. Null falls back to the settings field. */
    private val selectedDns: () -> String? = { null },
    /**
     * Every server the tunnel may move onto without being restarted, the chosen one included.
     *
     * A lambda rather than the profile store itself: this class is handed to the service, which
     * has no business reaching into profiles, and the membership question belongs where the
     * failover pool is already decided.
     */
    private val switchableGroup: (ProxyNode) -> List<ProxyNode> = { listOf(it) },
) {

    private val _probe = MutableStateFlow<ProbeEndpoint?>(null)

    private val _switchable = MutableStateFlow<Set<String>>(emptySet())

    private val _dnsFallback = MutableStateFlow(false)

    /**
     * Whether the next document should carry the fallback resolver instead of the chosen one.
     *
     * Kept here rather than in settings because it is a fact about right now, not a preference:
     * it must not survive a restart. A resolver that was down an hour ago is the most likely thing
     * in the world to be up again, and a flag persisted to disk would leave every future tunnel
     * quietly using somebody else's DNS until a human noticed.
     *
     * Read at [build] time, so the switch happens on the next connection — which is what the
     * watchdog asks for immediately after setting it.
     */
    val dnsFallback: StateFlow<Boolean> = _dnsFallback.asStateFlow()

    /**
     * Forgets the loopback inbound when the tunnel carrying it goes away.
     *
     * Left standing, the port it names is closed but the endpoint is not null, so the health check
     * reads a refused connection as "the server did not answer" instead of "there is nothing to
     * ask" — and the direct measurement it is supposed to fall back on is never reached.
     */
    fun forgetProbe() {
        _probe.value = null
        // The group belongs to the same dead tunnel as the inbound. A stale one would let the
        // watchdog keep choosing from servers the next core has never been told about.
        _switchable.value = emptySet()
    }

    fun useDnsFallback(active: Boolean) {
        _dnsFallback.value = active
    }

    /**
     * How to reach the core of the tunnel this builder last configured, for the speed test. Null
     * when no loopback port could be claimed — the tunnel is worth more than the measurement, so
     * that case drops the inbound rather than the connection.
     */
    val probe: StateFlow<ProbeEndpoint?> = _probe.asStateFlow()

    /**
     * Ids of every server written into the running core's selector group.
     *
     * Recorded because deriving the same list twice does not reliably produce the same answer —
     * see [FailoverGroup.preferSwitchable] for how the two drifted apart and what it cost. This is
     * what the core was actually handed, so it is the list worth trusting.
     */
    val switchable: StateFlow<Set<String>> = _switchable.asStateFlow()

    /** Null when the configuration could not be built; the reason is already in the log. */
    fun build(node: ProxyNode): String? {
        // Extraction happens here, before the core is handed paths it will read during startup.
        // A rule-set the core cannot open fails the whole tunnel, not just one routing rule.
        val ruleSets = RuleSetStore(context)
        val ruleSetDir = ruleSets.ensureExtracted()
        val missing = ruleSets.missing()

        val effectiveSettings = if (missing.isEmpty()) {
            settings.value
        } else {
            // Route by rules without the geo sets rather than refusing to connect: plain routing
            // still works, and a tunnel that comes up beats one that will not.
            logs.warn(R.string.log_georules_missing, missing.joinToString())
            settings.value.copy(routingMode = RoutingMode.Global, blockAds = false)
        }

        val probe = newProbeEndpoint()
        val group = switchableGroup(node)

        return runCatching {
            SingBoxConfigFactory.build(
                node = node,
                settings = effectiveSettings,
                ruleSetDir = ruleSetDir.absolutePath,
                probe = probe,
                dnsOverride = selectedDns(),
                dnsFallback = _dnsFallback.value,
                group = group,
            )
        }.onSuccess {
            // Only once the document exists: publishing an endpoint for a configuration that was
            // never handed to the core would point the speed test at a port nothing listens on.
            _probe.value = probe
            // Only what the core is about to be given, and only once the document it goes into
            // exists. A group published for a configuration that was never built would send the
            // watchdog at servers no core holds.
            _switchable.value = group.mapTo(mutableSetOf()) { it.id }
            // The port, never the credentials. An inbound that fails to bind takes the whole
            // tunnel with it, and this line is the only way to tell that apart from a bad server
            // when reading a log off the device.
            probe?.let { logs.debug(R.string.log_probe_port, it.port) }
        }.getOrElse { error ->
            logs.error(R.string.log_config_failed, error.message.orEmpty())
            null
        }
    }

    /**
     * A port the kernel has just confirmed is free, rather than a constant.
     *
     * An inbound that cannot bind fails the whole tunnel, not just the speed test, and a fixed port
     * is exactly the kind of thing another VPN client on the same phone has already taken. The
     * socket is closed before the core is asked to listen, so the window for someone else to claim
     * it is the few milliseconds in between.
     */
    private fun newProbeEndpoint(): ProbeEndpoint? = runCatching {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        ProbeEndpoint(
            port = port,
            username = UUID.randomUUID().toString(),
            password = UUID.randomUUID().toString(),
        )
    }.getOrNull()
}
