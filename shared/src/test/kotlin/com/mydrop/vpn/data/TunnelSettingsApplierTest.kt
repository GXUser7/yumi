package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.CustomRules
import com.mydrop.vpn.core.model.FragmentMode
import com.mydrop.vpn.core.model.FragmentScope
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.SplitTunnelRules
import com.mydrop.vpn.core.model.TlsFingerprintOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSettingsApplierTest {

    private fun baked(
        autoFailover: Boolean = false,
        fragmentMode: FragmentMode = FragmentMode.Off,
        customRules: CustomRules = CustomRules(),
        resolveServerAddress: Boolean = false,
        resolveDohUrl: String = AppSettings().resolveDohUrl,
    ): TunnelSettingsApplier.Baked {
        val defaults = AppSettings()
        return TunnelSettingsApplier.Baked(
            splitTunnelRules = SplitTunnelRules(SplitTunnelMode.Off, emptyMap()),
            bypassLan = defaults.bypassLan,
            blockAds = defaults.blockAds,
            blockQuic = defaults.blockQuic,
            enableIpv6 = defaults.enableIpv6,
            mtu = defaults.mtu,
            remoteDns = defaults.remoteDns,
            directDns = defaults.directDns,
            hijackDns = defaults.hijackDns,
            resolveServerAddress = resolveServerAddress,
            resolveDohUrl = resolveDohUrl,
            providerRouting = defaults.providerRouting,
            customRules = customRules,
            tlsFingerprint = TlsFingerprintOverride.Auto,
            fragmentMode = fragmentMode,
            fragmentScope = FragmentScope.TlsAndReality,
            fragmentLength = defaults.fragmentLength,
            fragmentInterval = defaults.fragmentInterval,
            fragmentMaxChunks = defaults.fragmentMaxChunks,
            autoFailover = autoFailover,
        )
    }

    @Test
    fun `enabling autoFailover reloads tunnel so core receives switchable group`() {
        val before = baked(autoFailover = false)
        val after = baked(autoFailover = true)
        assertTrue(TunnelSettingsApplier.shouldReload(before, after))
    }

    @Test
    fun `disabling autoFailover alone does not reload running tunnel`() {
        val before = baked(autoFailover = true)
        val after = baked(autoFailover = false)
        assertFalse(TunnelSettingsApplier.shouldReload(before, after))
    }

    @Test
    fun `disabling autoFailover alongside another baked setting change still reloads`() {
        val before = baked(autoFailover = true, fragmentMode = FragmentMode.Off)
        val after = baked(autoFailover = false, fragmentMode = FragmentMode.TlsHello)
        assertTrue(TunnelSettingsApplier.shouldReload(before, after))
    }

    @Test
    fun `identical baked snapshots never reload`() {
        val snap = baked(autoFailover = false)
        assertFalse(TunnelSettingsApplier.shouldReload(snap, snap))
    }

    @Test
    fun `changing customRules or DoH server resolution settings reloads tunnel`() {
        val base = baked()
        assertTrue(
            TunnelSettingsApplier.shouldReload(
                base,
                baked(customRules = CustomRules(proxy = listOf("domain:example.com"))),
            ),
        )
        assertTrue(
            TunnelSettingsApplier.shouldReload(
                base,
                baked(resolveServerAddress = true),
            ),
        )
        assertTrue(
            TunnelSettingsApplier.shouldReload(
                base,
                baked(resolveDohUrl = "https://8.8.8.8/dns-query"),
            ),
        )
    }
}
