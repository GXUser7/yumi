package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.model.TransportOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionServiceJsonMergeTest {

    private class TestStrings : Strings() {
        override fun get(id: Int, vararg formatArgs: Any): String = "res#$id(${formatArgs.joinToString(",")})"
        override fun plural(id: Int, count: Int): String = "$count"
    }

    @Test
    fun `fetch with Happ mimicry merges JSON-only fragment, xhttp extra, and grpc multiMode while keeping link IDs`() =
        runBlocking {
            val uuid1 = "11111111-1111-1111-1111-111111111111"
            val uuid2 = "22222222-2222-2222-2222-222222222222"
            val uuid3 = "33333333-3333-3333-3333-333333333333"

            val linksBody = Base64.getEncoder().encodeToString(
                """
                vless://$uuid1@xh.example.com:443?security=tls&sni=xh.example.com&type=xhttp&path=%2Fup#Server-Xhttp
                vless://$uuid2@gr.example.com:443?security=tls&sni=gr.example.com&type=grpc&serviceName=tun#Server-Grpc
                vless://$uuid3@de1.example.com:443?security=tls&sni=de1.example.com#Auto-Region
                """.trimIndent().toByteArray(),
            )

            val docsJson = """
                [
                  {
                    "remarks": "Server-Xhttp",
                    "outbounds": [
                      {
                        "tag": "fragment",
                        "protocol": "freedom",
                        "settings": {
                          "domainStrategy": "AsIs",
                          "fragment": {"packets": "tlshello", "length": "10-20", "interval": "5-15"}
                        }
                      },
                      {
                        "tag": "proxy",
                        "protocol": "vless",
                        "settings": {
                          "vnext": [{"address": "xh.example.com", "port": 443, "users": [{"id": "$uuid1"}]}]
                        },
                        "streamSettings": {
                          "network": "xhttp",
                          "security": "tls",
                          "tlsSettings": {"serverName": "xh.example.com"},
                          "xhttpSettings": {
                            "host": "xh.example.com",
                            "path": "/up",
                            "extra": {"xPaddingBytes": "50-150"}
                          },
                          "sockopt": {"dialerProxy": "fragment"}
                        }
                      }
                    ]
                  },
                  {
                    "remarks": "Server-Grpc",
                    "outbounds": [
                      {
                        "tag": "proxy",
                        "protocol": "vless",
                        "settings": {
                          "vnext": [{"address": "gr.example.com", "port": 443, "users": [{"id": "$uuid2"}]}]
                        },
                        "streamSettings": {
                          "network": "grpc",
                          "security": "tls",
                          "tlsSettings": {"serverName": "gr.example.com"},
                          "grpcSettings": {"serviceName": "tun", "multiMode": true}
                        }
                      }
                    ]
                  },
                  {
                    "remarks": "Auto-Region",
                    "routing": {
                      "balancers": [{"tag": "proxy", "selector": ["p1", "p2"], "strategy": {"type": "leastPing"}}]
                    },
                    "outbounds": [
                      {
                        "tag": "p1",
                        "protocol": "vless",
                        "settings": {
                          "vnext": [{"address": "de1.example.com", "port": 443, "users": [{"id": "$uuid3"}]}]
                        },
                        "streamSettings": {"security": "tls", "tlsSettings": {"serverName": "de1.example.com"}}
                      },
                      {
                        "tag": "p2",
                        "protocol": "vless",
                        "settings": {
                          "vnext": [{"address": "de2.example.com", "port": 443, "users": [{"id": "$uuid3"}]}]
                        },
                        "streamSettings": {"security": "tls", "tlsSettings": {"serverName": "de2.example.com"}}
                      }
                    ]
                  }
                ]
            """.trimIndent()

            val seenUserAgents = mutableListOf<String>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            try {
                server.createContext("/") { ex ->
                    val path = ex.requestURI.path
                    val ua = ex.requestHeaders.getFirst("User-Agent").orEmpty()
                    seenUserAgents += ua
                    if (path.endsWith("/json")) {
                        ex.sendResponseHeaders(404, -1)
                        ex.close()
                        return@createContext
                    }
                    val payload = if (ua.contains("(compatible; Happ)")) docsJson else linksBody
                    val bytes = payload.toByteArray()
                    ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                server.start()

                val expectedLinks = com.mydrop.vpn.core.parse.SubscriptionParser.parse(
                    linksBody,
                    "sub-1",
                ) as com.mydrop.vpn.core.parse.SubscriptionBody.Nodes
                val expectedXhttpId = expectedLinks.nodes.first { it.name == "Server-Xhttp" }.id
                val expectedGrpcId = expectedLinks.nodes.first { it.name == "Server-Grpc" }.id

                val strings = TestStrings()
                val service = SubscriptionService(
                    logs = LogRepository(strings),
                    strings = strings,
                    userAgent = "Yumi/0.2.0 (Android)",
                )
                val result = service.fetch(
                    Subscription(
                        id = "sub-1",
                        name = "TestPanel",
                        url = "http://127.0.0.1:${server.address.port}/tokentest1",
                    ),
                )

                assertTrue("Expected Success, got $result", result is SubscriptionUpdate.Success)
                assertTrue(
                    "Expected second fetch with Happ mimicry UA, got $seenUserAgents",
                    seenUserAgents.any { it.contains("(compatible; Happ)") },
                )

                val nodes = (result as SubscriptionUpdate.Success).nodes
                val xh = nodes.first { it.name == "Server-Xhttp" }
                assertEquals(expectedXhttpId, xh.id)
                assertEquals("tlshello", xh.fragment?.packets)
                assertEquals("5-15", xh.fragment?.interval)
                val xhTransport = xh.transport as TransportOptions.Xhttp
                assertTrue(xhTransport.extraJson?.contains("xPaddingBytes") == true)

                val gr = nodes.first { it.name == "Server-Grpc" }
                assertEquals(expectedGrpcId, gr.id)
                assertTrue((gr.transport as TransportOptions.Grpc).multiMode)

                val auto = nodes.first { it.name == "Auto-Region" }
                val group = auto.group
                assertNotNull(group)
                assertEquals(NodeGroup.STRATEGY_FASTEST, group?.strategy)
                assertEquals(2, group?.members?.size)
            } finally {
                server.stop(0)
            }
        }
}
