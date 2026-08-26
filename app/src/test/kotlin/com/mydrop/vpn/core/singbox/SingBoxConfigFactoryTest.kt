package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
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
        val off = config("vless://uuid@se.example.com:443?security=tls#N").routeRules
        assertTrue(off.none { it["action"]?.jsonPrimitive?.content == "reject" })

        val on = config(
            "vless://uuid@se.example.com:443?security=tls#N",
            AppSettings(blockQuic = true),
        ).routeRules
        val rule = on.first { it["action"]?.jsonPrimitive?.content == "reject" }
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

        val tun = config(
            "vless://u@a.example.com:443?security=tls#N",
            AppSettings(enableIpv6 = false),
        )["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1, tun["address"]!!.jsonArray.size)
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
        val probeRule = config.routeRules.first { it["inbound"] != null }
        assertEquals("probe-in", probeRule["inbound"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("proxy", probeRule["outbound"]!!.jsonPrimitive.content)
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
