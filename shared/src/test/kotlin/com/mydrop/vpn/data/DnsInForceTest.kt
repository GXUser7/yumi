package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.parse.ProviderFixtures
import com.mydrop.vpn.core.parse.ProviderProfileParser
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.TunnelConfigBuilder.DnsInForce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which resolver the watchdog checks, and which one it names when the check fails.
 *
 * It used to judge the user's resolver while the core was querying the provider's. With the
 * provider's routing on and the user's resolver left at the default, a dead provider resolver was
 * never checked at all. With a resolver of the user's own, the provider's outage was reported
 * under the user's resolver's name.
 */
class DnsInForceTest {

    private val server = ProxyNode(
        id = "n1",
        name = "Server",
        server = "203.0.113.10",
        port = 443,
        settings = ProxySettings.Vless(uuid = ProviderFixtures.UUID),
    )

    private val probe = ProbeEndpoint(port = 10808, username = "probe", password = "probe-password")

    /** Quattro's: `8.8.8.8` then `1.1.1.1`, plain UDP through the server. */
    private val quattro: ProviderProfile =
        ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single()

    /** What [TunnelConfigBuilder.build] records for the document it hands the core. */
    private fun inForce(
        settings: AppSettings,
        provider: ProviderProfile? = null,
        dnsFallback: Boolean = false,
    ): DnsInForce {
        val document = XrayConfigFactory.build(
            nodes = listOf(server),
            selected = server,
            settings = settings,
            probe = probe,
            provider = provider,
            dnsFallback = dnsFallback,
        )
        return DnsInForce(
            resolver = document.resolver,
            provider = document.providerDns,
            fallback = dnsFallback,
            mode = settings.routingMode,
        )
    }

    @Test
    fun `the shipped default is left alone`() {
        assertFalse(inForce(AppSettings()).policed)
        assertFalse(inForce(AppSettings(routingMode = RoutingMode.Direct)).policed)
    }

    @Test
    fun `a resolver the user chose is checked`() {
        assertTrue(inForce(AppSettings(remoteDns = "https://dns.example.com/dns-query")).policed)
        assertTrue(inForce(AppSettings(routingMode = RoutingMode.Direct, directDns = "77.88.8.8")).policed)
    }

    @Test
    fun `the provider's resolver is checked whatever the user's own resolver is`() {
        val users = listOf(
            AppSettings(),
            AppSettings(remoteDns = "https://dns.example.com/dns-query"),
            AppSettings(routingMode = RoutingMode.Global),
        )
        for (settings in users) {
            val dns = inForce(settings, quattro)

            assertTrue("$settings", dns.provider)
            assertTrue("$settings", dns.policed)
            assertEquals("$settings", "8.8.8.8", dns.resolver)
        }
    }

    @Test
    fun `a provider's resolver counts as chosen even when it is spelled like the shipped one`() {
        val dns = DnsInForce(
            resolver = XrayConfigFactory.DEFAULT_REMOTE_DNS,
            provider = true,
            fallback = false,
            mode = RoutingMode.Rules,
        )

        assertTrue(dns.policed)
    }

    /**
     * The whole path the watchdog takes once the provider's resolver has failed twice. The user's
     * own resolver is the shipped one, the case that was never checked.
     */
    @Test
    fun `a dead provider resolver is named, replaced and the replacement kept under watch`() {
        val running = inForce(AppSettings(), quattro)
        assertTrue(running.policed)

        // What the journal and the notification say: the provider's resolver, not the user's.
        val dead = running.resolver
        val replacement = XrayConfigFactory.fallbackResolver(dead)
        assertEquals("8.8.8.8", dead)
        assertEquals(XrayConfigFactory.FALLBACK_REMOTE_DNS, replacement)

        val rebuilt = inForce(AppSettings(), quattro, dnsFallback = true)

        assertFalse(rebuilt.provider)
        assertTrue(rebuilt.fallback)
        assertEquals(replacement, rebuilt.resolver)
        // Same string as the shipped default, and still checked: it is there because the
        // resolver before it failed.
        assertEquals(XrayConfigFactory.DEFAULT_REMOTE_DNS, rebuilt.resolver)
        assertTrue(rebuilt.policed)
    }

    @Test
    fun `in direct mode the provider's routing changes nothing`() {
        val dns = inForce(AppSettings(routingMode = RoutingMode.Direct), quattro)

        assertFalse(dns.provider)
        assertFalse(dns.policed)
    }
}
