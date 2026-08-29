package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.parse.ProxyUriParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigFactoryTest {

    /**
     * The rule is only worth having if it is narrow: a reject on all UDP would take DNS over
     * QUIC and WireGuard down with it, which is a bigger outage than the one it is fixing.
     */
    @Test
    fun `blocking QUIC rejects only 443 and only when asked`() {
        // Scoped to QUIC rules: the tunnel also refuses IPv6 by default, which is a reject rule
        // of its own and has nothing to do with this setting.
        val off = config("vless://uuid@se.example.com:443?security=tls#N").routeRules
        // `as?` rather than `.jsonArray`: the DNS hijack rule carries `protocol` as a bare string.
        assertTrue(off.none { (it["protocol"] as? JsonArray)?.any { p -> p.jsonPrimitive.content == "quic" } == true })

        val on = config(
            "vless://uuid@se.example.com:443?security=tls#N",
            AppSettings(blockQuic = true),
        ).routeRules
        // The IPv6 refusal is a reject rule too, and it comes first — pick by what it matches.
        val rule = on.first {
            (it["protocol"] as? JsonArray)?.any { p -> p.jsonPrimitive.content == "quic" } == true
        }
        assertEquals("reject", rule["action"]!!.jsonPrimitive.content)
        assertEquals("quic", rule["protocol"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(443, rule["port"]!!.jsonArray.single().jsonPrimitive.int)
    }

    private fun config(uri: String, settings: AppSettings = AppSettings()): JsonObject {
        val node = requireNotNull(ProxyUriParser.parse(uri))
        return Json.parseToJsonElement(SingBoxConfigFactory.build(node, settings, "/rule-sets")).jsonObject
    }

    private val JsonObject.outbounds: JsonArray get() = this["outbounds"]!!.jsonArray
    private val JsonObject.proxy: JsonObject
        get() = outbounds.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }

    private val JsonObject.routeRules: List<JsonObject>
        get() = this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    /**
     * The server list can now flip this per node, so the flag has to survive the whole way into
     * the document — a toggle that changes nothing in the config would be worse than no toggle.
     */
    @Test
    fun `insecure reaches the outbound only when it is set`() {
        val skipping = config("hysteria2://pass@se.example.com:443?insecure=1#N").proxy
        assertTrue(skipping["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.booleanOrNull == true)

        // Absent rather than `false`: sing-box defaults to verifying, and writing the default
        // would make a diff of two configurations noisier than the difference between them.
        val verifying = config("hysteria2://pass@se.example.com:443#N").proxy
        assertNull(verifying["tls"]!!.jsonObject["insecure"])
    }

    @Test
    fun `vless reality outbound carries uuid flow and reality keys`() {
        val proxy = config(
            "vless://uuid-1@de.example.com:443?security=reality&sni=www.microsoft.com" +
                "&fp=chrome&pbk=PUBKEY&sid=abcd&type=tcp&flow=xtls-rprx-vision#N",
        ).proxy

        assertEquals("vless", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("de.example.com", proxy["server"]!!.jsonPrimitive.content)
        assertEquals(443, proxy["server_port"]!!.jsonPrimitive.int)
        assertEquals("uuid-1", proxy["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", proxy["flow"]!!.jsonPrimitive.content)

        val tls = proxy["tls"]!!.jsonObject
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)

        val reality = tls["reality"]!!.jsonObject
        assertEquals("PUBKEY", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("abcd", reality["short_id"]!!.jsonPrimitive.content)

        // type=tcp carries no v2ray transport.
        assertNull(proxy["transport"])
    }

    @Test
    fun `reality without an explicit fingerprint still gets utls`() {
        val tls = config("vless://u@a.example.com:443?security=reality&pbk=K&sid=00#N")
            .proxy["tls"]!!.jsonObject
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown fingerprint falls back to a supported one`() {
        val tls = config("vless://u@a.example.com:443?security=tls&fp=nonsense#N")
            .proxy["tls"]!!.jsonObject
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `websocket transport is emitted with path and host header`() {
        val transport = config(
            "vless://u@a.example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#N",
        ).proxy["transport"]!!.jsonObject

        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", transport["path"]!!.jsonPrimitive.content)
        assertEquals(
            "cdn.example.com",
            transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `hysteria2 obfs becomes a nested object`() {
        val proxy = config(
            "hysteria2://pw@a.example.com:443?obfs=salamander&obfs-password=op#N",
        ).proxy

        assertEquals("hysteria2", proxy["type"]!!.jsonPrimitive.content)
        val obfs = proxy["obfs"]!!.jsonObject
        assertEquals("salamander", obfs["type"]!!.jsonPrimitive.content)
        assertEquals("op", obfs["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tuic splits credentials into uuid and password`() {
        val proxy = config("tuic://uu:pp@a.example.com:443?congestion_control=bbr#N").proxy
        assertEquals("tuic", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("uu", proxy["uuid"]!!.jsonPrimitive.content)
        assertEquals("pp", proxy["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sniff runs before every other route rule`() {
        val rules = config("vless://u@a.example.com:443?security=tls#N").routeRules
        assertEquals("sniff", rules.first()["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct mode sends the final route and dns to direct`() {
        val cfg = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(routingMode = RoutingMode.Direct),
        )
        assertEquals("direct", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", cfg["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `global mode adds no domain bypass rule`() {
        val rules = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(routingMode = RoutingMode.Global, bypassLan = false),
        ).routeRules
        assertTrue(rules.none { it.containsKey("domain_suffix") })
    }

    @Test
    fun `split tunnel allow list becomes include_package`() {
        val cfg = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(
                splitTunnelMode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf("com.example.b", "com.example.a"),
            ),
        )
        val tun = cfg["inbounds"]!!.jsonArray.first().jsonObject
        val included = tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("com.example.a", "com.example.b"), included)
        assertNull(tun["exclude_package"])
    }

    @Test
    fun `split tunnel block list becomes exclude_package`() {
        val tun = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(
                splitTunnelMode = SplitTunnelMode.BlockList,
                splitTunnelPackages = setOf("com.example.a"),
            ),
        )["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals(
            listOf("com.example.a"),
            tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(tun["include_package"])
    }

    @Test
    fun `doh dns url becomes a typed https server`() {
        val servers = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(remoteDns = "https://1.1.1.1/dns-query", directDns = "8.8.8.8"),
        )["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("https", remote["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", remote["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", remote["path"]!!.jsonPrimitive.content)
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)

        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("udp", direct["type"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", direct["server"]!!.jsonPrimitive.content)
        assertNull(direct["detour"])
    }

    @Test
    fun `an emptied dns field falls back instead of emitting a blank address`() {
        // A cleared field used to persist as "" and reach the core as `"server": ""`, which
        // sing-box rejects with "invalid server address: :53" — the tunnel then died the instant
        // it started, and the reason was only visible in an in-memory log.
        val servers = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(remoteDns = "", directDns = "   "),
        )["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        servers.forEach { server ->
            val address = server["server"]!!.jsonPrimitive.content
            assertTrue("empty address for ${server["tag"]}", address.isNotBlank())
        }

        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("1.1.1.1", remote["server"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", servers.first {
            it["tag"]!!.jsonPrimitive.content == "dns-direct"
        }["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ipv6 disabled restricts the dns strategy to ipv4`() {
        val dns = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(enableIpv6 = false),
        )["dns"]!!.jsonObject
        assertEquals("ipv4_only", dns["strategy"]!!.jsonPrimitive.content)

    }

    /**
     * Switching IPv6 off has to mean "none of it leaves the phone", not "it is somebody else's".
     *
     * A VpnService captures only the routes it is handed, and a route can only be handed over for
     * a family the interface has an address in. With no v6 address on the tunnel there was no
     * `::/0` route, and Android went on using the *physical* interface as the default gateway for
     * every IPv6 packet — the user's real address and their traffic in the clear, underneath an
     * app that said it was connected.
     *
     * So the address is always there and the traffic is always captured; the setting decides what
     * happens to it afterwards.
     */
    @Test
    fun `ipv6 is captured and refused rather than left to leak`() {
        val off = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(enableIpv6 = false),
        )
        val addresses = off["inbounds"]!!.jsonArray.first().jsonObject["address"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("the tunnel needs a v6 address to be given a v6 route", addresses.size == 2)
        assertTrue(addresses.any { it.contains(':') })

        // Refused, not dropped: an immediate answer sends the browser to the A record at once,
        // where silence would make it wait out a timeout on every dual-stack name.
        val refusal = off.routeRules.first {
            it["ip_cidr"]?.jsonArray?.any { c -> c.jsonPrimitive.content == "::/0" } == true
        }
        assertEquals("reject", refusal["action"]!!.jsonPrimitive.content)

        // And with IPv6 wanted, nothing refuses it.
        val on = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(enableIpv6 = true),
        )
        assertTrue(
            on.routeRules.none {
                it["ip_cidr"]?.jsonArray?.any { c -> c.jsonPrimitive.content == "::/0" } == true
            },
        )
    }

    @Test
    fun `hijack dns can be turned off entirely`() {
        val cfg = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(hijackDns = false),
        )
        assertTrue(cfg.routeRules.none { it["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertNull(cfg["inbounds"]!!.jsonArray.first().jsonObject["dns_mode"])
    }

    @Test
    fun `a direct outbound always exists alongside the proxy`() {
        val tags = config("vless://u@a.example.com:443?security=tls#N")
            .outbounds.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.containsAll(listOf("proxy", "direct")))
    }

    @Test
    fun `shadowsocks method and password survive the round trip`() {
        val proxy = config(
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@a.example.com:8388#N",
        ).proxy
        assertEquals("shadowsocks", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("aes-256-gcm", proxy["method"]!!.jsonPrimitive.content)
        assertEquals("secret", proxy["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tun inbound reflects the configured mtu`() {
        val tun = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(mtu = 1500),
        )["inbounds"]!!.jsonArray.first().jsonObject

        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals(1500, tun["mtu"]!!.jsonPrimitive.int)
        assertEquals(true, tun["auto_route"]!!.jsonPrimitive.booleanOrNull)
        assertNotNull(tun["stack"])
    }

    @Test
    fun `without a probe the tunnel listens on nothing but the tun`() {
        val inbounds = config("vless://u@a.example.com:443?security=tls#N")["inbounds"]!!.jsonArray

        assertEquals(1, inbounds.size)
    }

    @Test
    fun `the probe inbound is loopback only and behind credentials`() {
        val node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N"))
        val config = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                node,
                AppSettings(),
                "/rule-sets",
                ProbeEndpoint(port = 41234, username = "user", password = "secret"),
            ),
        ).jsonObject

        val probe = config["inbounds"]!!.jsonArray[1].jsonObject
        assertEquals("mixed", probe["type"]!!.jsonPrimitive.content)
        // Bound to anything else this would be an open proxy for the whole network the phone is
        // on, and the credentials are what close the same hole for other apps on the device.
        assertEquals("127.0.0.1", probe["listen"]!!.jsonPrimitive.content)
        assertEquals(41234, probe["listen_port"]!!.jsonPrimitive.int)
        val user = probe["users"]!!.jsonArray.single().jsonObject
        assertEquals("user", user["username"]!!.jsonPrimitive.content)
        assertEquals("secret", user["password"]!!.jsonPrimitive.content)

        // And it always reaches the server: measured over a bypass rule, the phone's own
        // connection would be reported as the server's throughput.
        val probeRules = config.routeRules.filter { it["inbound"] != null }
        val probeRule = probeRules.first { it["outbound"] != null }
        assertEquals("probe-in", probeRule["inbound"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("proxy", probeRule["outbound"]!!.jsonPrimitive.content)
    }

    /**
     * The one destination in the whole document that is resolved here rather than at the far end.
     *
     * Everything else hands its hostname to the outbound, which is why a dead resolver leaves no
     * mark on any probe. This rule is what makes one connection depend on the DNS pipeline, so
     * that the watchdog has a question whose answer is about DNS alone — and it has to be matched
     * before the rule that routes, because routing is a final action and stops the matching.
     */
    @Test
    fun `the dns probe is resolved locally, and before the probe is routed`() {
        val config = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N")),
                AppSettings(),
                "/rule-sets",
                ProbeEndpoint(port = 41234, username = "user", password = "secret"),
            ),
        ).jsonObject

        val rules = config.routeRules
        val resolveAt = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "resolve" }
        val routeAt = rules.indexOfFirst {
            it["inbound"] != null && it["outbound"]?.jsonPrimitive?.content == "proxy"
        }
        assertTrue(resolveAt >= 0)
        assertTrue("the resolve rule must be reached first", resolveAt < routeAt)

        val resolve = rules[resolveAt]
        assertEquals("probe-in", resolve["inbound"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            ProbeTargets.DNS,
            resolve["domain"]!!.jsonArray.single().jsonPrimitive.content,
        )
        // Both caches off, or the check answers itself: the name's TTL outlives the probe
        // interval more than tenfold, so a cached hit means the resolver was never asked and a
        // dead one would keep testing healthy until the entry expired.
        assertEquals(true, resolve["disable_cache"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, resolve["disable_optimistic_cache"]!!.jsonPrimitive.content.toBoolean())

        // And the tunnel probe is deliberately NOT in it: it has to keep passing while DNS is
        // dead, or a broken resolver would send the watchdog hunting through every server.
        assertTrue(rules.none { rule ->
            rule["domain"]?.jsonArray?.any { it.jsonPrimitive.content == ProbeTargets.TUNNEL } == true
        })
    }

    /**
     * The lookup that finds a named resolver goes through the tunnel, not out of the phone.
     *
     * It used to be plain UDP on port 53, direct — the exact traffic Russian DPI rewrites. That
     * made a healthy resolver unreachable whenever the question "what is its address" was
     * interfered with, and every name on the phone stopped resolving at once, for a reason nobody
     * would think to look for.
     */
    @Test
    fun `a named remote resolver is bootstrapped through the proxy`() {
        val servers = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N")),
                settings = AppSettings(),
                ruleSetDir = "/rule-sets",
                dnsOverride = "https://xbox-dns.ru/dns-query",
            ),
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("dns-bootstrap-proxy", remote["domain_resolver"]!!.jsonPrimitive.content)

        val bootstrap = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap-proxy" }
        assertEquals("proxy", bootstrap["detour"]!!.jsonPrimitive.content)
        // Numeric, or it would need the very thing it exists to find.
        assertTrue(bootstrap["server"]!!.jsonPrimitive.content.first().isDigit())

        // And no plain UDP resolver was added alongside it: the direct one here is numeric, so
        // nothing else needs bootstrapping and nothing else leaves on port 53.
        assertTrue(servers.none { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" })
    }

    /** The fallback replaces the resolver the tunnel actually queries, and only that one. */
    @Test
    fun `the dns fallback swaps the resolver the routing mode queries`() {
        fun remoteOf(mode: RoutingMode) = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N")),
                settings = AppSettings(routingMode = mode),
                ruleSetDir = "/rule-sets",
                dnsOverride = "https://xbox-dns.ru/dns-query",
                dnsFallback = true,
            ),
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        val proxied = remoteOf(RoutingMode.Rules)
            .first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("1.1.1.1", proxied["server"]!!.jsonPrimitive.content)
        // Numeric now, so the bootstrap it needed is gone with it.
        assertNull(proxied["domain_resolver"])

        // Direct mode queries dns-direct, so that is the one that has to change.
        val direct = remoteOf(RoutingMode.Direct)
            .first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("1.1.1.1", direct["server"]!!.jsonPrimitive.content)
    }

    /** Falling back from 1.1.1.1 has to land somewhere other than 1.1.1.1. */
    @Test
    fun `the fallback steps aside when it is already the chosen resolver`() {
        val remote = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N")),
                settings = AppSettings(remoteDns = "https://1.1.1.1/dns-query"),
                ruleSetDir = "/rule-sets",
                dnsFallback = true,
            ),
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }

        assertEquals("8.8.8.8", remote["server"]!!.jsonPrimitive.content)
    }



    @Test
    fun `the chosen resolver lands on the server the routing mode actually queries`() {
        val node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N"))

        fun servers(mode: RoutingMode) = Json
            .parseToJsonElement(
                SingBoxConfigFactory.build(
                    node = node,
                    settings = AppSettings(routingMode = mode),
                    ruleSetDir = "/rule-sets",
                    dnsOverride = "https://xbox-dns.ru/dns-query",
                ),
            ).jsonObject["dns"]!!.jsonObject

        // Through a proxy the queries go out over it, so the override belongs to the remote server.
        val proxied = servers(RoutingMode.Rules)
        val remote = proxied["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("xbox-dns.ru", remote["server"]!!.jsonPrimitive.content)

        // Direct mode never queries dns-remote — `final` points at dns-direct — so an override
        // left there would be a resolver chosen in the list and ignored by the tunnel.
        val direct = servers(RoutingMode.Direct)
        assertEquals("dns-direct", direct["final"]!!.jsonPrimitive.content)
        val directServer = direct["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("xbox-dns.ru", directServer["server"]!!.jsonPrimitive.content)
        assertEquals("https", directServer["type"]!!.jsonPrimitive.content)
        // And it is queried from the phone's own connection: there is no proxy to detour through.
        assertNull(directServer["detour"])
    }

    @Test
    fun `a resolver written as a name gets something to resolve it with`() {
        // "initialize DNS server[1]: missing domain resolver for domain server address" — the core
        // refuses a named resolver that has no numeric one behind it, and pointing it at itself is
        // the circle it is refusing.
        val node = requireNotNull(ProxyUriParser.parse("vless://u@a.example.com:443?security=tls#N"))
        val config = Json.parseToJsonElement(
            SingBoxConfigFactory.build(
                node = node,
                settings = AppSettings(routingMode = RoutingMode.Direct),
                ruleSetDir = "/rule-sets",
                dnsOverride = "https://xbox-dns.ru/dns-query",
            ),
        ).jsonObject

        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)

        val bootstrap = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" }
        assertEquals("udp", bootstrap["type"]!!.jsonPrimitive.content)
        assertTrue(bootstrap["server"]!!.jsonPrimitive.content.first().isDigit())

        // And the proxy's own hostname is resolved by the bootstrap too, for the same reason.
        assertEquals(
            "dns-bootstrap",
            config["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `numeric resolvers need no bootstrap at all`() {
        val config = config("vless://u@a.example.com:443?security=tls#N")
        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        assertEquals(2, servers.size)
        assertTrue(servers.none { it["domain_resolver"] != null })
        assertEquals(
            "dns-direct",
            config["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the tun stack is gvisor, never the system or mixed stack`() {
        // "mixed" hands TCP to the system stack, which needs to manipulate real routes — beyond
        // an unrooted Android app. The result was a tunnel where DNS resolved over UDP through
        // gVisor while every TCP connection hung at sniffing, so this is pinned rather than left
        // to whatever the schema defaults to.
        val tun = config("vless://u@a.example.com:443?security=tls#N")["inbounds"]!!
            .jsonArray.first().jsonObject

        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
    }

    /**
     * A regression guard with a story behind it.
     *
     * Routing geosite-ru lookups to the direct resolver shipped once and made browsing drop out:
     * `dns-direct` is plain UDP leaving through the user's own connection, which is precisely what
     * gets throttled where this app is used, so most everyday names moved onto a path that fails
     * intermittently. Everything resolves through `final` until something answers what happens
     * when the direct resolver is unreachable.
     */
    @Test
    fun `no DNS rule sends names to the direct resolver`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls",
            ),
        )
        val dns = Json.parseToJsonElement(
            SingBoxConfigFactory.build(node, AppSettings(routingMode = RoutingMode.Rules), "/rules"),
        ).jsonObject["dns"]!!.jsonObject

        assertNull(dns["rules"])
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
    }
}
