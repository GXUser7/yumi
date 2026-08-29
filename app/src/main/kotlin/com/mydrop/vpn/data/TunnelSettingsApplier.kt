package com.mydrop.vpn.data

import com.mydrop.vpn.R
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hands a running tunnel a new configuration when a setting it was built from changes.
 *
 * Most of what the settings screen offers is not a preference the app consults at runtime; it is
 * an input to the JSON document the core reads once, at startup. Split tunnelling, the LAN bypass,
 * ad blocking, QUIC blocking, IPv6, the MTU and every DNS field are all in that document — and
 * changing any of them used to move a switch on screen and nothing else, until the next time the
 * user happened to reconnect. The setting was saved, honestly; it simply was not in force, and
 * nothing said so. Someone turning on ad blocking and still seeing ads has no way to tell a
 * setting that does not work from one that has not been applied.
 *
 * Only what is baked in. Everything else — the failover thresholds, the notification switches, the
 * theme — is read from the repository as the app runs and needs no reload at all.
 *
 * The wait before acting is what makes this usable rather than hostile. An MTU is typed one digit
 * at a time and a package list is built one checkbox at a time; reloading on each would tear the
 * tunnel down repeatedly while somebody is still deciding. [collectLatest] cancels the pending
 * reload whenever another change lands, so a burst of edits costs exactly one rebuild, after the
 * user has stopped.
 */
class TunnelSettingsApplier(
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val tunnel: TunnelController,
    private val launcher: TunnelLauncher,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {

    /**
     * The fields the core reads at startup, and nothing else.
     *
     * A data class rather than a list of comparisons so that adding a field to the configuration
     * and forgetting it here is a visible omission in one place, instead of a setting that quietly
     * stops working.
     */
    private data class Baked(
        val splitTunnelMode: SplitTunnelMode,
        val splitTunnelPackages: Set<String>,
        val bypassLan: Boolean,
        val blockAds: Boolean,
        val blockQuic: Boolean,
        val enableIpv6: Boolean,
        val mtu: Int,
        val remoteDns: String,
        val directDns: String,
        val hijackDns: Boolean,
    )

    private fun AppSettings.baked() = Baked(
        splitTunnelMode = splitTunnelMode,
        splitTunnelPackages = splitTunnelPackages,
        bypassLan = bypassLan,
        blockAds = blockAds,
        blockQuic = blockQuic,
        enableIpv6 = enableIpv6,
        mtu = mtu,
        remoteDns = remoteDns,
        directDns = directDns,
        hijackDns = hijackDns,
    )

    fun start() {
        scope.launch {
            var known: Baked? = null
            settings.settings
                .map { it.baked() }
                .distinctUntilChanged()
                .collectLatest { current ->
                    // The first value is whatever the app started with, not a change to it.
                    if (known == null) {
                        known = current
                        return@collectLatest
                    }
                    delay(SETTLE_MILLIS)
                    known = current

                    // Nothing to hand the configuration to. The next connection builds it from
                    // these values anyway, which is the whole reason this only matters while a
                    // tunnel is already up.
                    if (tunnel.state.value !is VpnState.Connected) return@collectLatest
                    val carrying = profiles.selectedNode() ?: return@collectLatest

                    logs.info(R.string.log_settings_reapplied)
                    launcher.switchTo(carrying, reloadConfig = true)
                }
        }
    }

    private companion object {
        /**
         * Long enough to cover somebody typing an MTU or ticking their way down a list of
         * applications, short enough that a single toggle feels like it took effect immediately.
         */
        const val SETTLE_MILLIS = 1_500L
    }
}
