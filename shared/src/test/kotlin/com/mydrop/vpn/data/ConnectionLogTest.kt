package com.mydrop.vpn.data

import com.mydrop.vpn.core.xray.XrayConfigFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogTest {

    @Test
    fun `parses proxy line with override arrow and resolves node id`() {
        val entry = ConnectionLog.parse(
            "from tcp:172.19.0.2:51234 accepted tcp:www.example.com:443 [tun-in >> proxy-abc123]",
            100L,
        )!!
        assertEquals(100L, entry.atMillis)
        assertEquals("tcp", entry.network)
        assertEquals("www.example.com", entry.host)
        assertEquals(443, entry.port)
        assertEquals("tun-in", entry.inbound)
        assertEquals("${XrayConfigFactory.nodeTag("")}abc123", entry.outbound)
        assertEquals("abc123", entry.nodeId)
        assertEquals(ConnectionLog.Route.Proxy, entry.route)
        assertTrue(entry.accepted)
    }

    @Test
    fun `parses direct, fragment variants, block, dns, ipv6 and rejected lines`() {
        val directIpv6 = ConnectionLog.parse(
            "from 172.19.0.2:1 accepted udp:[2001:db8::1]:443 [tun-in -> direct]",
            1L,
        )!!
        assertEquals("udp", directIpv6.network)
        assertEquals("2001:db8::1", directIpv6.host)
        assertEquals(443, directIpv6.port)
        assertEquals(ConnectionLog.Route.Direct, directIpv6.route)

        assertEquals(ConnectionLog.Route.Direct, ConnectionLog.routeOf(XrayConfigFactory.FRAGMENT_TAG))
        assertEquals(ConnectionLog.Route.Direct, ConnectionLog.routeOf("${XrayConfigFactory.FRAGMENT_TAG}-2"))
        assertEquals(ConnectionLog.Route.Proxy, ConnectionLog.routeOf(XrayConfigFactory.BALANCER_TAG))

        val blocked = ConnectionLog.parse(
            "from 172.19.0.2:1 accepted tcp:ads.example:80 [tun-in -> block]",
            2L,
        )!!
        assertEquals(ConnectionLog.Route.Block, blocked.route)

        val dns = ConnectionLog.parse(
            "from 172.19.0.2:1 accepted udp:172.19.0.2:53 [tun-in ==> dns-out]",
            3L,
        )!!
        assertEquals(ConnectionLog.Route.Dns, dns.route)

        val rejected = ConnectionLog.parse(
            "from 172.19.0.2:1 rejected tcp:x.example:443 [tun-in -> proxy-n1] connection refused email: a@b",
            4L,
        )!!
        assertFalse(rejected.accepted)
        assertEquals("connection refused", rejected.reason)
        assertEquals("n1", rejected.nodeId)
    }

    @Test
    fun `ignores non-access lines and keeps bounded newest-first buffer`() {
        val log = ConnectionLog(capacity = 3)
        assertFalse(log.offer("app/dispatcher: default route for tcp:example.com:443"))
        assertNull(ConnectionLog.parse("[Info] xray started", 1L))

        repeat(5) { i ->
            assertTrue(log.offer("from 172.19.0.2:1 accepted tcp:h$i.example:443 [tun-in -> direct]", i.toLong()))
        }
        assertEquals(listOf("h4.example", "h3.example", "h2.example"), log.entries.value.map { it.host })

        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }
}
