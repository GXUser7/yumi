package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transport the core cannot speak has to make the whole node disappear — and one it *can* speak
 * has to survive.
 *
 * This file is written from a real failure, and the failure is also why the app changed cores. A
 * subscription started handing out `type=xhttp` nodes; the parser did not recognise the name,
 * dropped the transport, and produced an ordinary-looking VLESS server. It appeared in the list
 * with a normal latency, the tunnel came up on it, and then every connection failed with
 * `reality verification failed` — three hundred and fifty of them in forty seconds — because the
 * server was framing XHTTP at a client sending plain TLS.
 *
 * The first answer was to refuse those links at import, because sing-box implements five stream
 * transports and XHTTP is not among them and never will be. The second answer was to move to a core
 * that does implement it, which is where these tests now point: XHTTP parses, and the transports
 * Xray *removed* — `http`/`h2` and `quic` — are refused in its place.
 *
 * The rule underneath has not moved at all: accept what the core can carry, refuse what it cannot,
 * and never drop a transport quietly.
 */
class UnsupportedTransportTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun vless(query: String) =
        ProxyUriParser.parse("vless://$uuid@example.com:443?security=tls&$query#N")

    @Test
    fun `an xhttp node parses, which is what the port was for`() {
        val node = requireNotNull(vless("type=xhttp&path=%2Fx&host=cdn.example.com"))
        val transport = node.transport as TransportOptions.Xhttp
        assertEquals("/x", transport.path)
        assertEquals("cdn.example.com", transport.host)
    }

    /** The name it went by before the rename; subscriptions still hand out both. */
    @Test
    fun `splithttp is the same transport under its older name`() {
        assertTrue(vless("type=splithttp")?.transport is TransportOptions.Xhttp)
    }

    @Test
    fun `xhttp mode is carried when named and left to the core when not`() {
        assertEquals("stream-up", (vless("type=xhttp&mode=stream-up")?.transport as TransportOptions.Xhttp).mode)
        assertEquals("auto", (vless("type=xhttp")?.transport as TransportOptions.Xhttp).mode)
    }

    /**
     * Removed from the core outright, both of them, with an explicit removed-feature error. A link
     * asking for one has to be refused here: accepting it would mean emitting the node without its
     * transport, which is the exact failure at the top of this file.
     */
    @Test
    fun `transports the core removed are refused`() {
        assertNull(vless("type=http"))
        assertNull(vless("type=h2"))
        assertNull(vless("type=quic"))
    }

    @Test
    fun `transports no core ever implemented are refused too`() {
        assertNull(vless("type=kcp"))
        assertNull(vless("type=mkcp"))
        assertNull(vless("type=meek"))
    }

    @Test
    fun `the transports the core does implement still parse`() {
        assertTrue(vless("type=ws&path=%2Fws")?.transport is TransportOptions.WebSocket)
        assertTrue(vless("type=grpc&serviceName=s")?.transport is TransportOptions.Grpc)
        assertTrue(vless("type=httpupgrade")?.transport is TransportOptions.HttpUpgrade)
        assertTrue(vless("type=xhttp")?.transport is TransportOptions.Xhttp)
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

    @Test
    fun `a clash entry naming xhttp is kept and one naming a removed transport is skipped`() {
        val document = """
            proxies:
              - name: xhttp-node
                type: vless
                server: example.com
                port: 443
                uuid: $uuid
                network: xhttp
              - name: removed
                type: vless
                server: example.com
                port: 8443
                uuid: $uuid
                network: h2
        """.trimIndent()

        val nodes = ClashParser.parse(document, "sub")
        assertEquals(listOf("xhttp-node"), nodes.map { it.name })
        assertTrue(nodes.single().transport is TransportOptions.Xhttp)
    }

    @Test
    fun `an xray outbound naming xhttp keeps its transport`() {
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

    /**
     * The bug this pair of sets used to hide: a network could be *accepted* by the check and then
     * have no branch in the builder, so the node was created with no transport at all and dialled
     * as plain TCP. `h2` did exactly that.
     */
    @Test
    fun `an xray outbound naming a removed transport is skipped rather than stripped`() {
        val document = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"example.com",
            "port":443,"users":[{"id":"$uuid"}]}]},"streamSettings":{"network":"h2",
            "security":"tls"}}]}
        """.trimIndent().replace("\n", "")

        assertTrue(ConfigDocumentParser.parse(document, "sub").isEmpty())
    }
}
