package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressResolverTest {

    @Test
    fun `isValidDohUrl accepts external https and rejects http and forbidden hosts`() {
        assertTrue(ServerAddressResolver.isValidDohUrl("https://1.1.1.1/dns-query"))
        assertTrue(ServerAddressResolver.isValidDohUrl("https://dns.google/dns-query"))
        assertTrue(ServerAddressResolver.isValidDohUrl("https://[2606:4700:4700::1111]/dns-query"))

        assertFalse(ServerAddressResolver.isValidDohUrl(""))
        assertFalse(ServerAddressResolver.isValidDohUrl("http://1.1.1.1/dns-query"))
        assertFalse(ServerAddressResolver.isValidDohUrl("https://localhost/dns-query"))
        assertFalse(ServerAddressResolver.isValidDohUrl("https://127.0.0.1/dns-query"))
        assertFalse(ServerAddressResolver.isValidDohUrl("https://0.0.0.0/dns-query"))
        assertFalse(ServerAddressResolver.isValidDohUrl("https://[::1]/dns-query"))
        assertFalse(ServerAddressResolver.isValidDohUrl("https://fakedns/dns-query"))
    }

    @Test
    fun `wire format question and answer parsing handles compression and rejects loopback or bad ids`() {
        val q = ServerAddressResolver.buildQuestion(0x1234, "vpn.example.com")
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(1, q[5].toInt() and 0xFF) // QDCOUNT = 1

        // Valid A record (203.0.113.42, TTL 300) using compression pointer 0xC00C.
        val okResp = dnsResponse(
            id = 0x1234,
            rcode = 0,
            question = q,
            answers = listOf(byteArrayOf(203.toByte(), 0, 113, 42) to 300),
        )
        assertEquals("203.0.113.42" to 300, ServerAddressResolver.parseFirstAddress(okResp, 0x1234))

        // Mismatched ID is rejected.
        assertNull(ServerAddressResolver.parseFirstAddress(okResp, 0x9999))

        // Non-zero RCODE (NXDOMAIN = 3) is rejected.
        val nxdomain = dnsResponse(
            id = 0x1234,
            rcode = 3,
            question = q,
            answers = listOf(byteArrayOf(203.toByte(), 0, 113, 42) to 300),
        )
        assertNull(ServerAddressResolver.parseFirstAddress(nxdomain, 0x1234))

        // Loopback 127.0.0.1 and unspecified 0.0.0.0 are rejected; skips to next public A record if present.
        val onlyLoopback = dnsResponse(
            id = 0x1234,
            rcode = 0,
            question = q,
            answers = listOf(byteArrayOf(127, 0, 0, 1) to 60, byteArrayOf(0, 0, 0, 0) to 60),
        )
        assertNull(ServerAddressResolver.parseFirstAddress(onlyLoopback, 0x1234))

        val loopbackThenReal = dnsResponse(
            id = 0x1234,
            rcode = 0,
            question = q,
            answers = listOf(
                byteArrayOf(127, 0, 0, 1) to 60,
                byteArrayOf(198.toByte(), 51, 100, 77) to 120,
            ),
        )
        assertEquals("198.51.100.77" to 120, ServerAddressResolver.parseFirstAddress(loopbackThenReal, 0x1234))
    }

    @Test
    fun `resolveNode preserves sni, transport hosts, node id and recurses into node groups`() {
        val lookup: (String, String) -> String? = { host, _ ->
            when (host) {
                "edge.example.com" -> "203.0.113.10"
                "relay.example.com" -> "203.0.113.20"
                else -> null
            }
        }

        val wsNode = ProxyNode(
            id = "n-ws",
            name = "WS",
            server = "edge.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-1"),
            tls = TlsOptions(enabled = true, serverName = null),
            transport = TransportOptions.WebSocket(path = "/ws"),
        )
        val resolvedWs = ServerAddressResolver.resolveNode(wsNode, ServerAddressResolver.DEFAULT_DOH, lookup)
        assertEquals("n-ws", resolvedWs.id)
        assertEquals("203.0.113.10", resolvedWs.server)
        assertEquals("edge.example.com", resolvedWs.tls?.serverName)
        assertEquals("edge.example.com", (resolvedWs.transport as TransportOptions.WebSocket).headers["Host"])

        val grpcNode = ProxyNode(
            id = "n-grpc",
            name = "gRPC",
            server = "relay.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "uuid-2"),
            tls = TlsOptions(enabled = true, serverName = "custom-sni.example.com"),
            transport = TransportOptions.Grpc(serviceName = "svc", authority = ""),
        )
        val resolvedGrpc = ServerAddressResolver.resolveNode(grpcNode, ServerAddressResolver.DEFAULT_DOH, lookup)
        assertEquals("203.0.113.20", resolvedGrpc.server)
        assertEquals("custom-sni.example.com", resolvedGrpc.tls?.serverName)
        assertEquals("relay.example.com", (resolvedGrpc.transport as TransportOptions.Grpc).authority)

        val groupNode = wsNode.copy(
            id = "n-group",
            group = NodeGroup(members = listOf(wsNode, grpcNode)),
        )
        val resolvedGroup = ServerAddressResolver.resolveNode(groupNode, ServerAddressResolver.DEFAULT_DOH, lookup)
        assertEquals("n-group", resolvedGroup.id)
        assertEquals("203.0.113.10", resolvedGroup.server)
        assertEquals(listOf("203.0.113.10", "203.0.113.20"), resolvedGroup.group!!.members.map { it.server })

        val directNode = ProxyNode(
            id = "n-direct",
            name = "Direct",
            server = "edge.example.com",
            port = 0,
            settings = ProxySettings.Direct,
        )
        assertEquals(
            "edge.example.com",
            ServerAddressResolver.resolveNode(directNode, ServerAddressResolver.DEFAULT_DOH, lookup).server,
        )

        val numericNode = wsNode.copy(server = "198.51.100.5")
        assertEquals(
            "198.51.100.5",
            ServerAddressResolver.resolveNode(numericNode, ServerAddressResolver.DEFAULT_DOH, lookup).server,
        )
    }

    private fun dnsResponse(
        id: Int,
        rcode: Int,
        question: ByteArray,
        answers: List<Pair<ByteArray, Int>>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x81) // QR=1, RD=1
        out.write(0x80 or (rcode and 0x0F))
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write((answers.size ushr 8) and 0xFF)
        out.write(answers.size and 0xFF) // ANCOUNT
        repeat(4) { out.write(0x00) } // NSCOUNT, ARCOUNT
        out.write(question, 12, question.size - 12)
        for ((ipBytes, ttl) in answers) {
            out.write(0xC0)
            out.write(0x0C) // pointer to question QNAME
            out.write(0x00)
            out.write(0x01) // TYPE = A
            out.write(0x00)
            out.write(0x01) // CLASS = IN
            out.write((ttl ushr 24) and 0xFF)
            out.write((ttl ushr 16) and 0xFF)
            out.write((ttl ushr 8) and 0xFF)
            out.write(ttl and 0xFF)
            out.write(0x00)
            out.write(ipBytes.size)
            out.write(ipBytes)
        }
        return out.toByteArray()
    }
}
