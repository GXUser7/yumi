package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import com.mydrop.vpn.core.xray.XrayConfigFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transport the core cannot speak has to make the node disappear — but *which* core is asking has
 * become the interesting part, and that is what this file is now about.
 *
 * It was written from a real failure. A subscription started handing out `type=xhttp` nodes; the
 * parser did not recognise the name, dropped the transport, and produced an ordinary-looking VLESS
 * server. It appeared in the list with a normal latency, the tunnel came up on it, and then every
 * single connection failed with `reality verification failed` — three hundred and fifty of them in
 * forty seconds — because the server was framing XHTTP at a client sending plain TLS.
 *
 * The fix at the time was to refuse the line at import, because sing-box implements five stream
 * transports (HTTP, WebSocket, QUIC, gRPC, HTTPUpgrade) and no amount of parsing would make XHTTP
 * one of them. Xray does implement it, so that refusal has moved: the parser now understands every
 * transport the app can *represent*, and each config factory refuses whatever its own core cannot
 * carry. The two disagree in both directions — Xray removed `http`/`h2` and `quic` outright
 * (`infra/conf/transport_internet.go:33-36`) — so the refusals are asserted per factory below.
 *
 * What has not changed is the rule: refuse loudly, never drop silently.
 */
class UnsupportedTransportTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun vless(query: String) =
        ProxyUriParser.parse("vless://$uuid@example.com:443?security=tls&$query#N")

    // ------------------------------------------------------------------ Parsing

    @Test
    fun `xhttp is understood rather than silently stripped of its transport`() {
        val node = requireNotNull(vless("type=xhttp&path=%2Fx&host=h.example.com"))
        val transport = node.transport as TransportOptions.Xhttp
        assertEquals("/x", transport.path)
        assertEquals("h.example.com", transport.host)
    }

    /** Xray answers to both names, so a link written either way has to survive import. */
    @Test
    fun `splithttp is the same transport under its other name`() {
        assertTrue(vless("type=splithttp&path=%2Fx")?.transport is TransportOptions.Xhttp)
    }

    @Test
    fun `mode is carried through, and defaulted rather than invented`() {
        assertEquals(
            "stream-one",
            (vless("type=xhttp&mode=stream-one")?.transport as TransportOptions.Xhttp).mode,
        )
        assertEquals("auto", (vless("type=xhttp")?.transport as TransportOptions.Xhttp).mode)
    }

    /**
     * Still refused at import, and for the original reason: these are transports the app has no way
     * to *represent*, so accepting the line could only produce a node stripped of the very thing
     * that makes it work.
     */
    @Test
    fun `transports the app cannot represent are still refused`() {
        assertNull(vless("type=kcp"))
        assertNull(vless("type=mkcp"))
        assertNull(vless("type=meek"))
    }

    @Test
    fun `the transports the app does model still parse`() {
        assertTrue(vless("type=ws&path=%2Fws")?.transport is TransportOptions.WebSocket)
        assertTrue(vless("type=grpc&serviceName=s")?.transport is TransportOptions.Grpc)
        assertTrue(vless("type=http")?.transport is TransportOptions.Http)
        assertTrue(vless("type=httpupgrade")?.transport is TransportOptions.HttpUpgrade)
        assertEquals(TransportOptions.Quic, vless("type=quic")?.transport)
    }

    /** `tcp` is not a wrapper, it is the absence of one, and those nodes are the common case. */
    @Test
    fun `plain tcp keeps working and carries no transport`() {
        val node = requireNotNull(vless("type=tcp&flow=xtls-rprx-vision"))
        assertNull(node.transport)
        assertNotNull(vless("type=raw"))
        assertNotNull(vless("type=none"))
    }

    @Test
    fun `a link naming no transport at all is still a server`() {
        val node = requireNotNull(ProxyUriParser.parse("vless://$uuid@example.com:443?security=tls"))
        assertNull(node.transport)
    }

    /**
     * The trap this check has to avoid. Hysteria2 puts its obfuscator in `obfs`, and an earlier
     * shape of this read `type`, `net` and `obfs` as one field — which would have made every
     * obfuscated Hysteria2 node look like a request for a transport called "salamander".
     */
    @Test
    fun `hysteria2 obfuscation is not mistaken for a stream transport`() {
        val node = ProxyUriParser.parse(
            "hysteria2://password@example.com:443?sni=example.com" +
                "&obfs=salamander&obfs-password=secret#N",
        )
        val settings = requireNotNull(node).settings as ProxySettings.Hysteria2
        assertEquals("salamander", settings.obfsType)
    }

    // ------------------------------------------------------------------ Other document formats

    @Test
    fun `a clash entry naming a transport the app cannot represent is skipped`() {
        val document = """
            proxies:
              - name: broken
                type: vless
                server: example.com
                port: 443
                uuid: $uuid
                network: kcp
              - name: xh
                type: vless
                server: example.com
                port: 443
                uuid: $uuid
                network: xhttp
              - name: fine
                type: vless
                server: example.com
                port: 8443
                uuid: $uuid
                network: ws
        """.trimIndent()

        assertEquals(listOf("xh", "fine"), ClashParser.parse(document, "sub").map { it.name })
    }

    @Test
    fun `an xray outbound naming xhttp is read, and its settings with it`() {
        val document = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"example.com",
            "port":443,"users":[{"id":"$uuid"}]}]},"streamSettings":{"network":"xhttp",
            "xhttpSettings":{"path":"/x","mode":"packet-up"},
            "security":"reality","realitySettings":{"publicKey":"k"}}}]}
        """.trimIndent().replace("\n", "")

        val node = ConfigDocumentParser.parse(document, "sub").single()
        val transport = node.transport as TransportOptions.Xhttp
        assertEquals("/x", transport.path)
        assertEquals("packet-up", transport.mode)
    }

    // ------------------------------------------------------------------ What each core refuses

    /**
     * sing-box has no XHTTP and is not getting one, so the refusal has to happen before a document
     * is handed to it — loudly, because the alternative is the failure this whole file is named
     * after.
     */
    @Test
    fun `the sing-box factory refuses xhttp rather than emitting a node without it`() {
        val node = requireNotNull(vless("type=xhttp&path=%2Fx"))
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxConfigFactory.build(node, AppSettings(), ruleSetDir = "")
        }
    }

    /**
     * And the mirror image. Xray removed the HTTP and QUIC transports outright, so on that core it
     * is these nodes that cannot be carried — the same rule, pointing the other way.
     */
    @Test
    fun `the xray factory refuses the transports xray removed`() {
        val http = requireNotNull(vless("type=http"))
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigFactory.build(http, AppSettings())
        }
        val quic = requireNotNull(vless("type=quic"))
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigFactory.build(quic, AppSettings())
        }
    }

    /** Protocols Xray does not implement at all, refused for the same reason in the same place. */
    @Test
    fun `the xray factory refuses protocols xray does not implement`() {
        val tuic = requireNotNull(
            ProxyUriParser.parse("tuic://$uuid:pass@example.com:443?sni=example.com#N"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigFactory.build(tuic, AppSettings())
        }
    }
}
