package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TransportOptions
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.util.Base64

/**
 * Adversarial and empirical stress tests for URI parsing and Sing-box JSON configuration generation.
 * Challenges edge cases across VLESS Reality, VMess, Trojan, Shadowsocks, Hysteria2, TUIC,
 * casing permutations, special characters in SNI, empty short_id, missing fingerprints,
 * and DPI-resilient DNS direct bootstrap.
 */
class AdversarialStressTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private fun config(node: ProxyNode, settings: AppSettings = AppSettings()): JsonObject =
        json.parseToJsonElement(SingBoxConfigFactory.build(node, settings, "/rule-sets")).jsonObject

    private val JsonObject.outbounds: JsonArray get() = this["outbounds"]!!.jsonArray
    private val JsonObject.proxy: JsonObject
        get() = outbounds.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }

    // =========================================================================
    // 1. VLESS Reality Edge Cases & Casing Permutations
    // =========================================================================

    @Test
    fun `vless reality with all uppercase query parameter keys parses and emits correct config`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@node.vless.monster:443" +
            "?SECURITY=REALITY&SERVER_NAME=learn.microsoft.com&PUBLIC_KEY=PUB_KEY_UPPER" +
            "&SHORT_ID=a1b2c3d4&SPIDER_X=%2Fcustom&CLIENT_FINGERPRINT=firefox" +
            "&PACKET_ENCODING=packetaddr&FLOW=xtls-rprx-vision#UpperReality"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("node.vless.monster", node.server)
        assertEquals(443, node.port)
        assertEquals("UpperReality", node.name)

        val settings = node.settings as ProxySettings.Vless
        assertEquals("11111111-2222-3333-4444-555555555555", settings.uuid)
        assertEquals("xtls-rprx-vision", settings.flow)
        assertEquals("packetaddr", settings.packetEncoding)

        val tls = requireNotNull(node.tls)
        assertEquals("learn.microsoft.com", tls.serverName)
        assertEquals("firefox", tls.fingerprint)
        val reality = requireNotNull(tls.reality)
        assertEquals("PUB_KEY_UPPER", reality.publicKey)
        assertEquals("a1b2c3d4", reality.shortId)
        assertEquals("/custom", reality.spiderX)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("vless", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("node.vless.monster", proxy["server"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-3333-4444-555555555555", proxy["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", proxy["flow"]!!.jsonPrimitive.content)

        val tlsObj = proxy["tls"]!!.jsonObject
        assertEquals("learn.microsoft.com", tlsObj["server_name"]!!.jsonPrimitive.content)
        assertEquals("firefox", tlsObj["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        val realityObj = tlsObj["reality"]!!.jsonObject
        assertEquals("PUB_KEY_UPPER", realityObj["public_key"]!!.jsonPrimitive.content)
        assertEquals("a1b2c3d4", realityObj["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless reality with mixed case keys and alternative aliases`() {
        val uri = "vless://user-uuid@sub.domain.org:8443" +
            "?Security=Reality&ServerName=www.google.com&PubKey=MIXED_PUB" +
            "&ShortId=1234abcd&Spx=%2Ftest%2Fpath&Fp=safari#MixedCaseNode"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        val tls = requireNotNull(node.tls)
        assertEquals("www.google.com", tls.serverName)
        assertEquals("safari", tls.fingerprint)
        assertEquals("MIXED_PUB", tls.reality?.publicKey)
        assertEquals("1234abcd", tls.reality?.shortId)
        assertEquals("/test/path", tls.reality?.spiderX)
    }

    @Test
    fun `vless reality with empty short_id succeeds and omits short_id in sing-box config`() {
        val uri = "vless://user-uuid@proxy.domain.net:443" +
            "?security=reality&sni=decoy.com&pbk=MYKEY&sid=&fp=chrome#NoShortId"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        val reality = requireNotNull(node.tls?.reality)
        assertEquals("MYKEY", reality.publicKey)
        assertEquals("", reality.shortId)

        val cfg = config(node)
        val realityObj = cfg.proxy["tls"]!!.jsonObject["reality"]!!.jsonObject
        assertEquals("MYKEY", realityObj["public_key"]!!.jsonPrimitive.content)
        assertNull("short_id must be omitted when empty", realityObj["short_id"])
    }

    @Test
    fun `vless reality without sid parameter defaults to empty shortId and omits in JSON`() {
        val uri = "vless://user-uuid@proxy.domain.net:443" +
            "?security=reality&sni=decoy.com&pbk=MYKEY#OmittedShortId"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals("", node.tls?.reality?.shortId)

        val cfg = config(node)
        val realityObj = cfg.proxy["tls"]!!.jsonObject["reality"]!!.jsonObject
        assertNull(realityObj["short_id"])
    }

    @Test
    fun `vless reality missing fingerprint defaults to utls chrome in sing-box config`() {
        val uri = "vless://user-uuid@proxy.domain.net:443" +
            "?security=reality&sni=decoy.com&pbk=MYKEY&sid=1122#NoFp"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertNull(node.tls?.fingerprint)

        val cfg = config(node)
        val utlsObj = cfg.proxy["tls"]!!.jsonObject["utls"]!!.jsonObject
        assertEquals(true, utlsObj["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("chrome", utlsObj["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless reality with unknown fingerprint is sanitized to chrome`() {
        val uri = "vless://user-uuid@proxy.domain.net:443" +
            "?security=reality&sni=decoy.com&pbk=MYKEY&fp=some_invalid_fp#BadFp"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals("some_invalid_fp", node.tls?.fingerprint)

        val cfg = config(node)
        val utlsObj = cfg.proxy["tls"]!!.jsonObject["utls"]!!.jsonObject
        assertEquals("chrome", utlsObj["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless reality with all supported utls fingerprints preserves each fingerprint`() {
        val fingerprints = listOf(
            "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android",
            "random", "randomized", "chrome_psk", "chrome_pq",
        )
        fingerprints.forEach { fp ->
            val uri = "vless://user-uuid@proxy.domain.net:443?security=reality&sni=decoy.com&pbk=KEY&fp=$fp#FpTest"
            val node = requireNotNull(ProxyUriParser.parse(uri))
            assertEquals(fp, node.tls?.fingerprint)

            val cfg = config(node)
            val emittedFp = cfg.proxy["tls"]!!.jsonObject["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content
            assertEquals("fingerprint  should be emitted verbatim", fp, emittedFp)
        }
    }

    @Test
    fun `vless reality with punycode and hyphenated special characters in SNI`() {
        val uri = "vless://uuid@proxy.com:443?security=reality&sni=xn--e1afmkfd.xn--p1ai&pbk=KEY&sid=ab#Punycode"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals("xn--e1afmkfd.xn--p1ai", node.tls?.serverName)

        val cfg = config(node)
        assertEquals("xn--e1afmkfd.xn--p1ai", cfg.proxy["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless reality preserves proxy domain and never replaces reality sni with proxy host`() {
        val uri = "vless://uuid@de-01.vless.monster:443?security=reality&server_name=learn.microsoft.com&pbk=PUBKEY&sid=12#DecoyTest"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals("de-01.vless.monster", node.server)
        assertEquals("learn.microsoft.com", node.tls?.serverName)

        val cfg = config(node)
        assertEquals("de-01.vless.monster", cfg.proxy["server"]!!.jsonPrimitive.content)
        assertEquals("learn.microsoft.com", cfg.proxy["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless reality with URL-encoded parameters in query and fragment`() {
        val uri = "vless://uuid%2Dencoded%40val@proxy.server.com:443" +
            "?security=reality&server_name=decoy%2Dsite.example.com&pbk=PUB%2BKEY%2F123" +
            "&sid=ab%3Acd&spx=%2Fdeep%2Fpath%3Fkey%3Dval&flow=xtls%2Drprx%2Dvision" +
            "#%D0%A1%D0%B5%D1%80%D0%B2%D0%B5%D1%80%201%20(100%25)"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals("Сервер 1 (100%)", node.name)
        assertEquals("uuid-encoded@val", (node.settings as ProxySettings.Vless).uuid)
        assertEquals("xtls-rprx-vision", (node.settings as ProxySettings.Vless).flow)
        assertEquals("decoy-site.example.com", node.tls?.serverName)
        assertEquals("PUB+KEY/123", node.tls?.reality?.publicKey)
        assertEquals("ab:cd", node.tls?.reality?.shortId)
        assertEquals("/deep/path?key=val", node.tls?.reality?.spiderX)
    }

    // =========================================================================
    // 2. VMess Edge Cases (Base64 JSON & URI)
    // =========================================================================

    @Test
    fun `vmess base64 json with integer and string field permutations`() {
        val jsonPayload = """
            {
                "v": "2",
                "ps": "VMess Int Test",
                "add": "vmess.example.org",
                "port": 443,
                "id": "11112222-3333-4444-5555-666677778888",
                "aid": 0,
                "scy": "zero",
                "net": "ws",
                "type": "none",
                "host": "ws-host.com",
                "path": "/ws-path",
                "tls": "tls",
                "sni": "sni.vmess.com",
                "alpn": "h2,http/1.1",
                "fp": "chrome",
                "allowInsecure": "1"
            }
        """.trimIndent()
        val link = "vmess://" + Base64.getEncoder().encodeToString(jsonPayload.toByteArray())

        val node = requireNotNull(ProxyUriParser.parse(link))
        assertEquals(Protocol.VMESS, node.protocol)
        assertEquals("vmess.example.org", node.server)
        assertEquals(443, node.port)
        assertEquals("VMess Int Test", node.name)

        val settings = node.settings as ProxySettings.Vmess
        assertEquals("11112222-3333-4444-5555-666677778888", settings.uuid)
        assertEquals(0, settings.alterId)
        assertEquals("zero", settings.security)

        val transport = node.transport as TransportOptions.WebSocket
        assertEquals("/ws-path", transport.path)
        assertEquals("ws-host.com", transport.headers["Host"])

        val tls = requireNotNull(node.tls)
        assertEquals("sni.vmess.com", tls.serverName)
        assertEquals(listOf("h2", "http/1.1"), tls.alpn)
        assertEquals("chrome", tls.fingerprint)
        assertTrue(tls.insecure)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("vmess", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("zero", proxy["security"]!!.jsonPrimitive.content)
        assertEquals(0, proxy["alter_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `vmess base64 json unpadded and url-safe base64 decodes reliably`() {
        val jsonPayload = """{"v":"2","ps":"URLSafe","add":"safe.org","port":"8080","id":"u1","aid":"4","net":"grpc","path":"grpc-svc","tls":""}"""
        val rawBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonPayload.toByteArray())
        val link = "vmess://$rawBase64"

        val node = requireNotNull(ProxyUriParser.parse(link))
        assertEquals("safe.org", node.server)
        assertEquals(8080, node.port)
        assertEquals(4, (node.settings as ProxySettings.Vmess).alterId)
        assertEquals("grpc-svc", (node.transport as TransportOptions.Grpc).serviceName)
        assertNull(node.tls)
    }

    @Test
    fun `vmess uri format with uppercase query parameters`() {
        val uri = "vmess://user-uuid@vmess.node.net:443?SECURITY=TLS&SNI=vmess-decoy.com&TYPE=HTTPUPGRADE&PATH=%2Fupg&HOST=upg-host.com#VmessUri"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.VMESS, node.protocol)
        assertEquals("vmess-decoy.com", node.tls?.serverName)
        val transport = node.transport as TransportOptions.HttpUpgrade
        assertEquals("/upg", transport.path)
        assertEquals("upg-host.com", transport.host)
    }

    // =========================================================================
    // 3. Trojan Edge Cases
    // =========================================================================

    @Test
    fun `trojan with reality parameters and uppercase query keys`() {
        val uri = "trojan://p%40ss%3A123@trojan.monster.net:443" +
            "?SECURITY=REALITY&SERVER_NAME=decoy.trojan.com&PUBLIC_KEY=TR_PUB" +
            "&SHORT_ID=tr_sid&SPIDER_X=%2Ftr_path&CLIENT_FINGERPRINT=edge#TrojanReality"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.TROJAN, node.protocol)
        assertEquals("p@ss:123", (node.settings as ProxySettings.Trojan).password)
        assertEquals("trojan.monster.net", node.server)

        val tls = requireNotNull(node.tls)
        assertEquals("decoy.trojan.com", tls.serverName)
        assertEquals("edge", tls.fingerprint)
        assertEquals("TR_PUB", tls.reality?.publicKey)
        assertEquals("tr_sid", tls.reality?.shortId)
        assertEquals("/tr_path", tls.reality?.spiderX)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("trojan", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("p@ss:123", proxy["password"]!!.jsonPrimitive.content)
        assertEquals("trojan.monster.net", proxy["server"]!!.jsonPrimitive.content)
        assertEquals("decoy.trojan.com", proxy["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("TR_PUB", proxy["tls"]!!.jsonObject["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan implicit tls defaults on even when security parameter omitted`() {
        val uri = "trojan://mypass@tr.server.org:443?type=ws&host=tr-ws.org&path=/ws#ImplicitTls"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        val tls = requireNotNull(node.tls)
        assertTrue(tls.enabled)
        assertEquals("tr-ws.org", tls.serverName)
    }

    // =========================================================================
    // 4. Shadowsocks Edge Cases
    // =========================================================================

    @Test
    fun `shadowsocks sip002 with url-safe unpadded base64 and complex password`() {
        val creds = "chacha20-ietf-poly1305:P%40ss:w0rd!#$"
        val encodedCreds = Base64.getUrlEncoder().withoutPadding().encodeToString(creds.toByteArray())
        val uri = "ss://$encodedCreds@ss.example.com:8388?plugin=v2ray-plugin%3Bserver%3Bhost%3Dcdn.com#SIP002"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.SHADOWSOCKS, node.protocol)
        val settings = node.settings as ProxySettings.Shadowsocks
        assertEquals("chacha20-ietf-poly1305", settings.method)
        assertEquals("P%40ss:w0rd!#$", settings.password)
        assertEquals("v2ray-plugin", settings.plugin)
        assertEquals("server;host=cdn.com", settings.pluginOptions)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("shadowsocks", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("chacha20-ietf-poly1305", proxy["method"]!!.jsonPrimitive.content)
        assertEquals("v2ray-plugin", proxy["plugin"]!!.jsonPrimitive.content)
        assertEquals("server;host=cdn.com", proxy["plugin_opts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shadowsocks legacy format with complex credentials and name fragment`() {
        val raw = "aes-128-gcm:pass:with:colons@legacy.ss.org:8443"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
        val uri = "ss://$encoded#%D0%A1%D0%A1%20%D0%A1%D0%B5%D1%80%D0%B2%D0%B5%D1%80"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        val settings = node.settings as ProxySettings.Shadowsocks
        assertEquals("aes-128-gcm", settings.method)
        assertEquals("pass:with:colons", settings.password)
        assertEquals("legacy.ss.org", node.server)
        assertEquals(8443, node.port)
        assertEquals("СС Сервер", node.name)
    }

    // =========================================================================
    // 5. Hysteria2 & Hysteria Edge Cases
    // =========================================================================

    @Test
    fun `hysteria2 with uppercase query keys, obfs and bandwidth limits`() {
        val uri = "hy2://secret-pass@hy2.server.com:443" +
            "?SNI=hy2-decoy.com&OBFS=SALAMANDER&OBFS_PASSWORD=obfs_pwd" +
            "&UP_MBPS=100&DOWN_MBPS=250&INSECURE=1#Hy2Node"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.HYSTERIA2, node.protocol)
        val settings = node.settings as ProxySettings.Hysteria2
        assertEquals("secret-pass", settings.password)
        assertEquals("SALAMANDER", settings.obfsType)
        assertEquals("obfs_pwd", settings.obfsPassword)
        assertEquals(100, settings.upMbps)
        assertEquals(250, settings.downMbps)
        assertTrue(node.tls!!.insecure)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("hysteria2", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(100, proxy["up_mbps"]!!.jsonPrimitive.int)
        assertEquals(250, proxy["down_mbps"]!!.jsonPrimitive.int)
        assertEquals("SALAMANDER", proxy["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("obfs_pwd", proxy["obfs"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        assertTrue(proxy["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `hysteria legacy with auth_str and obfs parameters`() {
        val uri = "hysteria://hy.server.net:443?auth=my-auth-token&obfs=my-obfs&up=50&down=100&sni=hy-sni.com#Hy1"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.HYSTERIA, node.protocol)
        val settings = node.settings as ProxySettings.Hysteria
        assertEquals("my-auth-token", settings.auth)
        assertEquals("my-obfs", settings.obfs)
        assertEquals(50, settings.upMbps)
        assertEquals(100, settings.downMbps)
    }

    // =========================================================================
    // 6. TUIC Edge Cases
    // =========================================================================

    @Test
    fun `tuic with uppercase parameters, congestion control, and zero rtt`() {
        val uri = "tuic://tuic-uuid:tuic-pass@tuic.server.org:443" +
            "?CONGESTION_CONTROL=cubic&UDP_RELAY_MODE=quic&ZERO_RTT_HANDSHAKE=1" +
            "&ALPN=h3&SNI=tuic-decoy.com#TuicStress"

        val node = requireNotNull(ProxyUriParser.parse(uri))
        assertEquals(Protocol.TUIC, node.protocol)
        val settings = node.settings as ProxySettings.Tuic
        assertEquals("tuic-uuid", settings.uuid)
        assertEquals("tuic-pass", settings.password)
        assertEquals("cubic", settings.congestionControl)
        assertEquals("quic", settings.udpRelayMode)
        assertTrue(settings.zeroRttHandshake)

        val cfg = config(node)
        val proxy = cfg.proxy
        assertEquals("tuic", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("tuic-uuid", proxy["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuic-pass", proxy["password"]!!.jsonPrimitive.content)
        assertEquals("cubic", proxy["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("quic", proxy["udp_relay_mode"]!!.jsonPrimitive.content)
        assertTrue(proxy["zero_rtt_handshake"]!!.jsonPrimitive.content.toBoolean())
    }

    // =========================================================================
    // 7. DPI-Resilient DNS Direct & Bootstrap Verification
    // =========================================================================

    @Test
    fun `domain named proxy node with blank directDns falls back to a resolver that actually answers`() {
        // This used to assert the opposite — that a blank field defaults to "local" and that no
        // raw UDP 8.8.8.8 appears anywhere. Both were wrong on this platform, checked on a real
        // device: nothing implements PlatformInterface.localDNSTransport() (it returns null in
        // MyDropVpnService), /etc/resolv.conf does not exist on Android, and sing-box's own
        // fallback for that case is 127.0.0.1:53 — which nothing answers. So "local" was not a
        // more private resolver than 8.8.8.8, it was a dead one: default_domain_resolver would
        // point at it for every proxy server named by domain, and none of them could ever
        // resolve. See SingBoxConfigFactory.DEFAULT_DIRECT_DNS for the full account.
        val uri = "vless://uuid@my-proxy-node.vless.monster:443?security=reality&sni=learn.microsoft.com&pbk=KEY#Monster"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        val cfg = config(node, AppSettings(directDns = ""))

        val servers = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("udp", direct["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfigFactory.DEFAULT_DIRECT_DNS, direct["server"]!!.jsonPrimitive.content)

        val defaultDomainResolver = cfg["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content
        assertEquals("dns-direct", defaultDomainResolver)
    }

    @Test
    fun `custom doh direct dns bootstraps with a working numeric resolver, not the dead local one`() {
        val uri = "vless://uuid@my-proxy-node.vless.monster:443?security=tls#Node"
        val node = requireNotNull(ProxyUriParser.parse(uri))
        val cfg = config(node, AppSettings(directDns = "https://my-doh.com/dns-query"))

        val servers = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val direct = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertEquals("https", direct["type"]!!.jsonPrimitive.content)
        assertEquals("my-doh.com", direct["server"]!!.jsonPrimitive.content)
        assertEquals("dns-bootstrap", direct["domain_resolver"]!!.jsonPrimitive.content)

        // Not "local" — see DEFAULT_DIRECT_DNS. The one lookup that bootstraps a named direct
        // resolver has to go somewhere real, and on this platform "somewhere real" is numeric.
        val bootstrap = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-bootstrap" }
        assertEquals("udp", bootstrap["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfigFactory.DEFAULT_DIRECT_DNS, bootstrap["server"]!!.jsonPrimitive.content)

        val defaultDomainResolver = cfg["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content
        assertEquals("dns-bootstrap", defaultDomainResolver)
    }

    // =========================================================================
    // 8. Unsupported and Malformed URI Refusal Verification
    // =========================================================================

    @Test
    fun `unsupported transports such as xhttp or splithttp are rejected cleanly`() {
        val xhttpUri = "vless://uuid@proxy.com:443?security=reality&type=xhttp&pbk=KEY#XHttp"
        assertNull("xhttp transport must be rejected at parse time", ProxyUriParser.parse(xhttpUri))

        val splitHttpUri = "vless://uuid@proxy.com:443?security=reality&type=splithttp&pbk=KEY#SplitHttp"
        assertNull("splithttp transport must be rejected at parse time", ProxyUriParser.parse(splitHttpUri))

        val counts = ProxyUriParser.unsupportedTransports(listOf(xhttpUri, splitHttpUri).joinToString("\n"))
        assertEquals(1, counts["xhttp"])
        assertEquals(1, counts["splithttp"])
    }

    @Test
    fun `missing ports and empty credentials are rejected cleanly`() {
        assertNull(ProxyUriParser.parse("vless://@server.com:443"))
        assertNull(ProxyUriParser.parse("trojan://@server.com:443"))
        assertNull(ProxyUriParser.parse("vless://uuid@server.com"))
        assertNull(ProxyUriParser.parse("not-a-uri"))
    }
}
