package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
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

    /**
     * The shape a provider hands its own client for an auto-select server, cut down from a real
     * one: a balancer over candidates with a cost each, the dead last resort as its fallback. Read
     * as plain outbounds this became the fallback alone, which the server refused.
     */
    private val autoSelect = """
        {
          "remarks": "LTE Авто - Германия #2",
          "outbounds": [
            {
              "tag": "cand-01-aaa", "protocol": "vless",
              "settings": {"vnext": [{"address": "10.0.0.1", "port": 443,
                "users": [{"id": "11111111-2222-3333-4444-000000000001", "encryption": "none"}]}]},
              "streamSettings": {"network": "grpc", "security": "tls",
                "grpcSettings": {"serviceName": "x5.lk.v1.AccountService"},
                "tlsSettings": {"serverName": "lk.x5.ru", "fingerprint": "qq"}}
            },
            {
              "tag": "cand-03-ccc", "protocol": "vless",
              "settings": {"vnext": [{"address": "10.0.0.3", "port": 443,
                "users": [{"id": "11111111-2222-3333-4444-000000000003", "encryption": "none"}]}]},
              "streamSettings": {"network": "grpc", "security": "tls",
                "grpcSettings": {"serviceName": "segment"},
                "tlsSettings": {"serverName": "river-3-329.rtbcdn.ru", "fingerprint": "qq"}}
            },
            {
              "tag": "cand-02-bbb", "protocol": "vless",
              "settings": {"vnext": [{"address": "10.0.0.2", "port": 60443,
                "users": [{"id": "11111111-2222-3333-4444-000000000002", "encryption": "none",
                           "flow": "xtls-rprx-vision"}]}]},
              "streamSettings": {"network": "tcp", "security": "reality",
                "realitySettings": {"serverName": "de.example.ru", "publicKey": "PUBKEY",
                                    "shortId": "43dc", "fingerprint": "qq"}}
            },
            {"protocol": "freedom", "tag": "direct"},
            {"protocol": "blackhole", "tag": "block"}
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "balancers": [{
              "tag": "bal_price",
              "selector": ["cand-02-bbb", "cand-03-ccc"],
              "fallbackTag": "cand-01-aaa",
              "strategy": {"type": "leastLoad", "settings": {
                "costs": [
                  {"match": "cand-02-bbb", "regexp": false, "value": 0},
                  {"match": "cand-03-ccc", "regexp": false, "value": 100000}
                ],
                "maxRTT": "2s"
              }}
            }]
          }
        }
    """.trimIndent()

    @Test
    fun `an auto-select document becomes one server of several, cheapest first and fallback last`() {
        val nodes = ConfigDocumentParser.parse(autoSelect, "sub-1")

        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("LTE Авто - Германия #2", node.name)
        val group = assertNotNullGroup(node)
        // By cost, whatever order they were written in, and the balancer's last resort after them.
        assertEquals(listOf("10.0.0.2", "10.0.0.3", "10.0.0.1"), group.members.map { it.server })
        assertEquals(NodeGroup.STRATEGY_COST, group.strategy)
        assertEquals(2_000, group.maxDelayMillis)
        // The node wears its first member, so the list and the ping read it as that server.
        assertEquals("10.0.0.2", node.server)
        assertEquals(60443, node.port)
        // A group is not its first member: listed alone too, the two must not collide.
        assertTrue(group.members.none { it.id == node.id })
        assertNull(node.sourceUri)
    }

    @Test
    fun `several outbounds without a balancer are still several servers`() {
        val withoutBalancer = autoSelect.replace("\"balancers\"", "\"unused\"")
        val nodes = ConfigDocumentParser.parse(withoutBalancer, "sub-1")

        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it.group == null })
    }

    private fun assertNotNullGroup(node: com.mydrop.vpn.core.model.ProxyNode): NodeGroup {
        val group = node.group
        assertTrue("expected a group", group != null)
        return group!!
    }

    @Test
    fun `parses Happ style xray array with freedom fragment, hysteria2 finalmask, and vless extra fields`() {
        val docArray = """
            [
              {
                "remarks": "MergedSub",
                "outbounds": [
                  {
                    "tag": "fragment",
                    "protocol": "freedom",
                    "settings": {
                      "domainStrategy": "AsIs",
                      "fragment": {
                        "packets": "tlshello",
                        "length": "10-20",
                        "interval": "10-20",
                        "maxSplit": "8"
                      }
                    }
                  },
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{
                        "address": "xh.example.com",
                        "port": 443,
                        "users": [{
                          "id": "11111111-1111-1111-1111-111111111111",
                          "encryption": "mlkem768x25519plus.native.1rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                        }]
                      }]
                    },
                    "streamSettings": {
                      "network": "xhttp",
                      "security": "reality",
                      "realitySettings": {
                        "serverName": "decoy.example.com",
                        "publicKey": "PUB",
                        "shortId": "ab",
                        "mldsa65Verify": "PQVVALUE",
                        "fingerprint": "edge"
                      },
                      "xhttpSettings": {
                        "host": "cdn.example.com",
                        "path": "/up",
                        "mode": "auto",
                        "extra": {
                          "xPaddingBytes": "100-300"
                        }
                      },
                      "sockopt": {
                        "dialerProxy": "fragment"
                      }
                    }
                  },
                  {
                    "tag": "proxy-hy2",
                    "protocol": "hysteria",
                    "settings": {
                      "version": 2,
                      "address": "hy2.example.com",
                      "port": 443
                    },
                    "streamSettings": {
                      "network": "hysteria",
                      "security": "tls",
                      "tlsSettings": {
                        "serverName": "hy2.example.com",
                        "alpn": ["h3"],
                        "fingerprint": "chrome",
                        "echConfigList": "ECHVALUE"
                      },
                      "hysteriaSettings": {
                        "version": 2,
                        "auth": "hy2-secret"
                      },
                      "finalmask": {
                        "udp": [
                          {
                            "type": "salamander",
                            "settings": {"password": "mask-pass"}
                          }
                        ],
                        "quicParams": {
                          "congestion": "brutal",
                          "brutalUp": "50mbps",
                          "brutalDown": "200mbps"
                        }
                      }
                    }
                  },
                  {
                    "tag": "proxy-grpc",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{
                        "address": "gr.example.com",
                        "port": 443,
                        "users": [{"id": "22222222-2222-2222-2222-222222222222"}]
                      }]
                    },
                    "streamSettings": {
                      "network": "grpc",
                      "security": "tls",
                      "tlsSettings": {"serverName": "gr.example.com"},
                      "grpcSettings": {
                        "serviceName": "tun",
                        "authority": "grpc.example.com",
                        "multiMode": true
                      }
                    }
                  }
                ]
              }
            ]
        """.trimIndent()

        val nodes = ConfigDocumentParser.parse(docArray, "sub-happ")
        assertEquals(3, nodes.size)

        val xh = nodes[0]
        assertEquals(
            "mlkem768x25519plus.native.1rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            (xh.settings as ProxySettings.Vless).encryption,
        )
        assertEquals("PQVVALUE", xh.tls?.reality?.mldsa65Verify)
        assertEquals("tlshello", xh.fragment?.packets)
        assertEquals("10-20", xh.fragment?.length)
        assertEquals("8", xh.fragment?.maxSplit)
        assertTrue("Expected Fragment badge in ${xh.badges}", "Fragment" in xh.badges)
        assertTrue((xh.transport as TransportOptions.Xhttp).extraJson?.contains("xPaddingBytes") == true)

        val hy2 = nodes[1]
        val hySettings = hy2.settings as ProxySettings.Hysteria2
        assertEquals("hy2.example.com", hy2.server)
        assertEquals("hy2-secret", hySettings.password)
        assertEquals("salamander", hySettings.obfsType)
        assertEquals("mask-pass", hySettings.obfsPassword)
        assertEquals(50, hySettings.upMbps)
        assertEquals(200, hySettings.downMbps)
        assertEquals("ECHVALUE", hy2.tls?.echConfigList)

        val gr = nodes[2]
        val grTransport = gr.transport as TransportOptions.Grpc
        assertTrue(grTransport.multiMode)
        assertEquals("grpc.example.com", grTransport.authority)
    }
}
