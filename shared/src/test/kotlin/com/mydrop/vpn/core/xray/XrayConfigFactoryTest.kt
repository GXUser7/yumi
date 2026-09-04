package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the core will and will not accept, asserted against the document rather than against the
 * builder.
 *
 * These are not tests of Kotlin. Every one of them stands for a way the core refuses a
 * configuration or, worse, accepts one and carries nothing — the failures that cost afternoons in
 * this project, written down so the next edit trips over them here instead of on a phone.
 */
class XrayConfigFactoryTest {

    // ------------------------------------------------------------------ Fixtures

    private fun node(
        id: String = "n1",
        name: String = "Server",
        server: String = "example.com",
        port: Int = 443,
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        tls: TlsOptions? = null,
        transport: TransportOptions? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = server,
        port = port,
        settings = settings,
        tls = tls,
        transport = transport,
    )

    private val settings = AppSettings()

    private fun build(
        vararg nodes: ProxyNode,
        settings: AppSettings = this.settings,
        probe: ProbeEndpoint? = null,
        geoAvailable: Boolean = true,
    ): JsonObject {
        val list = nodes.toList()
        val document = XrayConfigFactory.build(
            nodes = list,
            selected = list.first(),
            settings = settings,
            probe = probe,
            geoAvailable = geoAvailable,
        )
        return Json.parseToJsonElement(document.json).jsonObject
    }

    private fun JsonObject.outbounds(): List<JsonObject> =
        this["outbounds"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.rules(): List<JsonObject> =
        this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.outboundNamed(tag: String): JsonObject =
        outbounds().first { it["tag"]?.jsonPrimitive?.content == tag }

    // ------------------------------------------------------------------ The balancer contract

    /**
     * The binding selects candidates with `strings.HasPrefix(tag, selector)`, so a node tag that
     * does not start with the selector is a server the balancer cannot see — and a balancer with no
     * candidates hands every connection to the fallback, silently.
     */
    @Test
    fun `every node tag matches the balancer selector`() {
        val config = build(node(id = "a"), node(id = "b"))
        val selector = config["routing"]!!.jsonObject["balancers"]!!.jsonArray
            .single().jsonObject["selector"]!!.jsonArray
            .single().jsonPrimitive.content

        val nodeTags = listOf("a", "b").map(XrayConfigFactory::nodeTag)
        assertTrue(nodeTags.all { it.startsWith(selector) })
        assertTrue(nodeTags.all { tag -> config.outbounds().any { it["tag"]?.jsonPrimitive?.content == tag } })
    }

    /** The plumbing outbounds must fall outside the selector, or the balancer routes into `direct`. */
    @Test
    fun `direct block and dns outbounds are not balancer candidates`() {
        val config = build(node())
        val selector = config["routing"]!!.jsonObject["balancers"]!!.jsonArray
            .single().jsonObject["selector"]!!.jsonArray
            .single().jsonPrimitive.content

        listOf(
            XrayConfigFactory.DIRECT_TAG,
            XrayConfigFactory.BLOCK_TAG,
            XrayConfigFactory.DNS_TAG,
        ).forEach { assertFalse("$it must not match $selector", it.startsWith(selector)) }
    }

    /**
     * The core's default for traffic no rule matched is the *first outbound* — one specific server.
     * Without a catch-all naming the balancer, a switch would leave part of the traffic behind on
     * whichever node happened to be first.
     */
    @Test
    fun `the last rule sends everything left to the balancer`() {
        val last = build(node()).rules().last()
        assertEquals(XrayConfigFactory.BALANCER_TAG, last["balancerTag"]?.jsonPrimitive?.content)
        assertEquals("tcp,udp", last["network"]?.jsonPrimitive?.content)
        assertNull("outboundTag wins over balancerTag when both are present", last["outboundTag"])
    }

    /** Every rule has to point somewhere the document actually declares. */
    @Test
    fun `no rule names an outbound that does not exist`() {
        val config = build(node(id = "a"), node(id = "b"), probe = ProbeEndpoint(1080, "u", "p"))
        val tags = config.outbounds().map { it["tag"]!!.jsonPrimitive.content }.toSet()

        config.rules().forEach { rule ->
            rule["outboundTag"]?.jsonPrimitive?.content?.let {
                assertTrue("rule points at unknown outbound $it", it in tags)
            }
            rule["balancerTag"]?.jsonPrimitive?.content?.let {
                assertEquals(XrayConfigFactory.BALANCER_TAG, it)
            }
        }
    }

    /** The pinned tag goes straight to `yumi.Start`, which refuses one the document does not hold. */
    @Test
    fun `the pinned tag is the selected node and appears first`() {
        val nodes = listOf(node(id = "a"), node(id = "b"))
        val document = XrayConfigFactory.build(nodes = nodes, selected = nodes[1], settings = settings)

        assertEquals(XrayConfigFactory.nodeTag("b"), document.pinnedTag)
        assertEquals(XrayConfigFactory.nodeTag("b"), document.nodeTags.first())
        assertTrue(document.pinnedTag in document.nodeTags)
    }

    // ------------------------------------------------------------------ Per-protocol fields

    /**
     * The lesson from the sing-box side of this app, asserted here so it cannot repeat: that
     * factory writes `server` and `server_port` before it knows the protocol, and a `direct` node
     * therefore produces a document the core refuses outright — no tunnel at all, for every server.
     */
    @Test
    fun `a direct node carries no address fields and no stream settings`() {
        val config = build(node(settings = ProxySettings.Direct))
        val outbound = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))

