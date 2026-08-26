package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transport the core cannot speak has to make the whole node disappear.
 *
 * This is written from a real failure. A subscription started handing out `type=xhttp` nodes;
 * the parser did not recognise the name, dropped the transport, and produced an ordinary-looking
 * VLESS server. It appeared in the list with a normal latency, the tunnel came up on it, and then
 * every single connection failed with `reality verification failed` — three hundred and fifty of
 * them in forty seconds — because the server was framing XHTTP at a client sending plain TLS.
 *
 * sing-box implements five stream transports (`option/v2ray_transport.go`): HTTP, WebSocket, QUIC,
 * gRPC and HTTPUpgrade. XHTTP is not one of them and no amount of parsing will make it work, so
 * the only honest answer is to refuse the line at import.
 */
class UnsupportedTransportTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun vless(query: String) =
        ProxyUriParser.parse("vless://$uuid@example.com:443?security=tls&$query#N")

    @Test
    fun `an xhttp node is refused rather than silently stripped of its transport`() {
        assertNull(vless("type=xhttp&path=%2Fx"))
    }

    @Test
    fun `other transports the core has no implementation for are refused too`() {
        assertNull(vless("type=splithttp"))
        assertNull(vless("type=kcp"))
        assertNull(vless("type=mkcp"))
        assertNull(vless("type=meek"))
    }

    @Test
    fun `the five the core does implement still parse`() {
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

    @Test
    fun `a clash entry naming an unsupported network is skipped`() {
        val document = """
            proxies:
              - name: broken
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

        val nodes = ClashParser.parse(document, "sub")
        assertEquals(listOf("fine"), nodes.map { it.name })
    }

    @Test
    fun `an xray outbound naming an unsupported network is skipped`() {
        val document = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"example.com",
            "port":443,"users":[{"id":"$uuid"}]}]},"streamSettings":{"network":"xhttp",
            "security":"reality","realitySettings":{"publicKey":"k"}}}]}
        """.trimIndent().replace("\n", "")

        assertTrue(ConfigDocumentParser.parse(document, "sub").isEmpty())
    }
}
