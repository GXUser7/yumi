package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyUriParserTest {

    @Test
    fun `vless with reality and vision keeps every handshake parameter`() {
        val node = ProxyUriParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443" +
                "?security=reality&sni=www.microsoft.com&fp=chrome" +
                "&pbk=PUBLICKEY123&sid=a1b2c3d4&spx=%2F&type=tcp" +
                "&flow=xtls-rprx-vision#Франкфурт",
        )

        assertNotNull(node)
        requireNotNull(node)
        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("de.example.com", node.server)
        assertEquals(443, node.port)
        assertEquals("Франкфурт", node.name)

        val settings = node.settings as ProxySettings.Vless
        assertEquals("11111111-2222-3333-4444-555555555555", settings.uuid)
        assertEquals("xtls-rprx-vision", settings.flow)

        val tls = requireNotNull(node.tls)
        assertEquals("www.microsoft.com", tls.serverName)
        assertEquals("chrome", tls.fingerprint)
        assertEquals("PUBLICKEY123", tls.reality?.publicKey)
        assertEquals("a1b2c3d4", tls.reality?.shortId)
        assertEquals("/", tls.reality?.spiderX)

        // type=tcp means no v2ray transport is layered under the protocol.
        assertNull(node.transport)
        assertTrue(node.badges.contains("REALITY"))
        assertTrue(node.badges.contains("Vision"))
    }

    @Test
    fun `vless over websocket carries the host header into sni`() {
        val node = ProxyUriParser.parse(
            "vless://uuid-here@nl.example.com:8443?security=tls&type=ws" +
                "&path=%2Fws&host=cdn.example.com#Амстердам",
        )

        requireNotNull(node)
        val transport = node.transport as TransportOptions.WebSocket
        assertEquals("/ws", transport.path)
        assertEquals("cdn.example.com", transport.headers["Host"])
        assertEquals("cdn.example.com", node.tls?.serverName)
    }

    @Test
    fun `vmess base64 json decodes the panel format`() {
        val json = """
            {"v":"2","ps":"Париж","add":"fr.example.com","port":"443","id":"uuid-1",
             "aid":"0","scy":"auto","net":"ws","host":"fr.example.com","path":"/vm",
             "tls":"tls","sni":"fr.example.com","fp":"chrome"}
        """.trimIndent().replace("\n", "")
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())

        val node = requireNotNull(ProxyUriParser.parse(link))
        assertEquals(Protocol.VMESS, node.protocol)
        assertEquals("Париж", node.name)
        assertEquals("fr.example.com", node.server)
        assertEquals(443, node.port)
        assertEquals("uuid-1", (node.settings as ProxySettings.Vmess).uuid)
        assertEquals("/vm", (node.transport as TransportOptions.WebSocket).path)
        assertEquals("chrome", node.tls?.fingerprint)
    }

    @Test
    fun `vmess port given as a number rather than a string still parses`() {
        val json = """{"v":"2","ps":"N","add":"a.example.com","port":8080,"id":"u","aid":0}"""
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())

        val node = requireNotNull(ProxyUriParser.parse(link))
        assertEquals(8080, node.port)
    }

    @Test
    fun `trojan defaults to tls even without an explicit security parameter`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "trojan://p%40ssword@fi.example.com:443?type=grpc&serviceName=tg#Хельсинки",
            ),
        )

        assertEquals("p@ssword", (node.settings as ProxySettings.Trojan).password)
        assertNotNull(node.tls)
        assertEquals("tg", (node.transport as TransportOptions.Grpc).serviceName)
    }

    @Test
    fun `shadowsocks sip002 and legacy forms produce the same server`() {
        val sip002 = requireNotNull(
            ProxyUriParser.parse(
                "ss://" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("aes-256-gcm:secret".toByteArray()) +
                    "@us.example.com:8388#NY",
            ),
        )
        val legacy = requireNotNull(
            ProxyUriParser.parse(
                "ss://" + Base64.getEncoder()
                    .encodeToString("aes-256-gcm:secret@us.example.com:8388".toByteArray()) +
                    "#NY",
            ),
        )

        listOf(sip002, legacy).forEach { node ->
            val settings = node.settings as ProxySettings.Shadowsocks
            assertEquals("aes-256-gcm", settings.method)
            assertEquals("secret", settings.password)
            assertEquals("us.example.com", node.server)
            assertEquals(8388, node.port)
        }
        // Same endpoint and credentials must yield the same identity regardless of encoding.
        assertEquals(sip002.id, legacy.id)
    }

    @Test
    fun `hysteria2 accepts the hy2 alias and obfs parameters`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "hy2://pass@se.example.com:443?sni=se.example.com" +
                    "&obfs=salamander&obfs-password=obfspass#Стокгольм",
            ),
        )

        assertEquals(Protocol.HYSTERIA2, node.protocol)
        val settings = node.settings as ProxySettings.Hysteria2
        assertEquals("pass", settings.password)
        assertEquals("salamander", settings.obfsType)
        assertEquals("obfspass", settings.obfsPassword)
        assertTrue(node.protocol.isQuicBased)
    }

    @Test
    fun `tuic splits uuid and password from userinfo`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "tuic://uuid-abc:pass-xyz@jp.example.com:443" +
                    "?congestion_control=bbr&alpn=h3&sni=jp.example.com#Токио",
            ),
        )

        val settings = node.settings as ProxySettings.Tuic
        assertEquals("uuid-abc", settings.uuid)
        assertEquals("pass-xyz", settings.password)
        assertEquals("bbr", settings.congestionControl)
        assertEquals(listOf("h3"), node.tls?.alpn)
    }

    @Test
    fun `anytls parses and is tls by default`() {
        val node = requireNotNull(
            ProxyUriParser.parse("anytls://secret@uk.example.com:443?sni=uk.example.com#Лондон"),
        )
        assertEquals(Protocol.ANYTLS, node.protocol)
        assertEquals("secret", (node.settings as ProxySettings.AnyTls).password)
        assertEquals("uk.example.com", node.tls?.serverName)
    }

    @Test
    fun `ipv6 literal host is unwrapped from its brackets`() {
        val node = requireNotNull(
            ProxyUriParser.parse("vless://uuid@[2001:db8::1]:443?security=tls#v6"),
        )
        assertEquals("2001:db8::1", node.server)
        assertEquals(443, node.port)
    }

    @Test
    fun `insecure flag is recognised under all its common spellings`() {
        listOf("insecure=1", "allowInsecure=1", "allow_insecure=true", "skip-cert-verify=true")
            .forEach { param ->
                val node = requireNotNull(
                    ProxyUriParser.parse("trojan://p@h.example.com:443?$param"),
                )
                assertTrue("failed for $param", node.tls!!.insecure)
            }
    }

    @Test
    fun `unknown schemes and junk lines are skipped rather than throwing`() {
        assertNull(ProxyUriParser.parse("ftp://example.com"))
        assertNull(ProxyUriParser.parse("just some text"))
        assertNull(ProxyUriParser.parse(""))
        assertNull(ProxyUriParser.parse("vless://"))
    }

    @Test
    fun `parseAll keeps the good servers when one line is malformed`() {
        val text = """
            vless://uuid@a.example.com:443?security=tls#A
            this-is-not-a-link
            trojan://pw@b.example.com:443#B
        """.trimIndent()

        val nodes = ProxyUriParser.parseAll(text)
        assertEquals(2, nodes.size)
        assertEquals(listOf("A", "B"), nodes.map { it.name })
    }

    @Test
    fun `node identity survives a provider renaming the server`() {
        val first = requireNotNull(
            ProxyUriParser.parse("vless://uuid@a.example.com:443?security=tls#Старое имя"),
        )
        val renamed = requireNotNull(
            ProxyUriParser.parse("vless://uuid@a.example.com:443?security=tls#Новое имя 80%"),
        )
        assertEquals(first.id, renamed.id)

        val different = requireNotNull(
            ProxyUriParser.parse("vless://other-uuid@a.example.com:443?security=tls#Старое имя"),
        )
        assertFalse(first.id == different.id)
    }

    /**
     * `user1234` is valid base64 and decodes to six bytes of binary. The parser used to believe
     * it, and the username handed to the core was that binary rather than the login.
     */
    @Test
    fun `a socks login that happens to be valid base64 is kept as written`() {
        val node = requireNotNull(ProxyUriParser.parse("socks://user1234@1.2.3.4:1080"))
        val settings = node.settings as ProxySettings.Socks
        assertEquals("user1234", settings.username)
        assertEquals("", settings.password)
    }

    @Test
    fun `socks credentials really encoded as base64 are still decoded`() {
        val encoded = Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        val node = requireNotNull(ProxyUriParser.parse("socks://$encoded@1.2.3.4:1080"))
        val settings = node.settings as ProxySettings.Socks
        assertEquals("alice", settings.username)
        assertEquals("s3cret", settings.password)
    }

    @Test
    fun `a link without a port is refused rather than given an invented one`() {
        assertNull(ProxyUriParser.parse("vless://11111111-2222-3333-4444-555555555555@example.com"))
    }

    @Test
    fun `protocols that need a credential refuse a link without one`() {
        assertNull(ProxyUriParser.parse("hysteria2://@example.com:443"))
        assertNull(ProxyUriParser.parse("anytls://@example.com:443"))
        assertNull(ProxyUriParser.parse("tuic://@example.com:443"))
    }

    @Test
    fun `vless reality with server_name extracts decoy sni and preserves node server`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "vless://11111111-2222-3333-4444-555555555555@node.vless.monster:443" +
                    "?security=reality&server_name=decoy.com&pbk=PUBLICKEY123&sid=a1b2c3d4" +
                    "&spx=%2F&type=tcp&flow=xtls-rprx-vision#Node1",
            ),
        )

        assertEquals(Protocol.VLESS, node.protocol)
        assertEquals("node.vless.monster", node.server)
        assertEquals(443, node.port)
        assertEquals("Node1", node.name)

        val tls = requireNotNull(node.tls)
        assertEquals("decoy.com", tls.serverName)
        assertEquals("PUBLICKEY123", tls.reality?.publicKey)
        assertEquals("a1b2c3d4", tls.reality?.shortId)
        assertEquals("/", tls.reality?.spiderX)
        assertTrue(tls.isReality)
    }

    @Test
    fun `vless reality with snake_case aliases parses all reality parameters correctly`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "vless://uuid-val@proxy.example.com:443" +
                    "?security=reality&server_name=target.decoy.org&public_key=PUB111" +
                    "&short_id=sid222&spider_x=%2Fcustom&client_fingerprint=firefox" +
                    "&packet_encoding=packetaddr#SnakeReality",
            ),
        )

        assertEquals("proxy.example.com", node.server)
        assertEquals(443, node.port)

        val settings = node.settings as ProxySettings.Vless
        assertEquals("uuid-val", settings.uuid)
        assertEquals("packetaddr", settings.packetEncoding)

        val tls = requireNotNull(node.tls)
        assertEquals("target.decoy.org", tls.serverName)
        assertEquals("firefox", tls.fingerprint)
        val reality = requireNotNull(tls.reality)
        assertEquals("PUB111", reality.publicKey)
        assertEquals("sid222", reality.shortId)
        assertEquals("/custom", reality.spiderX)
    }

    @Test
    fun `standard non-reality tls node falls back to host if sni omitted`() {
        val node = requireNotNull(
            ProxyUriParser.parse("vless://uuid-val@myproxy.org:443?security=tls#StandardTLS"),
        )

        assertEquals("myproxy.org", node.server)
        assertEquals(443, node.port)

        val tls = requireNotNull(node.tls)
        assertEquals("myproxy.org", tls.serverName)
        assertNull(tls.reality)
        assertFalse(tls.isReality)
    }

    @Test
    fun `vless reality with pubkey and key aliases parses successfully`() {
        val nodePubkey = requireNotNull(
            ProxyUriParser.parse(
                "vless://uuid-val@pub.example.com:443?security=reality&pubkey=ALTKEY&servername=decoy.pubkey.com",
            ),
        )
        assertEquals("decoy.pubkey.com", nodePubkey.tls?.serverName)
        assertEquals("ALTKEY", nodePubkey.tls?.reality?.publicKey)
        assertEquals("", nodePubkey.tls?.reality?.shortId)

        val nodeKey = requireNotNull(
            ProxyUriParser.parse(
                "vless://uuid-val@key.example.com:443?security=reality&key=KEYONLY&peer=peer.decoy.com",
            ),
        )
        assertEquals("peer.decoy.com", nodeKey.tls?.serverName)
        assertEquals("KEYONLY", nodeKey.tls?.reality?.publicKey)
    }

    @Test
    fun `trojan reality with snake_case parameters parses correctly`() {
        val node = requireNotNull(
            ProxyUriParser.parse(
                "trojan://secret@tr.example.com:443" +
                    "?security=reality&server_name=decoy.trojan.com&public_key=TRPUB" +
                    "&short_id=trsid&spider_x=%2Fpath&client_fingerprint=safari#TrojanReality",
            ),
        )

        assertEquals("tr.example.com", node.server)
        val settings = node.settings as ProxySettings.Trojan
        assertEquals("secret", settings.password)

        val tls = requireNotNull(node.tls)
        assertEquals("decoy.trojan.com", tls.serverName)
        assertEquals("safari", tls.fingerprint)
        val reality = requireNotNull(tls.reality)
        assertEquals("TRPUB", reality.publicKey)
        assertEquals("trsid", reality.shortId)
        assertEquals("/path", reality.spiderX)
    }

    @Test
    fun `vless reality with websocket host fallback and explicit server_name override`() {
        val fallbackNode = requireNotNull(
            ProxyUriParser.parse(
                "vless://uuid-val@ws.example.com:443?security=reality&type=ws&host=ws.host.com&pbk=WSKEY",
            ),
        )
        assertEquals("ws.host.com", fallbackNode.tls?.serverName)

        val explicitNode = requireNotNull(
            ProxyUriParser.parse(
                "vless://uuid-val@ws.example.com:443?security=reality&type=ws&host=ws.host.com&server_name=decoy.com&pbk=WSKEY",
            ),
        )
        assertEquals("decoy.com", explicitNode.tls?.serverName)
    }
}
