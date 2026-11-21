package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.CountryFilterMode
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TrafficStats
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.shared.R
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TunnelLauncherFilterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class TestStrings : Strings() {
        override fun get(id: Int, vararg formatArgs: Any): String = when (id) {
            R.string.error_no_server_selected -> "ERR_NO_SERVER"
            R.string.error_no_filtered_servers -> "ERR_NO_FILTERED_SERVERS"
            R.string.log_autoselect_no_filtered -> "LOG_NO_FILTERED"
            else -> "res#$id(${formatArgs.joinToString(",")})"
        }

        override fun plural(id: Int, count: Int): String = "$count"
    }

    private class RecordingTunnelController : TunnelController {
        override val state: StateFlow<VpnState> = MutableStateFlow(VpnState.Disconnected)
        override val traffic: StateFlow<TrafficStats> = MutableStateFlow(TrafficStats.Zero)
        override val isReal: Boolean = false

        val connectedNow = mutableListOf<Pair<ProxyNode, String>>()

        override fun connect(node: ProxyNode, reason: String) {
            connectedNow += node to reason
        }

        override suspend fun connectNow(node: ProxyNode, reason: String) {
            connectedNow += node to reason
        }

        override fun disconnect() = Unit
    }

    private data class Harness(
        val profiles: ProfileRepository,
        val settings: SettingsRepository,
        val tunnel: RecordingTunnelController,
        val logs: LogRepository,
        val launcher: TunnelLauncher,
        val scope: CoroutineScope,
    )

    private fun withHarness(
        latencyTester: LatencyTester = LatencyTester(timeoutProvider = { 500 }),
        block: suspend (Harness) -> Unit,
    ) {
        val dir = folder.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val strings = TestStrings()
            val profiles = ProfileRepository(dir, scope)
            val settings = SettingsRepository(dir, scope)
            val tunnel = RecordingTunnelController()
            val logs = LogRepository(strings)
            val launcher = TunnelLauncher(
                profiles = profiles,
                settings = settings,
                tunnel = tunnel,
                latencyTester = latencyTester,
                logs = logs,
                strings = strings,
            )
            runBlocking {
                block(Harness(profiles, settings, tunnel, logs, launcher, scope))
            }
        } finally {
            scope.cancel()
        }
    }

    private fun node(
        id: String,
        name: String,
        port: Int = 443,
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        subscriptionId: String? = "sub-1",
    ) = ProxyNode(
        id = id,
        name = name,
        server = "127.0.0.1",
        port = port,
        settings = settings,
        subscriptionId = subscriptionId,
    )

    private fun freshLatency(nodeId: String, millis: Int, failed: Boolean = false) = LatencyResult(
        nodeId = nodeId,
        millis = millis,
        measuredAtEpochMillis = System.currentTimeMillis(),
        failed = failed,
    )

    @Test
    fun `autoSelectFastest with Exclude filter skips excluded EU and RU and picks fastest allowed`() =
        withHarness { h ->
            val eu = node("eu", "${WorldMap.flagOf("EU")} Europe")
            val ru = node("ru", "${WorldMap.flagOf("RU")} Moscow")
            val de = node("de", "${WorldMap.flagOf("DE")} Berlin")
            val nl = node("nl", "${WorldMap.flagOf("NL")} Amsterdam")
            h.profiles.addNodes(listOf(eu, ru, de, nl))
            h.profiles.selectNode("eu")
            h.profiles.recordLatencies(
                listOf(
                    freshLatency("eu", 10),
                    freshLatency("ru", 5),
                    freshLatency("de", 40),
                    freshLatency("nl", 80),
                ),
            )
            h.settings.update {
                it.copy(
                    autoSelectFastest = true,
                    serverFilterCountries = setOf("EU", "RU"),
                    serverFilterCountryMode = CountryFilterMode.Exclude,
                )
            }

            val outcome = h.launcher.connect()

            assertEquals(ConnectOutcome.Started(de), outcome)
            assertEquals("de", h.profiles.selectedNode()?.id)
            assertEquals(listOf("de"), h.tunnel.connectedNow.map { it.first.id })
        }

    @Test
    fun `sweep only probes filtered candidates and never dials excluded EU server`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val euAccepts = AtomicInteger(0)
        val deAccepts = AtomicInteger(0)
        val nlAccepts = AtomicInteger(0)
        val deAccepted = CountDownLatch(1)
        val nlAccepted = CountDownLatch(1)
        val running = AtomicBoolean(true)

        ServerSocket(0, 8, loopback).use { euSocket ->
            ServerSocket(0, 8, loopback).use { deSocket ->
                ServerSocket(0, 8, loopback).use { nlSocket ->
                    val threads = listOf(
                        thread(isDaemon = true) {
                            while (running.get() && !euSocket.isClosed) {
                                runCatching { euSocket.accept().use { euAccepts.incrementAndGet() } }
                            }
                        },
                        thread(isDaemon = true) {
                            while (running.get() && !deSocket.isClosed) {
                                runCatching {
                                    deSocket.accept().use {
                                        deAccepts.incrementAndGet()
                                        deAccepted.countDown()
                                    }
                                }
                            }
                        },
                        thread(isDaemon = true) {
                            while (running.get() && !nlSocket.isClosed) {
                                runCatching {
                                    nlSocket.accept().use {
                                        nlAccepts.incrementAndGet()
                                        nlAccepted.countDown()
                                    }
                                }
                            }
                        },
                    )
                    try {
                        withHarness { h ->
                            val eu = node("eu", "${WorldMap.flagOf("EU")} Europe", port = euSocket.localPort)
                            val de = node("de", "${WorldMap.flagOf("DE")} Berlin", port = deSocket.localPort)
                            val nl = node("nl", "${WorldMap.flagOf("NL")} Amsterdam", port = nlSocket.localPort)
                            h.profiles.addNodes(listOf(eu, de, nl))
                            h.profiles.selectNode("eu")
                            // Only excluded EU has a fresh measurement; DE/NL are unmeasured so sweep must run.
                            h.profiles.recordLatency(freshLatency("eu", 3))
                            h.settings.update {
                                it.copy(
                                    autoSelectFastest = true,
                                    serverFilterCountries = setOf("EU"),
                                    serverFilterCountryMode = CountryFilterMode.Exclude,
                                )
                            }

                            val outcome = h.launcher.connect()

                            assertTrue(deAccepted.await(2, TimeUnit.SECONDS))
                            assertTrue(nlAccepted.await(2, TimeUnit.SECONDS))
                            assertTrue(outcome is ConnectOutcome.Started)
                            val dialed = (outcome as ConnectOutcome.Started).node
                            assertTrue(dialed.id == "de" || dialed.id == "nl")
                            assertEquals(0, euAccepts.get())
                            assertTrue(deAccepts.get() > 0)
                            assertTrue(nlAccepts.get() > 0)
                            assertEquals(dialed.id, h.profiles.selectedNode()?.id)
                        }
                    } finally {
                        running.set(false)
                        threads.forEach { it.interrupt() }
                    }
                }
            }
        }
    }

    @Test
    fun `when all allowed probes fail and chosen is excluded, falls back to first allowed candidate`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val deadPort1 = ServerSocket(0, 1, loopback).use { it.localPort }
        val deadPort2 = ServerSocket(0, 1, loopback).use { it.localPort }

        withHarness(latencyTester = LatencyTester(timeoutProvider = { 500 })) { h ->
            val eu = node("eu", "${WorldMap.flagOf("EU")} Europe", port = deadPort1)
            val de = node("de", "${WorldMap.flagOf("DE")} Berlin", port = deadPort1)
            val nl = node("nl", "${WorldMap.flagOf("NL")} Amsterdam", port = deadPort2)
            h.profiles.addNodes(listOf(eu, de, nl))
            h.profiles.selectNode("eu")
            h.profiles.recordLatency(freshLatency("eu", 5))
            h.settings.update {
                it.copy(
                    autoSelectFastest = true,
                    serverFilterCountries = setOf("EU"),
                    serverFilterCountryMode = CountryFilterMode.Exclude,
                )
            }

            val outcome = h.launcher.connect()

            assertEquals(ConnectOutcome.Started(de), outcome)
            assertEquals("de", h.profiles.selectedNode()?.id)
            assertEquals(listOf("de"), h.tunnel.connectedNow.map { it.first.id })
        }
    }

    @Test
    fun `when all allowed probes fail and chosen is allowed, stays on chosen`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val deadPort = ServerSocket(0, 1, loopback).use { it.localPort }

        withHarness(latencyTester = LatencyTester(timeoutProvider = { 500 })) { h ->
            val eu = node("eu", "${WorldMap.flagOf("EU")} Europe", port = deadPort)
            val de = node("de", "${WorldMap.flagOf("DE")} Berlin", port = deadPort)
            val nl = node("nl", "${WorldMap.flagOf("NL")} Amsterdam", port = deadPort)
            h.profiles.addNodes(listOf(eu, de, nl))
            h.profiles.selectNode("nl")
            h.settings.update {
                it.copy(
                    autoSelectFastest = true,
                    serverFilterCountries = setOf("EU"),
                    serverFilterCountryMode = CountryFilterMode.Exclude,
                )
            }

            val outcome = h.launcher.connect()

            assertEquals(ConnectOutcome.Started(nl), outcome)
            assertEquals("nl", h.profiles.selectedNode()?.id)
            assertEquals(listOf("nl"), h.tunnel.connectedNow.map { it.first.id })
        }
    }

    @Test
    fun `when only one allowed node remains and chosen is excluded, switches immediately without dialing excluded socket`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val euAccepts = AtomicInteger(0)
        val running = AtomicBoolean(true)

        ServerSocket(0, 8, loopback).use { euSocket ->
            val euThread = thread(isDaemon = true) {
                while (running.get() && !euSocket.isClosed) {
                    runCatching { euSocket.accept().use { euAccepts.incrementAndGet() } }
                }
            }
            try {
                withHarness { h ->
                    val eu = node("eu", "${WorldMap.flagOf("EU")} Europe", port = euSocket.localPort)
                    val de = node("de", "${WorldMap.flagOf("DE")} Berlin", port = 443)
                    h.profiles.addNodes(listOf(eu, de))
                    h.profiles.selectNode("eu")
                    h.settings.update {
                        it.copy(
                            autoSelectFastest = true,
                            serverFilterCountries = setOf("EU"),
                            serverFilterCountryMode = CountryFilterMode.Exclude,
                        )
                    }

                    val outcome = h.launcher.connect()

                    assertEquals(ConnectOutcome.Started(de), outcome)
                    assertEquals("de", h.profiles.selectedNode()?.id)
                    assertEquals(0, euAccepts.get())
                }
            } finally {
                running.set(false)
                euThread.interrupt()
            }
        }
    }

    @Test
    fun `when filters match zero nodes, connect rejects with filtered error, resolveNode returns null, and warning is logged`() =
        withHarness { h ->
            val eu = node(
                "eu",
                "${WorldMap.flagOf("EU")} Europe",
                settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p"),
            )
            val nl = node(
                "nl",
                "${WorldMap.flagOf("NL")} Amsterdam",
                settings = ProxySettings.Trojan(password = "p"),
            )
            h.profiles.addNodes(listOf(eu, nl))
            h.profiles.selectNode("eu")
            h.settings.update {
                it.copy(
                    autoSelectFastest = true,
                    serverFilterProtocols = setOf(Protocol.TROJAN),
                    serverFilterCountries = setOf("EU"),
                    serverFilterCountryMode = CountryFilterMode.Include,
                )
            }

            val resolved = h.launcher.resolveNode()
            val outcome = h.launcher.connect()

            assertNull(resolved)
            assertEquals(ConnectOutcome.Rejected("ERR_NO_FILTERED_SERVERS"), outcome)
            assertTrue(h.tunnel.connectedNow.isEmpty())
            assertTrue(
                h.logs.entries.value.any {
                    it.level == LogEntry.Level.Warn && it.message == "LOG_NO_FILTERED"
                },
            )
        }

    @Test
    fun `when autoSelectFastest is false, manual selection is connected even if excluded by filter`() =
        withHarness { h ->
            val eu = node("eu", "${WorldMap.flagOf("EU")} Europe")
            val de = node("de", "${WorldMap.flagOf("DE")} Berlin")
            h.profiles.addNodes(listOf(eu, de))
            h.profiles.selectNode("eu")
            h.profiles.recordLatencies(
                listOf(
                    freshLatency("eu", 50),
                    freshLatency("de", 10),
                ),
            )
            h.settings.update {
                it.copy(
                    autoSelectFastest = false,
                    serverFilterCountries = setOf("EU"),
                    serverFilterCountryMode = CountryFilterMode.Exclude,
                )
            }

            val outcome = h.launcher.connect()

            assertEquals(ConnectOutcome.Started(eu), outcome)
            assertEquals("eu", h.profiles.selectedNode()?.id)
            assertEquals(listOf("eu"), h.tunnel.connectedNow.map { it.first.id })
        }

    @Test
    fun `fastestNode respects Include country and protocol filters directly`() =
        withHarness { h ->
            val deVless = node(
                "de-vless",
                "${WorldMap.flagOf("DE")} Berlin VLESS",
                settings = ProxySettings.Vless(uuid = "u1"),
            )
            val nlTrojan = node(
                "nl-trojan",
                "${WorldMap.flagOf("NL")} Amsterdam Trojan",
                settings = ProxySettings.Trojan(password = "p"),
            )
            val nlVless = node(
                "nl-vless",
                "${WorldMap.flagOf("NL")} Amsterdam VLESS",
                settings = ProxySettings.Vless(uuid = "u2"),
            )
            h.profiles.addNodes(listOf(deVless, nlTrojan, nlVless))
            h.profiles.recordLatencies(
                listOf(
                    freshLatency("de-vless", 5),
                    freshLatency("nl-trojan", 10),
                    freshLatency("nl-vless", 25),
                ),
            )
            h.settings.update {
                it.copy(
                    serverFilterProtocols = setOf(Protocol.VLESS),
                    serverFilterCountries = setOf("NL"),
                    serverFilterCountryMode = CountryFilterMode.Include,
                )
            }

            val fastest = h.launcher.fastestNode()

            assertEquals("nl-vless", fastest?.id)
        }
}