        assertEquals("freedom", outbound["protocol"]!!.jsonPrimitive.content)
        assertNull(outbound["server"])
        assertNull(outbound["server_port"])
        assertNull(outbound["streamSettings"])
    }

    /** Mandatory on every VLESS user, and the one field with no sing-box counterpart at all. */
    @Test
    fun `vless users always declare encryption`() {
        val config = build(node())
        val user = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["settings"]!!
            .jsonObject["vnext"]!!.jsonArray.single().jsonObject["users"]!!.jsonArray
            .single().jsonObject

        assertEquals("none", user["encryption"]!!.jsonPrimitive.content)
    }

    /**
     * `ProxyNode.address` is a display string, and Xray takes any string as a domain name — so
     * `"example.com:443"` in an address field loads without complaint and then resolves to nothing.
     */
    @Test
    fun `addresses never carry the port`() {
        val config = build(node(settings = ProxySettings.Trojan(password = "p")))
        val server = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["settings"]!!
            .jsonObject["servers"]!!.jsonArray.single().jsonObject

        assertEquals("example.com", server["address"]!!.jsonPrimitive.content)
        assertEquals(443, server["port"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * The credential is not in `settings` — `users` there belongs to the server config, and a
     * password put in it produces an outbound that dials, completes nothing, and reports no error.
     */
    @Test
    fun `hysteria2 keeps its password in the stream settings`() {
        val config = build(node(settings = ProxySettings.Hysteria2(password = "secret")))
        val outbound = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))

        assertNull(outbound["settings"]!!.jsonObject["users"])
        assertNull(outbound["settings"]!!.jsonObject["password"])
        assertEquals("hysteria", outbound["streamSettings"]!!.jsonObject["network"]!!.jsonPrimitive.content)
        assertEquals(
            "secret",
            outbound["streamSettings"]!!.jsonObject["hysteriaSettings"]!!
                .jsonObject["auth"]!!.jsonPrimitive.content,
        )
        // No unencrypted form of it exists; a link naming no TLS gets it anyway.
        assertEquals("tls", outbound["streamSettings"]!!.jsonObject["security"]!!.jsonPrimitive.content)
    }

    /** Salamander is a packet mask in Xray, not a field on the protocol. Emitted as `obfs` it is ignored. */
    @Test
    fun `hysteria2 obfuscation and bandwidth land in finalmask`() {
        val config = build(
            node(
                settings = ProxySettings.Hysteria2(
                    password = "secret",
                    obfsType = "salamander",
                    obfsPassword = "mask",
                    upMbps = 50,
                    downMbps = 200,
                ),
            ),
        )
        val stream = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!.jsonObject
        assertNull(stream["hysteriaSettings"]!!.jsonObject["obfs"])

        val mask = stream["finalmask"]!!.jsonObject
        assertEquals(
            "salamander",
            mask["udp"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content,
        )
        val quic = mask["quicParams"]!!.jsonObject
        assertEquals("brutal", quic["congestion"]!!.jsonPrimitive.content)
        assertEquals("50mbps", quic["brutalUp"]!!.jsonPrimitive.content)
        assertEquals("200mbps", quic["brutalDown"]!!.jsonPrimitive.content)
    }

    /**
     * Broken on the sing-box side of this app — that factory still emits the pre-1.11 shape with
     * `peer_public_key`, which the pinned core dropped. Porting is what fixes it, so it is asserted.
     */
    @Test
    fun `wireguard is a peer with an endpoint and no stream layer`() {
        val config = build(
            node(
                server = "wg.example.com",
                port = 51820,
                settings = ProxySettings.WireGuard(
                    privateKey = "priv",
                    peerPublicKey = "pub",
                    localAddresses = listOf("10.0.0.2/32"),
                    reserved = listOf(1, 2, 3),
                ),
            ),
        )
        val outbound = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))
        assertEquals("wireguard", outbound["protocol"]!!.jsonPrimitive.content)
        assertNull("wireguard has no stream layer to give it", outbound["streamSettings"])

        val settings = outbound["settings"]!!.jsonObject
        assertEquals("priv", settings["secretKey"]!!.jsonPrimitive.content)
        assertNull("the pre-1.11 sing-box spelling has no place here", settings["peer_public_key"])

        val peer = settings["peers"]!!.jsonArray.single().jsonObject
        assertEquals("pub", peer["publicKey"]!!.jsonPrimitive.content)
        assertEquals("wg.example.com:51820", peer["endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an ipv6 wireguard endpoint is bracketed`() {
        val config = build(
            node(
                server = "2001:db8::1",
                port = 51820,
                settings = ProxySettings.WireGuard(privateKey = "priv", peerPublicKey = "pub"),
            ),
        )
        val peer = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["settings"]!!
            .jsonObject["peers"]!!.jsonArray.single().jsonObject
        assertEquals("[2001:db8::1]:51820", peer["endpoint"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ TLS

    /**
     * v26.7.28 answers `allowInsecure` with a hard removed-feature error, so one node carrying it
     * stops the core from starting for all of them.
     */
    @Test
    fun `allowInsecure never appears and the node is reported instead`() {
        val nodes = listOf(node(name = "Self-signed", tls = TlsOptions(insecure = true)))
        val document = XrayConfigFactory.build(nodes, nodes.first(), settings)

        assertFalse(document.json.contains("allowInsecure"))
        assertEquals(listOf("Self-signed"), document.verificationForced)
    }

    /** REALITY brings its own verification, so its nodes are not what the report above is about. */
    @Test
    fun `a reality node is not reported as forced verification`() {
        val nodes = listOf(
            node(
                tls = TlsOptions(
                    insecure = true,
                    serverName = "decoy.example.com",
                    reality = RealityOptions(publicKey = "pbk", shortId = "ab", spiderX = "/"),
                ),
            ),
        )
        val document = XrayConfigFactory.build(nodes, nodes.first(), settings)
        assertTrue(document.verificationForced.isEmpty())
    }

    @Test
    fun `reality keeps the camouflage name and always names a fingerprint`() {
        val config = build(
            node(
                tls = TlsOptions(
                    serverName = "decoy.example.com",
                    reality = RealityOptions(publicKey = "pbk", shortId = "ab", spiderX = "/x"),
                ),
            ),
        )
        val stream = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!.jsonObject
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)

        val reality = stream["realitySettings"]!!.jsonObject
        assertEquals("decoy.example.com", reality["serverName"]!!.jsonPrimitive.content)
        assertEquals("pbk", reality["publicKey"]!!.jsonPrimitive.content)
        assertEquals("ab", reality["shortId"]!!.jsonPrimitive.content)
        // Go's own ClientHello is the signature the whole exercise exists to avoid.
        assertEquals("chrome", reality["fingerprint"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ Transports

    @Test
    fun `xhttp reaches the document, which is what this port was for`() {
        val config = build(
            node(
                tls = TlsOptions(serverName = "example.com"),
                transport = TransportOptions.Xhttp(path = "/x", host = "cdn.example.com", mode = "stream-up"),
            ),
        )
        val stream = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!.jsonObject
        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)

        val xhttp = stream["xhttpSettings"]!!.jsonObject
        assertEquals("/x", xhttp["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", xhttp["host"]!!.jsonPrimitive.content)
        assertEquals("stream-up", xhttp["mode"]!!.jsonPrimitive.content)
    }

    /** `auto` is the core deciding for itself, and a server that knows better should not be overridden. */
    @Test
    fun `xhttp mode auto is left out entirely`() {
        val config = build(node(transport = TransportOptions.Xhttp(mode = "auto")))
        val xhttp = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!
            .jsonObject["xhttpSettings"]!!.jsonObject
        assertNull(xhttp["mode"])
    }

    /** Two spellings in one struct, both the core's: `serviceName` and `permit_without_stream`. */
    @Test
    fun `grpc keeps both of the core's spellings`() {
        val config = build(
            node(transport = TransportOptions.Grpc(serviceName = "svc", permitWithoutStream = true)),
        )
        val grpc = config.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!
            .jsonObject["grpcSettings"]!!.jsonObject
        assertEquals("svc", grpc["serviceName"]!!.jsonPrimitive.content)
        assertEquals(true, grpc["permit_without_stream"]!!.jsonPrimitive.content.toBoolean())
    }

    // ------------------------------------------------------------------ What Xray cannot carry

    @Test
    fun `protocols absent from the core are refused rather than emitted`() {
        assertEquals("TUIC", XrayConfigFactory.unsupported(node(settings = ProxySettings.Tuic(uuid = "u", password = "p"))))
        assertEquals("AnyTLS", XrayConfigFactory.unsupported(node(settings = ProxySettings.AnyTls(password = "p"))))
        assertNotNull(XrayConfigFactory.unsupported(node(settings = ProxySettings.Ssh(user = "root"))))
    }

    @Test
    fun `transports removed from the core are refused`() {
        assertEquals(
            "HTTP/2",
            XrayConfigFactory.unsupported(node(transport = TransportOptions.Http(path = "/"))),
        )
        assertEquals(
            "QUIC",
            XrayConfigFactory.unsupported(node(transport = TransportOptions.Quic)),
        )
    }

    @Test
    fun `what the core can carry is not refused`() {
        assertNull(XrayConfigFactory.unsupported(node()))
        assertNull(XrayConfigFactory.unsupported(node(transport = TransportOptions.Xhttp())))
        assertNull(
            XrayConfigFactory.unsupported(
                node(settings = ProxySettings.WireGuard(privateKey = "a", peerPublicKey = "b")),
            ),
        )
    }

    /** A subscription is forty servers, and one the core cannot speak must not sink the other 39. */
    @Test
    fun `an unsupported node is skipped rather than sinking the document`() {
        val good = node(id = "good")
        val bad = node(id = "bad", settings = ProxySettings.Tuic(uuid = "u", password = "p"))
        val document = XrayConfigFactory.build(listOf(good, bad), good, settings)

        assertEquals(listOf(XrayConfigFactory.nodeTag("good")), document.nodeTags)
        assertFalse(document.json.contains(XrayConfigFactory.nodeTag("bad")))
    }

    // ------------------------------------------------------------------ DNS

    /**
     * The schemes are matched by name in `app/dns/nameserver.go` and anything unrecognised is
     * treated as a plain UDP address — so `tls://1.1.1.1` becomes a hostname of that spelling and
     * the resolver answers nothing at all, without an error.
     */
    @Test
    fun `resolvers the core has no transport for are refused rather than downgraded`() {
        assertNull(XrayConfigFactory.xrayResolver("tls://1.1.1.1"))
        assertNull(XrayConfigFactory.xrayResolver("quic://dns.example.com"))
        assertNull(XrayConfigFactory.xrayResolver("h3://dns.example.com"))

        assertEquals("https://1.1.1.1/dns-query", XrayConfigFactory.xrayResolver("https://1.1.1.1/dns-query"))
        assertEquals("8.8.8.8", XrayConfigFactory.xrayResolver("8.8.8.8"))
        assertEquals("8.8.8.8", XrayConfigFactory.xrayResolver("udp://8.8.8.8"))
        assertEquals("quic+local://dns.example.com", XrayConfigFactory.xrayResolver("quic+local://dns.example.com"))
    }

    /**
     * The prototype sent every query the DNS app made out directly, which hands the ISP the list of
     * names being asked — the exact thing DoH was chosen to prevent.
     */
    @Test
    fun `the encrypted resolver rides the proxy and the direct one does not`() {
        val config = build(
            node(),
            settings = settings.copy(
                remoteDns = "https://1.1.1.1/dns-query",
                directDns = "8.8.8.8",
                routingMode = RoutingMode.Rules,
            ),
        )
        val dnsRules = config.rules().filter { rule ->
            rule["inboundTag"]?.jsonArray?.any {
                it.jsonPrimitive.content == XrayConfigFactory.DNS_IN_TAG
            } == true
        }

        val direct = dnsRules.first { it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG }
        assertEquals("8.8.8.8", direct["ip"]!!.jsonArray.single().jsonPrimitive.content)

        assertEquals(
            XrayConfigFactory.BALANCER_TAG,
            dnsRules.last()["balancerTag"]?.jsonPrimitive?.content,
        )
    }

    /**
     * A resolver named by domain has to be looked up before it can be asked anything, and there is
     * no tunnel to do that through yet — the one query that genuinely must go out directly.
     */
    @Test
    fun `a domain-named resolver gets its bootstrap sent direct`() {
        val config = build(
            node(),
            settings = settings.copy(remoteDns = "https://dns.example.com/dns-query"),
        )
        val bootstrap = config.rules().first { rule ->
            rule["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "full:dns.example.com" } == true
        }
        assertEquals(XrayConfigFactory.DIRECT_TAG, bootstrap["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct routing mode never sends a query through the proxy`() {
        val config = build(node(), settings = settings.copy(routingMode = RoutingMode.Direct))
        val dnsRules = config.rules().filter { rule ->
            rule["inboundTag"]?.jsonArray?.any {
                it.jsonPrimitive.content == XrayConfigFactory.DNS_IN_TAG
            } == true
        }
        assertTrue(dnsRules.isNotEmpty())
        assertTrue(dnsRules.none { it.containsKey("balancerTag") })
    }

    // ------------------------------------------------------------------ Geo databases

    /**
     * Xray resolves `geosite:`/`geoip:` while parsing, and one it cannot resolve rejects the whole
     * configuration rather than the single rule. A tunnel that routes everything through the proxy
     * beats one that will not start.
     */
    @Test
    fun `without the geo databases no rule mentions them`() {
        val config = build(
            node(),
            settings = settings.copy(routingMode = RoutingMode.Rules, blockAds = true),
            geoAvailable = false,
        )
        assertFalse(config.toString().contains("geosite:"))
        assertFalse(config.toString().contains("geoip:"))
    }

    @Test
    fun `with the geo databases the rules mode routes ru traffic direct`() {
        val config = build(node(), settings = settings.copy(routingMode = RoutingMode.Rules))
        val ru = config.rules().filter {
            it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG
        }
        assertTrue(
            ru.any { rule ->
                rule["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "geosite:category-ru" } == true
            },
        )
    }

    /** The LAN ranges are spelled out so reaching the router never waits on a 23 MB download. */
    @Test
    fun `bypassing the lan does not depend on the geo databases`() {
        val config = build(
            node(),
            settings = settings.copy(bypassLan = true),
            geoAvailable = false,
        )
        val lan = config.rules().first { rule ->
            rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "192.168.0.0/16" } == true
        }
        assertEquals(XrayConfigFactory.DIRECT_TAG, lan["outboundTag"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ Inbounds

    /**
     * An empty name sends the core into `GetAvailableTunName`, which enumerates interfaces through
     * netlink — refused to apps since Android 11, and it takes the whole configuration down with it
     * before anything else is even looked at.
     */
    @Test
    fun `the tun inbound always names an interface`() {
        val config = build(node())
        val tun = config["inbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == XrayConfigFactory.TUN_TAG }

        val name = tun["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertTrue(name.isNotBlank())
        // Needs CAP_NET_RAW, which an unprivileged Android app does not have.
        assertNull(tun["settings"]!!.jsonObject["autoOutboundsInterface"])
        // Route on the sniffed name, but do not dial by it: most of what sits behind a CDN breaks.
        assertEquals(true, tun["sniffing"]!!.jsonObject["routeOnly"]!!.jsonPrimitive.content.toBoolean())
    }

    /** Bound to every interface this would be an open proxy for the whole network the phone is on. */
    @Test
    fun `the probe inbound listens on loopback with credentials`() {
        val config = build(node(), probe = ProbeEndpoint(1080, "user", "pass"))
        val probe = config["inbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == XrayConfigFactory.PROBE_TAG }

        assertEquals("127.0.0.1", probe["listen"]!!.jsonPrimitive.content)
        assertEquals("password", probe["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        val account = probe["settings"]!!.jsonObject["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("user", account["user"]!!.jsonPrimitive.content)
    }

    /** The probe measures the tunnel, so it rides it whatever the routing mode says. */
    @Test
    fun `the probe is routed through the balancer even in direct mode`() {
        val config = build(
            node(),
            settings = settings.copy(routingMode = RoutingMode.Direct),
            probe = ProbeEndpoint(1080, "u", "p"),
        )
        val rule = config.rules().first { rule ->
            rule["inboundTag"]?.jsonArray?.any {
                it.jsonPrimitive.content == XrayConfigFactory.PROBE_TAG
            } == true
        }
        assertEquals(XrayConfigFactory.BALANCER_TAG, rule["balancerTag"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ Statistics

    /** Without both of these the outbound handler registers no counters and the speed stays at zero. */
    @Test
    fun `the document asks the core to count bytes`() {
        val config = build(node())
        assertNotNull(config["stats"])
        val system = config["policy"]!!.jsonObject["system"]!!.jsonObject
        assertEquals(true, system["statsOutboundUplink"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, system["statsOutboundDownlink"]!!.jsonPrimitive.content.toBoolean())
    }

    /** Nothing above is worth anything if the document does not parse. */
    @Test
    fun `the document is valid json with the sections the core requires`() {
        val config = build(node(), probe = ProbeEndpoint(1080, "u", "p"))
        listOf("log", "dns", "inbounds", "outbounds", "routing", "stats", "policy").forEach {
            assertNotNull("missing section $it", config[it])
        }
        assertTrue(config["inbounds"] is JsonArray)
        assertTrue(config["outbounds"] is JsonArray)
        assertTrue(config["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive is JsonPrimitive)
    }
}
