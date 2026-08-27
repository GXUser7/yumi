package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.parse.ProxyUriParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes the core accepts but misreads.
 *
 * A document Xray rejects is a loud failure and `XrayConfigDumpTest` plus the real binary catch it.
 * What neither catches is a document the core loads happily and then cannot act on — a host in the
 * wrong field, a credential in a section belonging to the server side, an obfuscator emitted under
 * a name nothing reads. Those produce a tunnel that connects and carries nothing, which is the
 * failure this port exists to stop repeating. So they are asserted here, field by field.
 */
class XrayConfigFactoryTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun config(uri: String, settings: AppSettings = AppSettings()): JsonObject {
        val node = requireNotNull(ProxyUriParser.parse(uri)) { "unparsable: $uri" }
        return Json.parseToJsonElement(XrayConfigFactory.build(node, settings)).jsonObject
    }

    private fun JsonObject.proxy(): JsonObject =
        this["outbounds"]!!.jsonArray.first { it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy" }
            .jsonObject

    private fun JsonObject.str(name: String): String? =
        this[name]?.jsonPrimitive?.content

    // ------------------------------------------------------------------ The host

    /**
     * `ProxyNode.address` is a display string — `"$server:$port"` — and Xray's `Address` takes any
     * string as a domain. So the port smuggled into a host field costs nothing at load time and
     * everything afterwards: there is no such name to resolve.
     */
    @Test
    fun `the host carries no port`() {
        val vless = config(
            "vless://$uuid@de.example.com:8443?security=tls#N",
        ).proxy()["settings"]!!.jsonObject["vnext"]!!.jsonArray.single().jsonObject
        assertEquals("de.example.com", vless.str("address"))
        assertEquals("8443", vless.str("port"))
    }

    @Test
    fun `no host field anywhere contains a colon`() {
        val documents = listOf(
            "vless://$uuid@de.example.com:8443?security=tls#N",
            "trojan://pass@fi.example.com:443?sni=fi.example.com#N",
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@us.example.com:8388#N",
            "hysteria2://pass@se.example.com:443?sni=se.example.com#N",
        )
        documents.forEach { uri ->
            val text = XrayConfigFactory.build(requireNotNull(ProxyUriParser.parse(uri)), AppSettings())
            val offenders = Regex("\"address\"\\s*:\\s*\"([^\"]*:[^\"]*)\"").findAll(text)
                .map { it.groupValues[1] }
                .filterNot { it.count { c -> c == ':' } > 1 } // a bare IPv6 literal is not this bug
                .toList()
            assertTrue("$uri produced address fields with a port: $offenders", offenders.isEmpty())
        }
    }

    // ------------------------------------------------------------------ Hysteria2

    /**
     * The credential is not where the protocol settings are. `infra/conf/hysteria.go:14-16` gives
     * the client exactly version, address and port; `users` is the server config. A password put
     * there is accepted, ignored, and the outbound authenticates with nothing.
     */
    @Test
    fun `the hysteria2 credential goes to the transport, not the outbound settings`() {
        val proxy = config("hysteria2://secret@se.example.com:443?sni=se.example.com#N").proxy()

        val settings = proxy["settings"]!!.jsonObject
        assertNull("users belongs to the server config", settings["users"])
        assertEquals("2", settings.str("version"))
        assertEquals("se.example.com", settings.str("address"))

        val stream = proxy["streamSettings"]!!.jsonObject
        val hysteria = stream["hysteriaSettings"]!!.jsonObject
        assertEquals("secret", hysteria.str("auth"))
        assertEquals("2", hysteria.str("version"))
    }

    /**
     * Hysteria2 runs on QUIC and has no unencrypted form. Its dialer reads the TLS config out of
     * the stream settings and refuses the connection with "tls config is nil" when `security` says
     * anything else, so a link that named no TLS has to be given it rather than obeyed.
     */
    @Test
    fun `hysteria2 gets tls even when the link never mentioned it`() {
        val stream = config("hysteria2://secret@bare.example.com:8443#N")
            .proxy()["streamSettings"]!!.jsonObject
        assertEquals("tls", stream.str("security"))
        assertEquals("bare.example.com", stream["tlsSettings"]!!.jsonObject.str("serverName"))
        assertEquals("hysteria", stream.str("network"))
    }

    /**
     * Salamander is not a field on the protocol in Xray — there is no `obfs` anywhere. It is a
     * packet mask wrapping the connection before QUIC sees it. Emitted under the name the share
     * link uses, it would be ignored, and the client would speak plain QUIC at a server expecting
     * masked packets.
     */
    @Test
    fun `salamander is emitted as a packet mask rather than an obfs field`() {
        val text = XrayConfigFactory.build(
            requireNotNull(
                ProxyUriParser.parse(
                    "hysteria2://secret@se.example.com:443?sni=se.example.com" +
                        "&obfs=salamander&obfs-password=obfspass#N",
                ),
            ),
            AppSettings(),
        )
        assertTrue("no field may be called obfs", !text.contains("\"obfs\""))

        val mask = Json.parseToJsonElement(text).jsonObject.proxy()["streamSettings"]!!
            .jsonObject["finalmask"]!!.jsonObject["udp"]!!.jsonArray.single().jsonObject
        assertEquals("salamander", mask.str("type"))
        assertEquals("obfspass", mask["settings"]!!.jsonObject.str("password"))
    }

    /** A node without obfuscation must not grow an empty masking layer. */
    @Test
    fun `a plain hysteria2 node carries no packet mask`() {
        val stream = config("hysteria2://secret@se.example.com:443?sni=se.example.com#N")
            .proxy()["streamSettings"]!!.jsonObject
        assertNull(stream["finalmask"])
    }

    // ------------------------------------------------------------------ VLESS

    /**
     * `encryption` is mandatory on every VLESS user (`infra/conf/vless.go:371-373`) and has no
     * sing-box counterpart at all, so it is exactly the field a port forgets.
     */
    @Test
    fun `every vless user declares an encryption`() {
        val user = config("vless://$uuid@de.example.com:443?security=tls&flow=xtls-rprx-vision#N")
            .proxy()["settings"]!!.jsonObject["vnext"]!!.jsonArray.single()
            .jsonObject["users"]!!.jsonArray.single().jsonObject
        assertEquals("none", user.str("encryption"))
        assertEquals(uuid, user.str("id"))
        assertEquals("xtls-rprx-vision", user.str("flow"))
    }

    /**
     * REALITY without a fingerprint presents Go's own ClientHello, which is the one signature the
     * whole arrangement exists to avoid.
     */
    @Test
    fun `reality always names a fingerprint`() {
        val reality = config(
            "vless://$uuid@de.example.com:443?security=reality&sni=www.microsoft.com" +
                "&pbk=xR8LmN2pQvT7yZ4aB6cD9eF1gH3jK5lM7nP9qR2sT4U&sid=a1b2c3d4#N",
        ).proxy()["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("chrome", reality.str("fingerprint"))
        assertEquals("www.microsoft.com", reality.str("serverName"))
    }

    // ------------------------------------------------------------------ Geo databases

    /**
     * Xray resolves `geoip:` and `geosite:` while parsing, and a reference it cannot resolve
     * rejects the whole document rather than the one rule. So on a phone that has not finished
     * downloading twenty-five megabytes of databases, the choice is between a configuration
     * without those rules and no tunnel at all.
     */
    @Test
    fun `no geo reference survives when the databases are missing`() {
        val text = XrayConfigFactory.build(
            requireNotNull(ProxyUriParser.parse("vless://$uuid@de.example.com:443?security=tls#N")),
            AppSettings(routingMode = com.mydrop.vpn.core.model.RoutingMode.Rules, blockAds = true),
            geoAvailable = false,
        )
        assertTrue("geoip: leaked into the document", !text.contains("geoip:"))
        assertTrue("geosite: leaked into the document", !text.contains("geosite:"))
    }

    /** And with them present the rules are actually there, or the flag would be meaningless. */
    @Test
    fun `the geo rules appear once the databases are there`() {
        val text = XrayConfigFactory.build(
            requireNotNull(ProxyUriParser.parse("vless://$uuid@de.example.com:443?security=tls#N")),
            AppSettings(routingMode = com.mydrop.vpn.core.model.RoutingMode.Rules, blockAds = true),
            geoAvailable = true,
        )
        assertTrue(text.contains("geosite:category-ru"))
        assertTrue(text.contains("geoip:ru"))
        assertTrue(text.contains("geosite:category-ads-all"))
    }

    /**
     * Reaching the router or a printer on the same Wi-Fi must not wait on a download. The private
     * ranges are fixed by the RFCs that reserved them, so they are written out rather than looked
     * up in a database.
     */
    @Test
    fun `the lan bypass needs no database`() {
        val text = XrayConfigFactory.build(
            requireNotNull(ProxyUriParser.parse("vless://$uuid@de.example.com:443?security=tls#N")),
            AppSettings(bypassLan = true),
            geoAvailable = false,
        )
        assertTrue(text.contains("192.168.0.0/16"))
        assertTrue(text.contains("fc00::/7"))
    }

    // ------------------------------------------------------------------ The tunnel

    /**
     * Leaving the outbound interface unset is what keeps the core off `SO_BINDTODEVICE`, which
     * needs a capability an unprivileged Android app does not have. Setting it would also register
     * a second dialer controller alongside the one that calls `VpnService.protect`.
     */
    @Test
    fun `the tun inbound never names an outbound interface`() {
        val tun = config("vless://$uuid@de.example.com:443?security=tls#N")["inbounds"]!!
            .jsonArray.single().jsonObject
        assertEquals("tun", tun.str("protocol"))
        assertNull(tun["settings"]!!.jsonObject["autoOutboundsInterface"])
    }
}
