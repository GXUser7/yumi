package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One case per defect an audit found in the parsers, so that each of them has to be re-introduced
 * deliberately rather than by accident.
 *
 * Every one of these was silent. Not a crash and not an error message — a server that appears in
 * the list, measures like the others and cannot carry traffic, or a subscription that comes up
 * short with nothing to say why. That is what makes them worth a test each: the symptom is
 * indistinguishable from "the provider is having a bad day".
 */
class AuditRegressionTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    // ------------------------------------------------------------------ credentials

    /**
     * `URLDecoder` implements form encoding, where `+` is a space. Standard base64 uses `+` as one
     * of its sixty-four characters, and a WireGuard or Shadowsocks-2022 key contains one about half
     * the time — so half of those keys arrived with a space in the middle and could not
     * authenticate.
     */
    @Test
    fun `a plus sign in a key survives the parser`() {
        val node = requireNotNull(
            ProxyUriParser.parse("trojan://pa+ss/word@example.com:443?security=tls#N"),
        )
        assertEquals("pa+ss/word", (node.settings as ProxySettings.Trojan).password)
    }

    @Test
    fun `percent escapes are still decoded, including multi-byte ones`() {
        val node = requireNotNull(
            ProxyUriParser.parse("trojan://p%40ss%20word@example.com:443?security=tls#%D0%A1%D0%B5%D1%80%D0%B2%D0%B5%D1%80"),
        )
        assertEquals("p@ss word", (node.settings as ProxySettings.Trojan).password)
        assertEquals("Сервер", node.name)
    }

    /**
     * The printable-ASCII test threw away every password with a non-Latin character, and the caller
     * then used the *raw base64* as the password — credentials nobody had ever set.
     */
    @Test
    fun `a non-ascii password survives the base64 heuristic`() {
        val raw = "aes-256-gcm:пароль"
        val encoded = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        val node = requireNotNull(ProxyUriParser.parse("ss://$encoded@example.com:443#N"))
        assertEquals("пароль", (node.settings as ProxySettings.Shadowsocks).password)
    }

    // ------------------------------------------------------------------ REALITY keys

    /**
     * The core decodes `publicKey` as URL-safe base64 and rejects the whole document when it
     * cannot — so a provider using the standard alphabet took the tunnel down for every server,
     * not just its own.
     */
    @Test
    fun `a reality key in the standard alphabet is normalised`() {
        val node = requireNotNull(
            ProxyUriParser.parse("vless://$uuid@example.com:443?security=reality&pbk=ab+cd/ef=&sid=1a#N"),
        )
        assertEquals("ab-cd_ef", node.tls?.reality?.publicKey)
    }

    @Test
    fun `a reality key already url-safe is left alone`() {
        val node = requireNotNull(
            ProxyUriParser.parse("vless://$uuid@example.com:443?security=reality&pbk=ab-cd_ef#N"),
        )
        assertEquals("ab-cd_ef", node.tls?.reality?.publicKey)
    }

    // ------------------------------------------------------------------ links

    /**
     * The query was cut off before the payload was read, which is right for a base64 payload and
     * wrong for a plain URL — the subscription was saved without its token and answered 401 for
     * ever after.
     */
    @Test
    fun `a deep link keeps the subscription token`() {
        val payload = DeepLinkParser.parse("mydrop://add/https://panel.example.com/sub?token=SECRET")
        val subscription = payload as DeepLinkPayload.AddSubscription
        assertEquals("https://panel.example.com/sub?token=SECRET", subscription.url)
    }

    @Test
    fun `the app's own name parameter is still taken out of the link`() {
        val payload = DeepLinkParser.parse(
            "mydrop://add/https://panel.example.com/sub?token=SECRET&name=Home",
        )
        val subscription = payload as DeepLinkPayload.AddSubscription
        assertEquals("https://panel.example.com/sub?token=SECRET", subscription.url)
        assertEquals("Home", subscription.name)
    }

    /**
     * Only the trailing slash was trimmed, so a path in the authority reached the host/port split
     * and failed it — and trojan links with a path are ordinary.
     */
    @Test
    fun `a link with a path in the authority is still a server`() {
        val node = requireNotNull(
            ProxyUriParser.parse("trojan://password@example.com:443/some/path?security=tls#N"),
        )
        assertEquals("example.com", node.server)
        assertEquals(443, node.port)
    }

    // ------------------------------------------------------------------ documents

    /** A bare list of outbounds is one of the two shapes an array can be, and it imported as none. */
    @Test
    fun `a flat array of outbounds yields its servers`() {
        val document = """
            [{"type":"vless","tag":"one","server":"a.example.com","server_port":443,
              "uuid":"$uuid"},
             {"type":"trojan","tag":"two","server":"b.example.com","server_port":8443,
              "password":"p"}]
        """.trimIndent().replace("\n", "")

        val nodes = ConfigDocumentParser.parse(document, "sub")
        assertEquals(listOf("one", "two"), nodes.map { it.name })
    }

    /**
     * `jsonObject` and `jsonPrimitive` throw on an element of the wrong kind. A provider whose
     * template rendered a port as an object took the entire subscription refresh down with it.
     */
    @Test
    fun `an entry of the wrong shape is skipped rather than throwing`() {
        val document = """
            {"outbounds":[{"type":"vless","tag":"broken","server":"a.example.com",
              "server_port":{"oops":1},"uuid":"$uuid"},
             {"type":"trojan","tag":"fine","server":"b.example.com","server_port":8443,
              "password":"p"}]}
        """.trimIndent().replace("\n", "")

        val nodes = ConfigDocumentParser.parse(document, "sub")
        assertEquals(listOf("fine"), nodes.map { it.name })
    }

    @Test
    fun `a settings block of the wrong shape does not throw either`() {
        val document = """
            {"outbounds":[{"protocol":"vless","settings":"not-an-object"},
             {"protocol":"trojan","settings":{"servers":[{"address":"b.example.com",
              "port":8443,"password":"p"}]},"tag":"fine"}]}
        """.trimIndent().replace("\n", "")

        assertEquals(1, ConfigDocumentParser.parse(document, "sub").size)
    }

    @Test
    fun `a port outside the range is refused everywhere it can be written`() {
        val json = """
            {"outbounds":[{"type":"vless","tag":"zero","server":"a.example.com",
              "server_port":0,"uuid":"$uuid"}]}
        """.trimIndent().replace("\n", "")
        assertTrue(ConfigDocumentParser.parse(json, "sub").isEmpty())

        val clash = """
            proxies:
              - name: huge
                type: trojan
                server: example.com
                port: 99999
                password: p
        """.trimIndent()
        assertTrue(ClashParser.parse(clash, "sub").isEmpty())
    }

    // ------------------------------------------------------------------ Clash

    /**
     * Trojan has no plaintext form, so Clash configs leave `tls: true` off. Reading the omission as
     * "no TLS" produced an outbound that cannot complete a handshake with any trojan server.
     */
    @Test
    fun `a clash trojan entry gets tls even when the flag is missing`() {
        val document = """
            proxies:
              - name: tr
                type: trojan
                server: example.com
                port: 443
                password: secret
                sni: decoy.example.com
        """.trimIndent()

        val node = ClashParser.parse(document, "sub").single()
        assertNotNull("trojan is TLS by definition", node.tls)
        assertEquals("decoy.example.com", node.tls?.serverName)
    }

    /** `toPair` promised to strip trailing comments and never did, so the host became undialable. */
    @Test
    fun `a trailing comment is stripped from a clash value`() {
        val document = """
            proxies:
              - name: commented
                type: trojan
                server: example.com # the main one
                port: 443
                password: secret
        """.trimIndent()

        val node = ClashParser.parse(document, "sub").single()
        assertEquals("example.com", node.server)
    }

    /** …but a `#` that is part of a value is not a comment. */
    @Test
    fun `a hash inside a password is not treated as a comment`() {
        val document = """
            proxies:
              - name: hashy
                type: trojan
                server: example.com
                port: 443
                password: pa#ssword
        """.trimIndent()

        val node = ClashParser.parse(document, "sub").single()
        assertEquals("pa#ssword", (node.settings as ProxySettings.Trojan).password)
    }

    /** A plugin with no options is a plugin with no mode, which cannot work. */
    @Test
    fun `clash shadowsocks plugin options are carried`() {
        val document = """
            proxies:
              - name: ss-obfs
                type: ss
                server: example.com
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: obfs
                plugin-opts: {mode: tls, host: cdn.example.com}
        """.trimIndent()

        val settings = ClashParser.parse(document, "sub").single().settings as ProxySettings.Shadowsocks
        assertEquals("obfs", settings.plugin)
        assertTrue(settings.pluginOptions.contains("mode=tls"))
        assertTrue(settings.pluginOptions.contains("host=cdn.example.com"))
    }

    // ------------------------------------------------------------------ transports

    @Test
    fun `a websocket node keeps its path and host across the whole chain`() {
        val node: ProxyNode = requireNotNull(
            ProxyUriParser.parse(
                "vless://$uuid@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com#N",
            ),
        )
        val transport = node.transport as TransportOptions.WebSocket
        assertEquals("/ws", transport.path)
        assertEquals("cdn.example.com", transport.headers["Host"])
    }

    @Test
    fun `a resolver this core cannot query is refused rather than downgraded`() {
        assertNull(DnsUriParser.parse("tls://dns.example.com"))
        assertNotNull(DnsUriParser.parse("https://dns.example.com/dns-query"))
    }
}
