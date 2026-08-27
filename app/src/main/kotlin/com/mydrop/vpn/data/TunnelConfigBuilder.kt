package com.mydrop.vpn.data

import android.content.Context
import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.xray.XrayConfigFactory
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
) {

    private val _probe = MutableStateFlow<ProbeEndpoint?>(null)

    /**
     * How to reach the core of the tunnel this builder last configured, for the speed test. Null
     * when no loopback port could be claimed — the tunnel is worth more than the measurement, so
     * that case drops the inbound rather than the connection.
     */
    val probe: StateFlow<ProbeEndpoint?> = _probe.asStateFlow()

    /** Null when the configuration could not be built; the reason is already in the log. */
    fun build(node: ProxyNode): String? {
        // Asked here, before the core is handed a document that may name them. A `geoip:` or
        // `geosite:` reference Xray cannot resolve rejects the whole configuration rather than the
        // one rule, so the databases decide what may be written, not what may be ignored.
        val missing = GeoAssetStore(context).missing()
        if (missing.isNotEmpty()) {
            // Routing by rules becomes routing everything through the proxy until the download
            // finishes. A tunnel that comes up beats one that will not.
            logs.warn(R.string.log_georules_missing, missing.joinToString())
        }

        val probe = newProbeEndpoint()

        return runCatching {
            XrayConfigFactory.build(
                node = node,
                settings = settings.value,
                probe = probe,
                dnsOverride = selectedDns(),
                geoAvailable = missing.isEmpty(),
            )
        }.onSuccess {
            // Only once the document exists: publishing an endpoint for a configuration that was
            // never handed to the core would point the speed test at a port nothing listens on.
            _probe.value = probe
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
