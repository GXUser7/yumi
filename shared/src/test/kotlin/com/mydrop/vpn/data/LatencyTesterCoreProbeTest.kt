package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.FragmentMode
import com.mydrop.vpn.core.model.FragmentScope
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TlsOptions
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTesterCoreProbeTest {

    private fun vlessNode(
        id: String,
        server: String = "127.0.0.1",
        port: Int = 443,
        tls: TlsOptions? = TlsOptions(enabled = true, serverName = server),
    ) = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = port,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        tls = tls,
    )

    private fun hy2Node(id: String, server: String = "hy.example.com", port: Int = 33443) = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = port,
        settings = ProxySettings.Hysteria2(
            password = "pw-$id",
            obfsType = "salamander",
            obfsPassword = "obfs",
        ),
        tls = TlsOptions(enabled = true, serverName = server),
    )

    private fun wireGuardNode(id: String, server: String = "198.51.100.10", port: Int = 51820) = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = port,
        settings = ProxySettings.WireGuard(
            privateKey = "priv",
            peerPublicKey = "pub",
        ),
    )

    @Test
    fun `url test routes all nodes and groups through core prober, streams results, and preserves order`() = runBlocking {
        val m1 = vlessNode("m1")
        val m2 = vlessNode("m2")
        val groupNode = m1.copy(
            id = "grp",
            name = "Group",
            group = NodeGroup(members = listOf(m1, m2), strategy = NodeGroup.STRATEGY_COST, maxDelayMillis = 2_000),
        )
        val plain = hy2Node("hy1")

        val seenConcurrencies = CopyOnWriteArrayList<Int>()
        val seenBatches = CopyOnWriteArrayList<Set<String>>()
        val streamedIds = CopyOnWriteArrayList<String>()
        var tunnelUp = false

        val prober = CoreProber { nodes, _, _, concurrency, onResult ->
            seenConcurrencies.add(concurrency)
            val ids = nodes.map { it.id }.toSet()
            seenBatches.add(ids)
            if ("hy1" in ids) onResult("hy1", 42)
            if ("m1" in ids || "m2" in ids) {
                delay(20)
                if ("m1" in ids) onResult("m1", null)
                if ("m2" in ids) onResult("m2", 85)
            }
        }

        val tester = LatencyTester(
            settingsProvider = { AppSettings(pingMode = PingMode.UrlTest, resolveServerAddress = false) },
            coreProber = prober,
            isTunnelRunning = { tunnelUp },
        )

        val results = tester.measureAll(listOf(groupNode, plain), PingMode.UrlTest) {
            streamedIds.add(it.nodeId)
        }

        assertEquals(setOf("m1", "m2", "hy1"), seenBatches[0])
        assertEquals(listOf("grp", "hy1"), results.map { it.nodeId })
        assertEquals(85, results[0].millis)
        assertFalse(results[0].failed)
        assertEquals(42, results[1].millis)
        assertFalse(results[1].failed)
        assertEquals(listOf("hy1", "grp"), streamedIds)
        assertEquals(listOf(12), seenConcurrencies.toList())

        tunnelUp = true
        val single = tester.measure(plain, PingMode.UrlTest)
        assertEquals(setOf("hy1"), seenBatches[1])
        assertEquals(42, single.millis)
        assertEquals(listOf(12, 8), seenConcurrencies.toList())
    }

    @Test
    fun `tcp and tls modes auto-upgrade hysteria2, wireguard, and fragmented tls to core prober`() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { tcpSocket ->
            val plainTcp = vlessNode("plain-tcp", server = "127.0.0.1", port = tcpSocket.localPort, tls = null)
            val vlessTls = vlessNode("vless-tls", server = "127.0.0.1", port = tcpSocket.localPort)
            val hy2 = hy2Node("hy2")
            val wg = wireGuardNode("wg")

            val probedInTcp = CopyOnWriteArrayList<String>()
            val prober = CoreProber { nodes, _, _, _, onResult ->
                nodes.forEach {
                    probedInTcp.add(it.id)
                    onResult(it.id, 55)
                }
            }

            val tester = LatencyTester(
                settingsProvider = {
                    AppSettings(
                        pingMode = PingMode.Tcp,
                        fragmentMode = FragmentMode.TcpSegments,
                        fragmentScope = FragmentScope.TlsAndReality,
                        resolveServerAddress = false,
                    )
                },
                coreProber = prober,
            )

            val tcpResults = tester.measureAll(listOf(plainTcp, hy2, wg, vlessTls), PingMode.Tcp)
            assertEquals(listOf("plain-tcp", "hy2", "wg", "vless-tls"), tcpResults.map { it.nodeId })
            assertFalse(tcpResults[0].failed)
            assertEquals(setOf("hy2", "wg"), probedInTcp.toSet())

            probedInTcp.clear()
            val tlsResults = tester.measureAll(listOf(plainTcp, hy2, wg, vlessTls), PingMode.Tls)
            assertEquals(listOf("plain-tcp", "hy2", "wg", "vless-tls"), tlsResults.map { it.nodeId })
            assertEquals(setOf("hy2", "wg", "vless-tls"), probedInTcp.toSet())
        }
    }

    @Test
    fun `doh resolution applies before both core prober and raw socket probes when enabled`() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { tcpSocket ->
            val resolvedServers = CopyOnWriteArrayList<String>()
            val resolveHook: (ProxyNode, String) -> ProxyNode = { node, _ ->
                resolvedServers.add(node.id)
                node.copy(
                    server = "127.0.0.1",
                    tls = node.tls?.copy(serverName = node.server),
                )
            }

            val domainTcp = vlessNode(
                id = "domain-tcp",
                server = "blocked-domain.example",
                port = tcpSocket.localPort,
                tls = null,
            )
            val domainHy2 = hy2Node("domain-hy2", server = "hy-domain.example", port = 33443)

            val coreResolvedIps = ConcurrentHashMap<String, String>()
            val prober = CoreProber { nodes, _, _, _, onResult ->
                nodes.forEach {
                    coreResolvedIps[it.id] = it.server
                    onResult(it.id, 33)
                }
            }

            var dohEnabled = true
            val tester = LatencyTester(
                settingsProvider = {
                    AppSettings(
                        pingMode = PingMode.Tcp,
                        resolveServerAddress = dohEnabled,
                    )
                },
                coreProber = prober,
                resolveNode = resolveHook,
            )

            val results = tester.measureAll(listOf(domainTcp, domainHy2), PingMode.Tcp)
            assertFalse(results[0].failed)
            assertFalse(results[1].failed)
            assertEquals("127.0.0.1", coreResolvedIps["domain-hy2"])
            assertEquals(setOf("domain-tcp", "domain-hy2"), resolvedServers.toSet())

            resolvedServers.clear()
            coreResolvedIps.clear()
            dohEnabled = false
            tester.measureAll(listOf(domainHy2), PingMode.Tcp)
            assertTrue(resolvedServers.isEmpty())
            assertEquals("hy-domain.example", coreResolvedIps["domain-hy2"])
        }
    }
}
