package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDocumentParserTest {

    /** The shape a Remnawave panel emits for Xray clients: an array of whole configs. */
    private val xrayArray = """
        [
          {
            "remarks": "🇩🇪 Германия",
            "dns": {"servers": ["1.1.1.1"]},
            "inbounds": [{"tag": "socks", "port": 10808, "protocol": "socks"}],
            "outbounds": [
              {
                "tag": "proxy",
                "protocol": "vless",
                "settings": {
                  "vnext": [{
                    "address": "de.example.com",
                    "port": 443,
                    "users": [{"id": "11111111-2222-3333-4444-555555555555",
                               "flow": "xtls-rprx-vision", "encryption": "none"}]
                  }]
                },
                "streamSettings": {
                  "network": "tcp",
                  "security": "reality",
                  "realitySettings": {
                    "serverName": "www.microsoft.com",
                    "publicKey": "PUBKEY",
                    "shortId": "a1b2",
                    "fingerprint": "chrome"
                  }
                }
              },
              {"tag": "direct", "protocol": "freedom"},
              {"tag": "block", "protocol": "blackhole"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `xray config becomes one node with reality and flow`() {
        val nodes = ConfigDocumentParser.parse(xrayArray, "sub-1")

        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("🇩🇪 Германия", node.name)
        assertEquals("de.example.com", node.server)
        assertEquals(443, node.port)

        val settings = node.settings as ProxySettings.Vless
        assertEquals("11111111-2222-3333-4444-555555555555", settings.uuid)
        assertEquals("xtls-rprx-vision", settings.flow)

        assertEquals("www.microsoft.com", node.tls?.serverName)
        assertEquals("chrome", node.tls?.fingerprint)
        assertEquals("PUBKEY", node.tls?.reality?.publicKey)
        assertEquals("a1b2", node.tls?.reality?.shortId)
    }

    @Test
    fun `plumbing outbounds never become servers`() {
        // freedom and blackhole dial nothing; a list with them in it would otherwise show two
        // servers that cannot be connected to.
        val nodes = ConfigDocumentParser.parse(xrayArray, "sub-1")
        assertTrue(nodes.none { it.name.contains("direct") || it.name.contains("block") })
    }

    @Test
    fun `xray websocket transport carries path and host`() {
        val document = """
            [{"remarks":"ws","outbounds":[{"protocol":"vmess","tag":"proxy",
              "settings":{"vnext":[{"address":"a.example.com","port":8443,
                "users":[{"id":"uuid-1","alterId":0}]}]},
              "streamSettings":{"network":"ws","security":"tls",
                "tlsSettings":{"serverName":"a.example.com"},
                "wsSettings":{"path":"/ws","headers":{"Host":"cdn.example.com"}}}}]}]
        """.trimIndent()

        val node = ConfigDocumentParser.parse(document, null).single()
        val transport = node.transport as com.mydrop.vpn.core.model.TransportOptions.WebSocket
        assertEquals("/ws", transport.path)
        assertEquals("cdn.example.com", transport.headers["Host"])
    }

    @Test
    fun `sing-box outbound is kept verbatim, including fields the model has no room for`() {
        val document = """
            {"outbounds":[
              {"type":"vless","tag":"Токио","server":"jp.example.com","server_port":443,
               "uuid":"uuid-2","flow":"xtls-rprx-vision",
               "tls":{"enabled":true,"utls":{"enabled":true,"fingerprint":"chrome"},
                      "ech":{"enabled":true,"config":["something"]}},
               "multiplex":{"enabled":true,"protocol":"h2mux","max_connections":4}},
              {"type":"direct","tag":"direct"}
            ]}
        """.trimIndent()

        val node = ConfigDocumentParser.parse(document, "sub-2").single()
        assertEquals("Токио", node.name)
        assertEquals("jp.example.com", node.server)

        val raw = node.settings as ProxySettings.Raw
        assertEquals("vless", raw.declaredType)
        // ECH and multiplex have no place in the typed model; the point of the raw carrier is
        // that they survive anyway.
        assertTrue(raw.outbound.contains("\"ech\""))
        assertTrue(raw.outbound.contains("h2mux"))
    }

    @Test
    fun `sip008 list becomes shadowsocks nodes`() {
        val document = """
            {"version":1,"servers":[
              {"id":"x","remarks":"US","server":"us.example.com","server_port":8388,
               "password":"secret","method":"aes-256-gcm"}
            ]}
        """.trimIndent()

        val node = ConfigDocumentParser.parse(document, null).single()
        assertEquals("US", node.name)
        val settings = node.settings as ProxySettings.Shadowsocks
        assertEquals("aes-256-gcm", settings.method)
        assertEquals("secret", settings.password)
    }

    @Test
    fun `nonsense parses to nothing rather than throwing`() {
        assertTrue(ConfigDocumentParser.parse("not json at all", null).isEmpty())
        assertTrue(ConfigDocumentParser.parse("{}", null).isEmpty())
        assertTrue(ConfigDocumentParser.parse("", null).isEmpty())
    }

    @Test
    fun `an unreadable entry costs one server, not the whole list`() {
        val document = """
            {"outbounds":[
              {"type":"vless","tag":"good","server":"a.example.com","server_port":443,"uuid":"u"},
              {"type":"vless","tag":"broken"},
              {"type":"trojan","tag":"good-2","server":"b.example.com","server_port":443,
               "password":"p"}
            ]}
        """.trimIndent()

        val nodes = ConfigDocumentParser.parse(document, null)
        assertEquals(listOf("good", "good-2"), nodes.map { it.name })
        assertNull(nodes.firstOrNull { it.name == "broken" })
    }
}
