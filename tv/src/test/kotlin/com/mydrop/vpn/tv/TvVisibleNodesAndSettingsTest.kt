package com.mydrop.vpn.tv

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.data.FilteredPingCoordinator
import com.mydrop.vpn.data.LatencyTester
import com.mydrop.vpn.data.ProfileState
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVisibleNodesAndSettingsTest {

    private fun node(
        id: String,
        port: Int = 443,
        subscriptionId: String? = "sub-enabled",
    ) = ProxyNode(
        id = id,
        name = "TV $id",
        server = "127.0.0.1",
        port = port,
        settings = ProxySettings.Vless("11111111-2222-3333-4444-555555555555"),
        subscriptionId = subscriptionId,
    )

    @Test
    fun `Tv visibleNodes excludes disabled subscription nodes and LatencyTester dials only visible servers`() =
        runBlocking {
            val loopback = InetAddress.getByName("127.0.0.1")
            val enabledHits = AtomicInteger(0)
            val disabledHits = AtomicInteger(0)
            val running = AtomicBoolean(true)
            val threadsReady = CountDownLatch(2)
            val enabledAccepted = CountDownLatch(1)

            ServerSocket(0, 8, loopback).use { enabledSocket ->
                ServerSocket(0, 8, loopback).use { disabledSocket ->
                    val t1 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !enabledSocket.isClosed) {
                            runCatching {
                                enabledSocket.accept().use {
                                    enabledHits.incrementAndGet()
                                    enabledAccepted.countDown()
                                }
                            }
                        }
                    }
                    val t2 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !disabledSocket.isClosed) {
                            runCatching { disabledSocket.accept().use { disabledHits.incrementAndGet() } }
                        }
                    }

                    try {
                        val state = ProfileState(
                            nodes = listOf(
                                node("enabled-1", port = enabledSocket.localPort, subscriptionId = "sub-on"),
                                node("disabled-1", port = disabledSocket.localPort, subscriptionId = "sub-off"),
                            ),
                            subscriptions = listOf(
                                Subscription(id = "sub-on", name = "On", url = "https://a.example", enabled = true),
                                Subscription(id = "sub-off", name = "Off", url = "https://b.example", enabled = false),
                            ),
                        )
                        val tvUi = TvUiState(
                            nodes = state.visibleNodes,
                            subscriptions = state.subscriptions,
                        )
                        assertEquals(listOf("enabled-1"), tvUi.nodes.map { it.id })

                        assertTrue(
                            "Accept threads did not start within timeout",
                            threadsReady.await(5, TimeUnit.SECONDS),
                        )

                        val results = LatencyTester().measureAll(tvUi.nodes, PingMode.Tcp)
                        assertEquals(listOf("enabled-1"), results.map { it.nodeId })
                        assertTrue(
                            "Timed out waiting for enabledSocket accept",
                            enabledAccepted.await(5, TimeUnit.SECONDS),
                        )
                        assertEquals(1, enabledHits.get())
                        assertEquals(0, disabledHits.get())
                    } finally {
                        running.set(false)
                        runCatching { enabledSocket.close() }
                        runCatching { disabledSocket.close() }
                        t1.join(2_000)
                        t2.join(2_000)
                    }
                }
            }
        }

    @Test
    fun `F12 TvUiState and AppSettings contain no split-tunnel guard toggles`() {
        val forbidden = listOf("guard", "leak", "owneruid", "trafficguard", "uidpolicy")
        val appFields = AppSettings::class.java.declaredFields.map { it.name.lowercase() }
        val tvFields = TvUiState::class.java.declaredFields.map { it.name.lowercase() }
        (appFields + tvFields).forEach { name ->
            forbidden.forEach { token ->
                assertFalse("Unexpected guard field '$name'", token in name)
            }
        }
    }

    @Test
    fun `Tier5 TvViewModel ping coordinator enforces double-tap guard and records visible node latencies`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val recorded = mutableListOf<LatencyResult>()
                val coordinator = FilteredPingCoordinator(scope) { recorded += it }
                val n1 = node("tv-1")
                val gate = CompletableDeferred<Unit>()
                var sweeps = 0

                val job = coordinator.ping(
                    nodes = listOf(n1),
                    mode = PingMode.Tcp,
                    measure = { targets, _, onResult ->
                        sweeps++
                        gate.await()
                        val res = LatencyResult(targets.single().id, 30, 1_000L, false)
                        onResult(res)
                        listOf(res)
                    },
                )
                assertNotNull(job)
                assertNull(
                    coordinator.ping(
                        nodes = listOf(n1),
                        mode = PingMode.Tcp,
                        measure = { _, _, _ -> sweeps++; emptyList() },
                    ),
                )
                assertEquals(1, sweeps)
                gate.complete(Unit)
                job!!.join()
                assertEquals(1, recorded.size)
                assertTrue(coordinator.pingingNodeIds.value.isEmpty())
            } finally {
                scope.cancel()
            }
        }
}
