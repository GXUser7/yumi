package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.parse.ProxyUriParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigFactoryDnsStressTest {

    private fun config(
        uri: String,
        settings: AppSettings = AppSettings(),
        probe: ProbeEndpoint? = null,
        dnsOverride: String? = null,
        dnsFallback: Boolean = false,
    ): JsonObject {
        val node = requireNotNull(ProxyUriParser.parse(uri))
        val raw = SingBoxConfigFactory.build(
            node = node,
            settings = settings,
            ruleSetDir = "/rule-sets",
            probe = probe,
            dnsOverride = dnsOverride,
            dnsFallback = dnsFallback,
        )
        return Json.parseToJsonElement(raw).jsonObject
    }

    private fun dnsServers(cfg: JsonObject): List<JsonObject> =
        cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

    private fun defaultDomainResolver(cfg: JsonObject): String =
        cfg["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content

    // =========================================================================
    // 1. Direct DNS Variants: Local, Empty, Blank, and Case Insensitivity
    // =========================================================================

    @Test
    fun `explicit local direct dns variants still produce a clean local dns server`() {
        // Only the literal marker, typed by hand — not the values that ARRIVE at "clean up
        // after an empty field", which is the next test and now asserts the opposite. See
        // SingBoxConfigFactory.DEFAULT_DIRECT_DNS for why "local" is opt-in rather than a
        // fallback: nothing implements the Android bridge it needs.
        val variations = listOf("local", "LOCAL", "Local", "local://")
        for (v in variations) {
            val cfg = config(
                "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
                AppSettings(directDns = v),
            )
            val servers = dnsServers(cfg)
            val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
            assertEquals("Variation '$v' must emit type local", "local", direct["type"]!!.jsonPrimitive.content)
            assertNull("Variation '$v' must not have server field", direct["server"])
            assertNull("Variation '$v' must not have server_port field", direct["server_port"])
            assertNull("Variation '$v' must not have detour field", direct["detour"])
            assertNull("Variation '$v' must not have domain_resolver field", direct["domain_resolver"])

            // Local needs no bootstrap server
            assertTrue(
                "Variation '$v' must not emit dns-bootstrap",
                servers.none { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" },
            )
            assertEquals("Variation '$v' must use dns-direct as default_domain_resolver", "dns-direct", defaultDomainResolver(cfg))
        }
    }

    /**
     * An emptied or blank field must fall back to a resolver that actually answers on this
     * platform, not to "local". Checked on a real device: nothing implements
     * `PlatformInterface.localDNSTransport()` (it returns null), Android has no
     * `/etc/resolv.conf`, and sing-box's own fallback for that is `127.0.0.1:53` — which nothing
     * answers. Defaulting an emptied field to "local" would mean every fresh install with a
     * domain-named proxy server fails to connect at all, silently.
     */
    @Test
    fun `an emptied or blank direct dns field falls back to a working resolver, not local`() {
        val variations = listOf("", "   ", "\t\n  ")
        for (v in variations) {
            val cfg = config(
                "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
                AppSettings(directDns = v),
            )
            val servers = dnsServers(cfg)
            val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
            assertEquals("Variation '$v' must emit type udp", "udp", direct["type"]!!.jsonPrimitive.content)
            assertEquals(
                "Variation '$v' must fall back to the numeric default",
                SingBoxConfigFactory.DEFAULT_DIRECT_DNS,
                direct["server"]!!.jsonPrimitive.content,
            )
            assertTrue(
                "Variation '$v' must not emit dns-bootstrap",
                servers.none { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" },
            )
            assertEquals("Variation '$v' must use dns-direct as default_domain_resolver", "dns-direct", defaultDomainResolver(cfg))
        }
    }

    // =========================================================================
    // 2. Direct DNS Variants: Custom DoH (HTTPS)
    // =========================================================================

    @Test
    fun `custom numeric DoH direct DNS requires no bootstrap`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "https://1.1.1.1/dns-query"),
        )
        val servers = dnsServers(cfg)
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", direct["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", direct["path"]!!.jsonPrimitive.content)
        assertNull(direct["domain_resolver"])
        assertNull(direct["detour"])
        assertTrue(servers.none { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" })
        assertEquals("dns-direct", defaultDomainResolver(cfg))
    }

    @Test
    fun `custom domain-named DoH direct DNS emits local bootstrap and points domain resolver to it`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "https://dns.google/dns-query"),
        )
        val servers = dnsServers(cfg)
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
        assertEquals("dns.google", direct["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", direct["path"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)
        assertNull(direct["detour"])

        // Not "local" — see DEFAULT_DIRECT_DNS. The bootstrap has to reach somewhere real.
        val bootstrap = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" }
        assertEquals("udp", bootstrap["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfigFactory.DEFAULT_DIRECT_DNS, bootstrap["server"]!!.jsonPrimitive.content)
        assertNull(bootstrap["detour"])

        assertEquals("dns-bootstrap", defaultDomainResolver(cfg))
    }

    @Test
    fun `custom domain-named DoH direct DNS with custom port parses correctly`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "https://custom.dns.org:8443/query"),
        )
        val servers = dnsServers(cfg)
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
        assertEquals("custom.dns.org", direct["server"]!!.jsonPrimitive.content)
        assertEquals(8443, direct["server_port"]!!.jsonPrimitive.int)
        assertEquals("/query", direct["path"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // 3. Direct DNS Variants: Custom DoT (TLS)
    // =========================================================================

    @Test
    fun `custom numeric DoT direct DNS requires no bootstrap`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "tls://1.0.0.1:853"),
        )
        val servers = dnsServers(cfg)
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("tls", direct["type"]!!.jsonPrimitive.content)
        assertEquals("1.0.0.1", direct["server"]!!.jsonPrimitive.content)
        assertEquals(853, direct["server_port"]!!.jsonPrimitive.int)
        assertNull(direct["domain_resolver"])
        assertNull(direct["path"])
        assertEquals("dns-direct", defaultDomainResolver(cfg))
    }

    @Test
    fun `custom domain-named DoT direct DNS emits local bootstrap`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "tls://dns.adguard-dns.com:853"),
        )
        val servers = dnsServers(cfg)
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("tls", direct["type"]!!.jsonPrimitive.content)
        assertEquals("dns.adguard-dns.com", direct["server"]!!.jsonPrimitive.content)
        assertEquals(853, direct["server_port"]!!.jsonPrimitive.int)
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)

        // Not "local" — see DEFAULT_DIRECT_DNS. The bootstrap has to reach somewhere real.
        val bootstrap = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" }
        assertEquals("udp", bootstrap["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfigFactory.DEFAULT_DIRECT_DNS, bootstrap["server"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", defaultDomainResolver(cfg))
    }

    // =========================================================================
    // 4. Direct DNS Variants: Raw IP (UDP, TCP, IPv4, IPv6)
    // =========================================================================

    @Test
    fun `raw IPv4 and IPv6 direct DNS addresses parse cleanly`() {
        val cases = listOf(
            "8.8.4.4" to ("udp" to "8.8.4.4"),
            "8.8.8.8:5353" to ("udp" to "8.8.8.8"),
            "tcp://9.9.9.9" to ("tcp" to "9.9.9.9"),
            "tcp://9.9.9.9:53" to ("tcp" to "9.9.9.9"),
            "2001:4860:4860::8888" to ("udp" to "2001:4860:4860::8888"),
            "[2606:4700:4700::1111]:5353" to ("udp" to "2606:4700:4700::1111"),
        )
        for ((input, expected) in cases) {
            val (expectedType, expectedHost) = expected
            val cfg = config(
                "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
                AppSettings(directDns = input),
            )
            val servers = dnsServers(cfg)
            val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
            assertEquals("Type for '$input'", expectedType, direct["type"]!!.jsonPrimitive.content)
            assertEquals("Host for '$input'", expectedHost, direct["server"]!!.jsonPrimitive.content)
            assertNull("No domain resolver for '$input'", direct["domain_resolver"])
            assertTrue("No bootstrap for '$input'", servers.none { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" })
            assertEquals("Default domain resolver for '$input'", "dns-direct", defaultDomainResolver(cfg))
        }
    }

    // =========================================================================
    // 5. Direct DNS Variants: QUIC and H3
    // =========================================================================

    @Test
    fun `custom QUIC and H3 direct DNS configurations emit expected types`() {
        val quicCfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "quic://dns.adguard-dns.com"),
        )
        val quicDirect = dnsServers(quicCfg).first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("quic", quicDirect["type"]!!.jsonPrimitive.content)
        assertEquals("dns.adguard-dns.com", quicDirect["server"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", quicDirect["domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", defaultDomainResolver(quicCfg))

        val h3Cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(directDns = "h3://dns.google/dns-query"),
        )
        val h3Direct = dnsServers(h3Cfg).first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("h3", h3Direct["type"]!!.jsonPrimitive.content)
        assertEquals("dns.google", h3Direct["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", h3Direct["path"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", h3Direct["domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", defaultDomainResolver(h3Cfg))
    }

    // =========================================================================
    // 6. Reachable, Non-Circular Domain Resolver Invariant Verification
    // =========================================================================

    @Test
    fun `default_domain_resolver is ALWAYS direct or local bootstrap across all combinations`() {
        val nodes = listOf(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#DomainNode",
            "vless://uuid@198.51.100.1:443?security=reality&pbk=KEY&sid=1234#IpNode",
        )
        val remoteDnsList = listOf(
            "https://1.1.1.1/dns-query",
            "https://xbox-dns.ru/dns-query",
            "8.8.8.8",
            "tls://dns.google",
        )
        val directDnsList = listOf(
            "local",
            "",
            "8.8.8.8",
            "https://1.1.1.1/dns-query",
            "https://custom-dns.org/dns-query",
            "tls://dns.adguard.com",
        )
        val modes = listOf(RoutingMode.Rules, RoutingMode.Global, RoutingMode.Direct)

        for (nodeUri in nodes) {
            for (remote in remoteDnsList) {
                for (direct in directDnsList) {
                    for (mode in modes) {
                        val cfg = config(
                            nodeUri,
                            AppSettings(remoteDns = remote, directDns = direct, routingMode = mode),
                        )
                        val resolverTag = defaultDomainResolver(cfg)
                        val servers = dnsServers(cfg)
                        val resolverObj = servers.firstOrNull { it["tag"]!!.jsonPrimitive.content == resolverTag }

                        assertNotNull("Resolver '$resolverTag' must exist in dns.servers", resolverObj)
                        // Invariant 1: default_domain_resolver must NEVER detour through proxy
                        assertNull(
                            "Resolver '$resolverTag' must NOT detour through proxy (would cause deadlock)",
                            resolverObj!!["detour"],
                        )
                        // Invariant 2: resolver must be either local or numeric server
                        val isLocal = resolverObj["type"]?.jsonPrimitive?.content == "local"
                        val isNumeric = resolverObj["server"]?.jsonPrimitive?.content?.firstOrNull()?.isDigit() == true
                        assertTrue(
                            "Resolver '$resolverTag' must be local or numeric (got $resolverObj)",
                            isLocal || isNumeric,
                        )
                        // Invariant 3: default_domain_resolver must NOT be dns-remote or dns-bootstrap-proxy
                        assertTrue(
                            "Resolver '$resolverTag' cannot be remote or proxied bootstrap",
                            resolverTag == "dns-direct" || resolverTag == "dns-bootstrap",
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 7. RoutingMode.Direct (DNS Filter Only, No VPN Proxy) Stress Test
    // =========================================================================

    @Test
    fun `direct routing mode maintains valid DNS routing and server configs`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(routingMode = RoutingMode.Direct, directDns = "local"),
        )
        // 1. route.final is direct
        assertEquals("direct", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        // 2. dns.final is dns-direct
        assertEquals("dns-direct", cfg["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        // 3. dns-direct is local
        val direct = dnsServers(cfg).first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("local", direct["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct routing mode applies dns override to directDns and not remoteDns`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(routingMode = RoutingMode.Direct, directDns = "local"),
            dnsOverride = "https://dns.adguard.com/dns-query",
        )
        val direct = dnsServers(cfg).first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
        assertEquals("dns.adguard.com", direct["server"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)
        assertNull(direct["detour"])

        // Remote DNS remains untouched default
        val remote = dnsServers(cfg).first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("1.1.1.1", remote["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct routing mode applies fallback to directDns`() {
        val cfg = config(
            "vless://uuid@de-01.vless.monster:443?security=reality&pbk=KEY&sid=1234#Monster",
            AppSettings(routingMode = RoutingMode.Direct, directDns = "https://1.1.1.1/dns-query"),
            dnsFallback = true,
        )
        val direct = dnsServers(cfg).first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        // Fallback from 1.1.1.1 should step aside to 8.8.8.8
        assertEquals("8.8.8.8", direct["server"]!!.jsonPrimitive.content)
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
    }
}
