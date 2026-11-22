package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.FragmentMode
import com.mydrop.vpn.core.model.FragmentOptions
import com.mydrop.vpn.core.model.FragmentScope
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsFingerprintOverride
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import java.util.Base64
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
        geoAvailable: Boolean = true,
    ): JsonObject {
        val list = nodes.toList()
        val document = XrayConfigFactory.build(
            nodes = list,
            selected = list.first(),
            settings = settings,
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

    private fun JsonObject.dnsRules(): List<JsonObject> =
        rules().filter { rule ->
            rule["inboundTag"]?.jsonArray?.any {
                it.jsonPrimitive.content == XrayConfigFactory.DNS_IN_TAG
            } == true
        }

    // ------------------------------------------------------------------ Timeouts

    /**
     * Xray's own default here is five minutes, and leaving the key out is a decision to accept it.
     *
     * Five minutes reaps any flow that goes quiet — which is what a push connection does by design
     * between messages, for far longer than that. The core then drops the tun side without an RST
     * the phone can see, so the notification socket is not reconnected, it is simply never used
     * again. Asserted rather than trusted because the symptom is silence: nothing fails, nothing is
     * logged, and the tunnel goes on passing every health check.
     */
    @Test
    fun `idle connections outlive a push heartbeat`() {
        val level0 = build(node())["policy"]!!.jsonObject["levels"]!!.jsonObject["0"]!!.jsonObject
        val idle = level0["connIdle"]!!.jsonPrimitive.content.toInt()

        assertTrue("connIdle $idle s is under Firebase's half-hour heartbeat", idle >= 1800)
    }

    /**
     * One second is what the core replaces the idle timeout with once a direction finishes, and it
     * is far too little for a connection that is supposed to sit quietly waiting to be spoken to.
     */
    @Test
    fun `a half-closed connection is not shot within the second`() {
        val level0 = build(node())["policy"]!!.jsonObject["levels"]!!.jsonObject["0"]!!.jsonObject

        listOf("uplinkOnly", "downlinkOnly").forEach { key ->
            val seconds = level0[key]!!.jsonPrimitive.content.toInt()
            assertTrue("$key is $seconds s", seconds >= 15)
            // Zero reads as "no limit" and means "expire now" — see ActivityTimer.SetTimeout.
            assertTrue("$key must never be zero", seconds > 0)
        }
    }

    /** The counters are what the connect screen reads; they were here first and stay. */
    @Test
    fun `outbound byte counters survive the policy block`() {
        val system = build(node())["policy"]!!.jsonObject["system"]!!.jsonObject

        assertTrue(system["statsOutboundUplink"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(system["statsOutboundDownlink"]!!.jsonPrimitive.content.toBoolean())
    }

    // ------------------------------------------------------------------ The DNS hijack setting

    private fun JsonObject.dnsCatchRules(): List<JsonObject> =
        rules().filter { it["port"]?.jsonPrimitive?.content == "53" }

    /**
     * Turning the hijack off must weaken the setting, not remove DNS from the phone.
     *
     * The tunnel advertises [XrayConfigFactory.TUN_DNS_V4] to applications whatever this setting
     * says, so something has to answer there. When the only port-53 rule was the hijack itself,
     * turning it off left every application in the tunnel pointed at an address with nobody
     * listening — and the query did not even fail quickly, it matched the LAN bypass and went out
     * of the physical interface addressed to an interface that exists only inside the phone.
     */
    @Test
    fun `the core answers its own resolver with the hijack off`() {
        val rules = build(node(), settings = settings.copy(hijackDns = false)).dnsCatchRules()
        val own = rules.filter { rule ->
            rule["ip"]?.jsonArray?.any {
                it.jsonPrimitive.content.startsWith(XrayConfigFactory.TUN_DNS_V4)
            } == true
        }

        assertEquals("exactly one rule may claim the tunnel's own resolver", 1, own.size)
        assertEquals(XrayConfigFactory.DNS_TAG, own.single()["outboundTag"]?.jsonPrimitive?.content)
    }

    /** And with the hijack off, nothing claims queries addressed anywhere else. */
    @Test
    fun `queries addressed elsewhere are taken only when the hijack is on`() {
        val off = build(node(), settings = settings.copy(hijackDns = false)).dnsCatchRules()
        assertTrue("a blanket port-53 rule survived the setting being off", off.all { it["ip"] != null })

        val on = build(node(), settings = settings.copy(hijackDns = true)).dnsCatchRules()
        assertTrue("nothing claims port 53 in general with the hijack on", on.any { it["ip"] == null })
    }

    // ------------------------------------------------------------------ The tunnel's own subnet

    /**
     * Whatever else is addressed to the tunnel's subnet is dropped before the LAN bypass can send
     * it `direct`: its broadcast address routes back into the tunnel even from a protected socket,
     * and each turn of that loop held a UDP port until the phone had none left.
     */
    @Test
    fun `the tunnel's own subnet is dropped ahead of the lan bypass`() {
        val rules = build(node(), settings = settings.copy(bypassLan = true)).rules()
        // The v6 half is only checked loosely: how an address is written back out differs between
        // the JVM and Android, and the core reads either.
        val own = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "172.19.0.0/30" in ips && ips.any { it.startsWith("fdfe:dcba:9876:") && it.endsWith("/126") }
        }
        val resolver = rules.indexOfFirst { rule ->
            rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "${XrayConfigFactory.TUN_DNS_V4}/32" } == true
        }
        val lan = rules.indexOfFirst { rule ->
            rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "172.16.0.0/12" } == true
        }

        assertTrue("no rule drops the tunnel's subnet", own >= 0)
        assertEquals(XrayConfigFactory.BLOCK_TAG, rules[own]["outboundTag"]?.jsonPrimitive?.content)
        assertTrue("the resolver has to be answered before its subnet is dropped", resolver in 0 until own)
        assertTrue("the lan bypass would send the subnet round first", own < lan)
    }

    // ------------------------------------------------------------------ The carrier's own names

    /**
     * A connection addressed to the operator's own tree goes out of the phone, not to the proxy.
     *
     * This is all the list can do from here, and the test says so on purpose. Steering the *DNS
     * lookup* for these names cannot be expressed as a routing rule: the router matches a domain
     * with `ctx.GetTargetDomain()`, which for a query leaving the DNS module is the resolver's own
     * address rather than the name being looked up. A rule written that way passes review, passes
     * a shape assertion like this one, and matches nothing at runtime — which is how the first
     * version of this fix was written.
     */
    @Test
    fun `connections to carrier internal names leave the phone directly`() {
        val rule = build(node()).rules().first { rule ->
            rule["domain"]?.jsonArray?.any { it.jsonPrimitive.content.endsWith("3gppnetwork.org") } == true
        }

        assertEquals(XrayConfigFactory.DIRECT_TAG, rule["outboundTag"]?.jsonPrimitive?.content)
        assertNull(rule["balancerTag"])
        assertNull("a dns-in rule cannot see the queried name; see the doc comment", rule["inboundTag"])
    }

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
        val config = build(node(id = "a"), node(id = "b"))
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
        assertNull(XrayConfigFactory.xrayResolver("quic+local://dns.example.com"))
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

    /**
     * YouTube's video nodes sit inside Russian networks and `geoip:ru` sent one direct, where it
     * hung. Its names have to be matched, and sent to the proxy, before the country rules are.
     */
    @Test
    fun `the rules mode keeps youtube on the proxy ahead of the ru rules`() {
        val config = build(node(), settings = settings.copy(routingMode = RoutingMode.Rules))
        val rules = config.rules()
        val youtube = rules.indexOfFirst { rule ->
            rule["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "domain:googlevideo.com" } == true
        }
        val ruByAddress = rules.indexOfFirst { rule ->
            rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "geoip:ru" } == true
        }
        assertTrue(youtube >= 0)
        assertTrue(youtube < ruByAddress)
        assertEquals(XrayConfigFactory.BALANCER_TAG, rules[youtube]["balancerTag"]?.jsonPrimitive?.content)
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

    /**
     * F10: No loopback SOCKS/HTTP/API inbound or `probe-in` routing rule is ever emitted, even if
     * a deprecated ProbeEndpoint is supplied.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `only the tun inbound exists and no loopback proxy or probe-in rule is emitted`() {
        val doc = XrayConfigFactory.build(
            nodes = listOf(node()),
            selected = node(),
            settings = settings.copy(routingMode = RoutingMode.Direct),
            probe = com.mydrop.vpn.core.model.ProbeEndpoint(1080, "user", "pass"),
        )
        val config = Json.parseToJsonElement(doc.json).jsonObject
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf(XrayConfigFactory.TUN_TAG), inbounds.map { it["tag"]!!.jsonPrimitive.content })
        assertTrue(inbounds.none { it["listen"] != null })
        assertTrue(config.rules().none { rule ->
            rule["inboundTag"]?.jsonArray?.any { it.jsonPrimitive.content == "probe-in" } == true
        })
        assertNull(config["api"])
        assertNull(config["metrics"])
    }

    /**
     * F11: Loopback traffic (`127.0.0.0/8` and `::1/128`) entering via `tun-in` is explicitly
     * blocked ahead of `hijackDns` and `bypassLan`, and loopback CIDRs are absent from `DIRECT_TAG`
     * `bypassLan` rules regardless of `bypassLan` or `enableIpv6`.
     */
    @Test
    fun `tun-in loopback traffic is blocked ahead of bypassLan and absent from private direct ranges`() {
        for (bypassLan in listOf(true, false)) {
            for (enableIpv6 in listOf(true, false)) {
                val config = build(
                    node(),
                    settings = settings.copy(bypassLan = bypassLan, enableIpv6 = enableIpv6),
                )
                val rules = config.rules()
                val loopbackBlockIdx = rules.indexOfFirst { rule ->
                    val inbound = rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    XrayConfigFactory.TUN_TAG in inbound &&
                        "127.0.0.0/8" in ips &&
                        "::1/128" in ips &&
                        rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
                }
                assertTrue(
                    "missing tun-in loopback block rule for bypassLan=$bypassLan ipv6=$enableIpv6",
                    loopbackBlockIdx >= 0,
                )

                val directLanIdx = rules.indexOfFirst { rule ->
                    rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG &&
                        rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "10.0.0.0/8" } == true
                }
                if (bypassLan) {
                    assertTrue(directLanIdx > loopbackBlockIdx)
                    val directIps = rules[directLanIdx]["ip"]!!.jsonArray.map { it.jsonPrimitive.content }
                    assertFalse("127.0.0.0/8" in directIps)
                    assertFalse("::1/128" in directIps)
                } else {
                    assertEquals(-1, directLanIdx)
                }
            }
        }
    }

    /**
     * F11: Uniform hardening across all 9 supported protocols and 6 supported transports.
     */
    @Test
    fun `all 9 protocols and 6 transports share identical tun-in loopback and no-proxy hardening`() {
        val tls = TlsOptions(serverName = "example.com")
        val matrix = listOf(
            node(id = "vless-tcp", settings = ProxySettings.Vless(uuid = "u1"), tls = tls),
            node(id = "vless-ws", settings = ProxySettings.Vless(uuid = "u1"), tls = tls, transport = TransportOptions.WebSocket()),
            node(id = "vless-grpc", settings = ProxySettings.Vless(uuid = "u1"), tls = tls, transport = TransportOptions.Grpc("svc")),
            node(id = "vless-httpupgrade", settings = ProxySettings.Vless(uuid = "u1"), tls = tls, transport = TransportOptions.HttpUpgrade()),
            node(id = "vless-xhttp", settings = ProxySettings.Vless(uuid = "u1"), tls = tls, transport = TransportOptions.Xhttp()),
            node(id = "vmess", settings = ProxySettings.Vmess(uuid = "u1"), tls = tls),
            node(id = "trojan", settings = ProxySettings.Trojan(password = "p1"), tls = tls),
            node(id = "ss", settings = ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "p1")),
            node(id = "hy2", settings = ProxySettings.Hysteria2(password = "p1")),
            node(id = "wg", settings = ProxySettings.WireGuard(privateKey = "priv", peerPublicKey = "pub")),
            node(id = "http", settings = ProxySettings.Http()),
            node(id = "socks", settings = ProxySettings.Socks()),
            node(id = "direct", settings = ProxySettings.Direct),
        )

        matrix.forEach { n ->
            val config = build(n)
            val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
            assertEquals("inbounds mismatch for ${n.id}", listOf(XrayConfigFactory.TUN_TAG), inbounds.map { it["tag"]!!.jsonPrimitive.content })
            assertTrue("loopback block missing for ${n.id}", config.rules().any { rule ->
                val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                "127.0.0.0/8" in ips && "::1/128" in ips &&
                    rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
            })
        }
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
        val config = build(node())
        listOf("log", "dns", "inbounds", "outbounds", "routing", "stats", "policy").forEach {
            assertNotNull("missing section $it", config[it])
        }
        assertTrue(config["inbounds"] is JsonArray)
        assertTrue(config["outbounds"] is JsonArray)
        assertTrue(config["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive is JsonPrimitive)
    }

    // ------------------------------------------------------------------ Groups

    /**
     * A group is every member under a tag of its own, the tunnel starts on the first, and a member
     * that is also listed alone does not produce a second outbound with the same tag — which the
     * core refuses, taking every other server with it.
     */
    @Test
    fun `a group is all its members, pinned to the first, with no tag twice`() {
        val first = node(id = "m1", server = "a.example")
        val second = node(id = "m2", server = "b.example")
        val group = node(id = "g", name = "Auto").copy(group = NodeGroup(listOf(first, second)))
        val alone = node(id = "m2", server = "b.example")

        val document = XrayConfigFactory.build(
            nodes = listOf(group, alone),
            selected = group,
            settings = settings,
        )
        val config = Json.parseToJsonElement(document.json).jsonObject
        val tags = config.outbounds().mapNotNull { it["tag"]?.jsonPrimitive?.content }

        assertEquals(tags.size, tags.toSet().size)
        assertTrue(XrayConfigFactory.nodeTag("g-0") in tags)
        assertTrue(XrayConfigFactory.nodeTag("g-1") in tags)
        assertTrue(XrayConfigFactory.nodeTag("m2") in tags)
        assertEquals(XrayConfigFactory.nodeTag("g-0"), document.pinnedTag)
        assertEquals(XrayConfigFactory.nodeTag("g"), document.nodeTags.first())
        assertTrue("a.example" in config.outboundNamed(XrayConfigFactory.nodeTag("g-0")).toString())
    }

    @Test
    fun `a group keeps the members the core can carry and drops the ones it cannot`() {
        val carried = node(id = "m1", server = "a.example")
        val refused = node(id = "m2", server = "b.example", settings = ProxySettings.Tuic(uuid = "u", password = "p"))
        val group = node(id = "g", name = "Auto").copy(group = NodeGroup(listOf(carried, refused)))

        val result = XrayConfigFactory.carriable(group)

        assertNotNull(result)
        assertNull(result!!.group)
        assertEquals("a.example", result.server)
        assertEquals("Auto", result.name)
    }

    @Test
    fun `Tier5 xrayResolver normalizes case-insensitive udp and rejects localhost, fakedns, and loopback or unspecified targets`() {
        assertEquals("8.8.8.8", XrayConfigFactory.xrayResolver("UDP://8.8.8.8"))
        assertEquals("8.8.8.8:53", XrayConfigFactory.xrayResolver("Udp://8.8.8.8:53"))
        assertEquals("https+local://1.1.1.1/dns-query", XrayConfigFactory.xrayResolver("https+local://1.1.1.1/dns-query"))
        assertEquals("tcp+local://8.8.8.8", XrayConfigFactory.xrayResolver("tcp+local://8.8.8.8"))

        val forbidden = listOf(
            "localhost",
            "LOCALHOST",
            "localhost.",
            "sub.localhost",
            "udp://localhost",
            "UDP://LOCALHOST:53",
            "tcp://localhost",
            "tcp+local://localhost",
            "https://localhost/dns-query",
            "fakedns",
            "FAKEDNS",
            "udp://fakedns",
            "127.0.0.1",
            "127.0.0.53",
            "127.1",
            "127.0.1",
            "0177.0.0.1",
            "0177.1",
            "0x7f.0.0.1",
            "0x7f000001",
            "2130706433",
            "udp://127.0.0.1",
            "udp://127.1",
            "udp://0177.0.0.1",
            "tcp://127.0.0.1",
            "tcp+local://127.0.0.1",
            "https://127.0.0.1/dns-query",
            "https://0177.0.0.1/dns-query",
            "https://127.1/dns-query",
            "https+local://127.0.0.1/dns-query",
            "::1",
            "::1%lo0",
            "[::1]",
            "[::1]:53",
            "[::1%25lo0]",
            "[::1%25lo0]:53",
            "https://[::1%25lo0]/dns-query",
            "::ffff:127.0.0.1",
            "::ffff:127.1",
            "0.0.0.0",
            "0000.0.0.0",
            "0x0.0.0.0",
            "0",
            "::",
            "[::]",
        )
        for (resolver in forbidden) {
            assertNull("xrayResolver must reject '$resolver'", XrayConfigFactory.xrayResolver(resolver))
        }
    }

    @Test
    fun `custom-port UDP DNS is serialized as an object with address and port while port-less UDP stays string`() {
        val config = build(
            node(),
            settings = settings.copy(
                remoteDns = "udp://1.1.1.1:5353",
                directDns = "udp://8.8.8.8",
            ),
        )
        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals(2, servers.size)

        val remoteObj = servers[0].jsonObject
        assertEquals("1.1.1.1", remoteObj["address"]!!.jsonPrimitive.content)
        assertEquals(5353, remoteObj["port"]!!.jsonPrimitive.content.toInt())

        assertEquals("8.8.8.8", servers[1].jsonPrimitive.content)
    }

    @Test
    fun `default settings emit no fragment outbound and no dialerProxy`() {
        val tlsNode = ProxyNode(
            id = "n1",
            name = "N1",
            server = "a.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-n1"),
            tls = TlsOptions(serverName = "a.example.com"),
        )
        val doc = XrayConfigFactory.build(listOf(tlsNode), tlsNode, AppSettings())
        val outbounds = Json.parseToJsonElement(doc.json).jsonObject["outbounds"]!!.jsonArray

        assertNull(outbounds.firstOrNull { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.FRAGMENT_TAG })
        val proxyStream = outbounds
            .first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag("n1") }
            .jsonObject["streamSettings"]!!.jsonObject
        assertNull(proxyStream["sockopt"])
    }

    @Test
    fun `global fragmentation emits freedom outbound with AsIs strategy and honors scope and excluded protocols`() {
        val tlsNode = ProxyNode(
            id = "tls",
            name = "TLS",
            server = "tls.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-tls"),
            tls = TlsOptions(serverName = "tls.example.com"),
        )
        val realityNode = ProxyNode(
            id = "rel",
            name = "REL",
            server = "rel.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-rel"),
            tls = TlsOptions(serverName = "decoy.example.com", reality = RealityOptions(publicKey = "PUB")),
        )
        val plainNode = ProxyNode(
            id = "plain",
            name = "PLAIN",
            server = "plain.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-plain"),
            tls = null,
        )
        val h3XhttpNode = ProxyNode(
            id = "h3",
            name = "H3",
            server = "h3.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-h3"),
            tls = TlsOptions(serverName = "h3.example.com", alpn = listOf("h3")),
            transport = TransportOptions.Xhttp(host = "h3.example.com", path = "/xh"),
        )
        val hy2Node = ProxyNode(
            id = "hy2",
            name = "HY2",
            server = "hy2.example.com",
            port = 443,
            settings = ProxySettings.Hysteria2(password = "pw"),
            tls = TlsOptions(serverName = "hy2.example.com"),
        )
        val wgNode = ProxyNode(
            id = "wg",
            name = "WG",
            server = "wg.example.com",
            port = 51820,
            settings = ProxySettings.WireGuard(privateKey = "priv", peerPublicKey = "pub"),
        )
        val directNode = ProxyNode(
            id = "dir",
            name = "Direct",
            server = "direct",
            port = 0,
            settings = ProxySettings.Direct,
        )
        val allNodes = listOf(tlsNode, realityNode, plainNode, h3XhttpNode, hy2Node, wgNode, directNode)

        fun dialerProxyOf(doc: XrayConfigFactory.Document, nodeId: String): String? {
            val outbounds = Json.parseToJsonElement(doc.json).jsonObject["outbounds"]!!.jsonArray
            val ob = outbounds.first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag(nodeId) }
                .jsonObject
            return ob["streamSettings"]?.jsonObject?.get("sockopt")?.jsonObject?.get("dialerProxy")?.jsonPrimitive?.content
        }

        val tlsOnlyDoc = XrayConfigFactory.build(
            nodes = allNodes,
            selected = tlsNode,
            settings = AppSettings(
                fragmentMode = FragmentMode.TlsHello,
                fragmentScope = FragmentScope.TlsOnly,
                fragmentLength = "10-20",
                fragmentInterval = "5-10",
                fragmentMaxChunks = "4",
            ),
        )
        val outbounds = Json.parseToJsonElement(tlsOnlyDoc.json).jsonObject["outbounds"]!!.jsonArray
        val fragOb = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.FRAGMENT_TAG
        }.jsonObject
        assertFalse(XrayConfigFactory.FRAGMENT_TAG.startsWith(XrayConfigFactory.nodeTag("")))
        assertEquals("freedom", fragOb["protocol"]?.jsonPrimitive?.content)
        val fragSettings = fragOb["settings"]!!.jsonObject
        assertEquals("AsIs", fragSettings["domainStrategy"]?.jsonPrimitive?.content)
        val frag = fragSettings["fragment"]!!.jsonObject
        assertEquals("tlshello", frag["packets"]?.jsonPrimitive?.content)
        assertEquals("10-20", frag["length"]?.jsonPrimitive?.content)
        assertEquals("5-10", frag["interval"]?.jsonPrimitive?.content)
        assertEquals("4", frag["maxSplit"]?.jsonPrimitive?.content)

        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(tlsOnlyDoc, "tls"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "rel"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "plain"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "h3"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "hy2"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "wg"))
        assertNull(dialerProxyOf(tlsOnlyDoc, "dir"))

        val tlsRealityDoc = XrayConfigFactory.build(
            nodes = allNodes,
            selected = tlsNode,
            settings = AppSettings(
                fragmentMode = FragmentMode.TcpSegments,
                fragmentScope = FragmentScope.TlsAndReality,
            ),
        )
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(tlsRealityDoc, "tls"))
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(tlsRealityDoc, "rel"))
        assertNull(dialerProxyOf(tlsRealityDoc, "plain"))
        assertNull(dialerProxyOf(tlsRealityDoc, "h3"))
        assertNull(dialerProxyOf(tlsRealityDoc, "hy2"))

        val allTcpDoc = XrayConfigFactory.build(
            nodes = allNodes,
            selected = tlsNode,
            settings = AppSettings(
                fragmentMode = FragmentMode.TcpSegments,
                fragmentScope = FragmentScope.AllTcp,
            ),
        )
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(allTcpDoc, "tls"))
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(allTcpDoc, "rel"))
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(allTcpDoc, "plain"))
        assertNull(dialerProxyOf(allTcpDoc, "h3"))
        assertNull(dialerProxyOf(allTcpDoc, "hy2"))
        assertNull(dialerProxyOf(allTcpDoc, "wg"))
        assertNull(dialerProxyOf(allTcpDoc, "dir"))

        val socksNode = ProxyNode(
            id = "socks",
            name = "SOCKS",
            server = "socks.example.com",
            port = 42000,
            settings = ProxySettings.Socks(username = "u", password = "p"),
            tls = null,
        )
        val tlsHelloAllTcpDoc = XrayConfigFactory.build(
            nodes = allNodes + socksNode,
            selected = tlsNode,
            settings = AppSettings(
                fragmentMode = FragmentMode.TlsHello,
                fragmentScope = FragmentScope.AllTcp,
            ),
        )
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(tlsHelloAllTcpDoc, "tls"))
        assertEquals(XrayConfigFactory.FRAGMENT_TAG, dialerProxyOf(tlsHelloAllTcpDoc, "rel"))
        assertNull(dialerProxyOf(tlsHelloAllTcpDoc, "plain"))
        assertNull(dialerProxyOf(tlsHelloAllTcpDoc, "socks"))
    }

    @Test
    fun `provider fragment configs deduplicate and are overridden when global fragment is enabled`() {
        val fragA = FragmentOptions(packets = "tlshello", length = "10-20", interval = "10-20")
        val fragB = FragmentOptions(packets = "1-3", length = "1-5", interval = "2-5", maxSplit = "6")
        val n1 = ProxyNode(
            id = "n1",
            name = "N1",
            server = "a.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-n1"),
            tls = TlsOptions(),
            fragment = fragA,
        )
        val n2 = ProxyNode(
            id = "n2",
            name = "N2",
            server = "b.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-n2"),
            tls = TlsOptions(),
            fragment = fragA,
        )
        val n3 = ProxyNode(
            id = "n3",
            name = "N3",
            server = "c.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-n3"),
            tls = TlsOptions(),
            fragment = fragB,
        )

        val offDoc = XrayConfigFactory.build(listOf(n1, n2, n3), n1, AppSettings(fragmentMode = FragmentMode.Off))
        val offOutbounds = Json.parseToJsonElement(offDoc.json).jsonObject["outbounds"]!!.jsonArray
        val offFragTags = offOutbounds.mapNotNull { ob ->
            val obj = ob.jsonObject
            if (obj["protocol"]?.jsonPrimitive?.content == "freedom" &&
                obj["settings"]?.jsonObject?.containsKey("fragment") == true
            ) {
                obj["tag"]?.jsonPrimitive?.content
            } else {
                null
            }
        }
        assertEquals(listOf("fragment", "fragment-2"), offFragTags)

        fun dialerProxy(outbounds: JsonArray, id: String) = outbounds
            .first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag(id) }
            .jsonObject["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content

        assertEquals("fragment", dialerProxy(offOutbounds, "n1"))
        assertEquals("fragment", dialerProxy(offOutbounds, "n2"))
        assertEquals("fragment-2", dialerProxy(offOutbounds, "n3"))

        val overrideDoc = XrayConfigFactory.build(
            listOf(n1, n2, n3),
            n1,
            AppSettings(
                fragmentMode = FragmentMode.TcpSegments,
                fragmentScope = FragmentScope.TlsAndReality,
                fragmentLength = "2-4",
                fragmentInterval = "3-6",
            ),
        )
        val overrideOutbounds = Json.parseToJsonElement(overrideDoc.json).jsonObject["outbounds"]!!.jsonArray
        val overrideFragCount = overrideOutbounds.count {
            it.jsonObject["settings"]?.jsonObject?.containsKey("fragment") == true
        }
        assertEquals(1, overrideFragCount)
        assertEquals("fragment", dialerProxy(overrideOutbounds, "n3"))
    }

    @Test
    fun `tls fingerprint override applies to TLS and REALITY but skips Hysteria2, and expanded known list works`() {
        val tlsShuffle = ProxyNode(
            id = "tls",
            name = "TLS",
            server = "tls.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-tls"),
            tls = TlsOptions(serverName = "tls.example.com", fingerprint = "hellochrome_106_shuffle"),
        )
        val realityBad = ProxyNode(
            id = "rel",
            name = "REL",
            server = "rel.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-rel"),
            tls = TlsOptions(
                serverName = "decoy.example.com",
                fingerprint = "hellogolang",
                reality = RealityOptions(publicKey = "PUB"),
            ),
        )
        val tlsUnsafe = ProxyNode(
            id = "unsafe",
            name = "UNSAFE",
            server = "u.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-u"),
            tls = TlsOptions(serverName = "u.example.com", fingerprint = "unsafe"),
        )
        val hy2 = ProxyNode(
            id = "hy2",
            name = "HY2",
            server = "hy2.example.com",
            port = 443,
            settings = ProxySettings.Hysteria2(password = "pw"),
            tls = TlsOptions(serverName = "hy2.example.com", fingerprint = "firefox"),
        )

        val autoDoc = XrayConfigFactory.build(
            listOf(tlsShuffle, realityBad, tlsUnsafe, hy2),
            tlsShuffle,
            AppSettings(tlsFingerprint = TlsFingerprintOverride.Auto),
        )
        val autoOutbounds = Json.parseToJsonElement(autoDoc.json).jsonObject["outbounds"]!!.jsonArray
        fun streamOf(outbounds: JsonArray, id: String) = outbounds
            .first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag(id) }
            .jsonObject["streamSettings"]!!.jsonObject

        assertEquals(
            "hellochrome_106_shuffle",
            streamOf(autoOutbounds, "tls")["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "chrome",
            streamOf(autoOutbounds, "rel")["realitySettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "chrome",
            streamOf(autoOutbounds, "unsafe")["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "firefox",
            streamOf(autoOutbounds, "hy2")["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )

        val qqDoc = XrayConfigFactory.build(
            listOf(tlsShuffle, realityBad, hy2),
            tlsShuffle,
            AppSettings(tlsFingerprint = TlsFingerprintOverride.Qq),
        )
        val qqOutbounds = Json.parseToJsonElement(qqDoc.json).jsonObject["outbounds"]!!.jsonArray
        assertEquals(
            "qq",
            streamOf(qqOutbounds, "tls")["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "qq",
            streamOf(qqOutbounds, "rel")["realitySettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "firefox",
            streamOf(qqOutbounds, "hy2")["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `xhttp extra sanitization, VLESS encryption, mldsa65Verify, echConfigList, and grpc multiMode are emitted safely`() {
        val rawExtra = """
            {
              "host": "ignored-by-extra.example.com",
              "path": "/ignored",
              "mode": "packet-up",
              "xPaddingBytes": "100-500",
              "scMaxEachPostBytes": 1000000,
              "headers": {"Host": "bad.example.com", "User-Agent": "InnerAgent"},
              "downloadSettings": {
                "address": "cdn.example.com",
                "port": 443,
                "network": "xhttp",
                "security": "tls",
                "tlsSettings": {"serverName": "cdn.example.com", "allowInsecure": true, "fingerprint": "unsafe"},
                "sockopt": {"dialerProxy": "unknown-proxy"},
                "xhttpSettings": {"host": "cdn.example.com", "path": "/dl", "mode": "stream-one"}
              }
            }
        """.trimIndent()
        val pqKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val validEncryption = "mlkem768x25519plus.native.1rtt.$pqKey"
        val validPqv = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952) { 3 })

        val xhttpNode = ProxyNode(
            id = "xh",
            name = "XH",
            server = "xh.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-xh", encryption = validEncryption),
            tls = TlsOptions(serverName = "xh.example.com", echConfigList = "ECH-CFG-DATA"),
            transport = TransportOptions.Xhttp(
                host = "xh.example.com",
                path = "/up",
                mode = "auto",
                headers = mapOf("Host" to "outer.example.com", "X-Outer" to "keep-if-needed"),
                extraJson = rawExtra,
            ),
        )
        val grpcNode = ProxyNode(
            id = "gr",
            name = "GR",
            server = "gr.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-gr", encryption = "not-valid-encryption"),
            tls = TlsOptions(
                serverName = "decoy.example.com",
                reality = RealityOptions(publicKey = "PUB", mldsa65Verify = validPqv),
            ),
            transport = TransportOptions.Grpc(serviceName = "svc", multiMode = true, authority = "grpc.example.com"),
        )

        val doc = XrayConfigFactory.build(listOf(xhttpNode, grpcNode), xhttpNode, AppSettings())
        val outbounds = Json.parseToJsonElement(doc.json).jsonObject["outbounds"]!!.jsonArray

        val xhOb = outbounds.first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag("xh") }.jsonObject
        val xhUser = xhOb["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals(validEncryption, xhUser["encryption"]!!.jsonPrimitive.content)

        val xhStream = xhOb["streamSettings"]!!.jsonObject
        assertEquals("ECH-CFG-DATA", xhStream["tlsSettings"]!!.jsonObject["echConfigList"]!!.jsonPrimitive.content)
        val xhSettings = xhStream["xhttpSettings"]!!.jsonObject
        val emittedExtra = xhSettings["extra"]!!.jsonObject
        assertNull(emittedExtra["host"])
        assertEquals("100-500", emittedExtra["xPaddingBytes"]!!.jsonPrimitive.content)
        assertNull(emittedExtra["headers"]!!.jsonObject["Host"])
        assertEquals("InnerAgent", emittedExtra["headers"]!!.jsonObject["User-Agent"]!!.jsonPrimitive.content)

        val dl = emittedExtra["downloadSettings"]!!.jsonObject
        assertNull(dl["tlsSettings"]!!.jsonObject["allowInsecure"])
        assertEquals("chrome", dl["tlsSettings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertNull(dl["sockopt"])
        val dlXhttp = dl["xhttpSettings"]!!.jsonObject
        assertEquals("cdn.example.com", dlXhttp["host"]!!.jsonPrimitive.content)
        assertEquals("/dl", dlXhttp["path"]!!.jsonPrimitive.content)
        assertEquals("stream-one", dlXhttp["mode"]!!.jsonPrimitive.content)

        val grOb = outbounds.first { it.jsonObject["tag"]?.jsonPrimitive?.content == XrayConfigFactory.nodeTag("gr") }.jsonObject
        val grUser = grOb["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("none", grUser["encryption"]!!.jsonPrimitive.content)
        val grStream = grOb["streamSettings"]!!.jsonObject
        assertEquals(validPqv, grStream["realitySettings"]!!.jsonObject["mldsa65Verify"]!!.jsonPrimitive.content)
        val grpcSettings = grStream["grpcSettings"]!!.jsonObject
        assertEquals(true, grpcSettings["multiMode"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("grpc.example.com", grpcSettings["authority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reality spiderX without leading slash is normalized in outbound and xhttp downloadSettings`() {
        val extra = """{"downloadSettings":{"security":"reality","realitySettings":{"publicKey":"PUB2","serverName":"dl.example.com","spiderX":"bad"}}}"""
        val cfg = build(
            node(
                tls = TlsOptions(
                    serverName = "decoy.example.com",
                    reality = RealityOptions(publicKey = "PUB", spiderX = "astral-edge-h1"),
                ),
                transport = TransportOptions.Xhttp(path = "/x", host = "cdn.example.com", extraJson = extra),
            ),
        )
        val stream = cfg.outboundNamed(XrayConfigFactory.nodeTag("n1"))["streamSettings"]!!.jsonObject
        assertEquals("/astral-edge-h1", stream["realitySettings"]!!.jsonObject["spiderX"]!!.jsonPrimitive.content)

        val dlReality = stream["xhttpSettings"]!!.jsonObject["extra"]!!
            .jsonObject["downloadSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("/bad", dlReality["spiderX"]!!.jsonPrimitive.content)
    }
}
