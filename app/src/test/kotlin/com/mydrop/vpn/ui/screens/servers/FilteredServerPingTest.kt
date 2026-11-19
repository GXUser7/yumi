package com.mydrop.vpn.ui.screens.servers

import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.WorldMap
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteredServerPingTest {

    private fun node(
        id: String,
        name: String,
        server: String = "$id.example.com",
        port: Int = 443,
        subscriptionId: String? = "sub-1",
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = server,
        port = port,
        settings = settings,
        subscriptionId = subscriptionId,
        group = group,
    )

    private fun sub(id: String, enabled: Boolean = true) = Subscription(
        id = id,
        name = "Sub $id",
        url = "https://example.com/$id",
        enabled = enabled,
    )

    @Test
    fun `default filter returns all visible nodes and excludes disabled subscriptions`() {
        val visibleSub = sub("sub-1", enabled = true)
        val disabledSub = sub("sub-disabled", enabled = false)
        val n1 = node("n1", "DE #1", subscriptionId = "sub-1")
        val n2 = node("n2", "Hidden", subscriptionId = "sub-disabled")
        val manual = node("m1", "Manual", subscriptionId = null)

        val profileState = ProfileState(
            nodes = listOf(n1, n2, manual),
            subscriptions = listOf(visibleSub, disabledSub),
        )

        val targets = filteredNodesForPing(
            nodes = profileState.visibleNodes,
            subscriptions = profileState.subscriptions,
            latencies = emptyMap(),
            filter = ServerFilterState(),
        )
        assertEquals(listOf("n1", "m1"), targets.map { it.id })
    }

    @Test
    fun `filters by query (name, server, protocol label), protocol including NodeGroup, and country`() {
        val deVless = node(
            id = "de-vless",
            name = "${WorldMap.flagOf("DE")} Berlin Edge",
            server = "edge-de.example.com",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val deTrojan = node(
            id = "de-trojan",
            name = "${WorldMap.flagOf("DE")} Frankfurt",
            server = "fra.example.com",
            settings = ProxySettings.Trojan(password = "p1"),
        )
        val nlGroup = node(
            id = "nl-group",
            name = "${WorldMap.flagOf("NL")} Amsterdam Auto",
            server = "ams.example.com",
            settings = ProxySettings.Hysteria2(password = "h1"),
            group = NodeGroup(members = listOf(node("m1", "M1", settings = ProxySettings.Vless("u2")))),
        )
        val subs = listOf(sub("sub-1"))
        val nodes = listOf(deVless, deTrojan, nlGroup)

        // Query by name
        assertEquals(
            listOf("de-vless"),
            filteredNodesForPing(nodes, subs, emptyMap(), ServerFilterState(query = "berlin")).map { it.id },
        )
        // Query by server host
        assertEquals(
            listOf("de-trojan"),
            filteredNodesForPing(nodes, subs, emptyMap(), ServerFilterState(query = "fra.example")).map { it.id },
        )
        // Query by protocol label
        assertEquals(
            listOf("de-trojan"),
            filteredNodesForPing(nodes, subs, emptyMap(), ServerFilterState(query = "Trojan")).map { it.id },
        )
        // Protocol filter matches NodeGroup member
        assertEquals(
            listOf("de-vless", "nl-group"),
            filteredNodesForPing(
                nodes,
                subs,
                emptyMap(),
                ServerFilterState(selectedProtocols = setOf(Protocol.VLESS)),
            ).map { it.id },
        )
        // Country + protocol combined
        assertEquals(
            listOf("de-vless"),
            filteredNodesForPing(
                nodes,
                subs,
                emptyMap(),
                ServerFilterState(
                    selectedProtocols = setOf(Protocol.VLESS),
                    selectedCountries = setOf("DE"),
                ),
            ).map { it.id },
        )
        // Empty match returns empty list
        assertTrue(
            filteredNodesForPing(nodes, subs, emptyMap(), ServerFilterState(query = "nonexistent")).isEmpty(),
        )
    }

    @Test
    fun `resolveServerFilters drops stale facets missing from current node list`() {
        val nodes = listOf(
            node(
                id = "de-1",
                name = "${WorldMap.flagOf("DE")} Berlin",
                settings = ProxySettings.Vless("u1"),
            ),
        )
        val resolved = resolveServerFilters(
            nodes = nodes,
            filter = ServerFilterState(
                sort = ServerSort.Latency,
                selectedProtocols = setOf(Protocol.VLESS, Protocol.TROJAN),
                selectedCountries = setOf("DE", "JP"),
            ),
        )
        assertEquals(setOf(Protocol.VLESS), resolved.activeProtocols)
        assertEquals(setOf("DE"), resolved.activeCountries)
        assertEquals(3, resolved.activeFilterCount)
    }

    @Test
    fun `LatencyTester dials ONLY filtered servers over real loopback sockets and skips excluded servers`() =
        runBlocking {
            val loopback = InetAddress.getByName("127.0.0.1")
            val matchedAccepts = AtomicInteger(0)
            val excludedAccepts = AtomicInteger(0)
            val running = AtomicBoolean(true)
            val threadsReady = CountDownLatch(2)
            val matchedAccepted = CountDownLatch(1)

            ServerSocket(0, 8, loopback).use { matchedSocket ->
                ServerSocket(0, 8, loopback).use { excludedSocket ->
                    val t1 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !matchedSocket.isClosed) {
                            runCatching {
                                matchedSocket.accept().use {
                                    matchedAccepts.incrementAndGet()
                                    matchedAccepted.countDown()
                                }
                            }
                        }
                    }
                    val t2 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !excludedSocket.isClosed) {
                            runCatching { excludedSocket.accept().use { excludedAccepts.incrementAndGet() } }
                        }
                    }

                    try {
                        val matchingNode = node(
                            id = "match",
                            name = "${WorldMap.flagOf("DE")} Berlin",
                            server = "127.0.0.1",
                            port = matchedSocket.localPort,
                            settings = ProxySettings.Vless("u1"),
                        )
                        val excludedNode = node(
                            id = "excluded",
                            name = "${WorldMap.flagOf("US")} New York",
                            server = "127.0.0.1",
                            port = excludedSocket.localPort,
                            settings = ProxySettings.Trojan("p1"),
                        )

                        val filtered = filteredNodesForPing(
                            nodes = listOf(matchingNode, excludedNode),
                            subscriptions = listOf(sub("sub-1")),
                            latencies = emptyMap(),
                            filter = ServerFilterState(
                                query = "Berlin",
                                selectedProtocols = setOf(Protocol.VLESS),
                                selectedCountries = setOf("DE"),
                            ),
                        )
                        assertEquals(listOf("match"), filtered.map { it.id })

                        assertTrue(
                            "Accept threads did not start within timeout",
                            threadsReady.await(5, TimeUnit.SECONDS),
                        )

                        val results = LatencyTester().measureAll(filtered, PingMode.Tcp)
                        assertEquals(1, results.size)
                        assertEquals("match", results.single().nodeId)
                        assertTrue(
                            "Timed out waiting for matchedSocket accept",
                            matchedAccepted.await(5, TimeUnit.SECONDS),
                        )
                        assertEquals(1, matchedAccepts.get())
                        assertEquals(0, excludedAccepts.get())
                    } finally {
                        running.set(false)
                        runCatching { matchedSocket.close() }
                        runCatching { excludedSocket.close() }
                        t1.join(2_000)
                        t2.join(2_000)
                    }
                }
            }
        }

    @Test
    fun `Tier5 FilteredPingCoordinator handles filtered scoping, double-tap guard, empty short-circuit, and cancel cleanup`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val recorded = mutableListOf<String>()
                val coordinator = FilteredPingCoordinator(scope) { result -> recorded += result.nodeId }

                val jp = node("jp", "${WorldMap.flagOf("JP")} Tokyo", settings = ProxySettings.Vless("u1"))
                val de = node("de", "${WorldMap.flagOf("DE")} Berlin", settings = ProxySettings.Trojan("p1"))
                val subs = listOf(sub("sub-1"))

                // 1. Empty filter short-circuits without launching or calling measure/onComplete.
                val emptyTargets = filteredNodesForPing(
                    nodes = listOf(jp, de),
                    subscriptions = subs,
                    latencies = emptyMap(),
                    filter = ServerFilterState(query = "no-such-server"),
                )
                var measureCalls = 0
                var completedCalls = 0
                assertNull(
                    coordinator.ping(
                        nodes = emptyTargets,
                        mode = PingMode.Tcp,
                        measure = { _, _, _ -> measureCalls++; emptyList() },
                        onComplete = { completedCalls++ },
                    ),
                )
                assertEquals(0, measureCalls)
                assertEquals(0, completedCalls)
                assertTrue(coordinator.pingingNodeIds.value.isEmpty())

                // 2. Filtered sweep scopes pingingNodeIds to matching nodes and blocks double-tap while active.
                val filteredTargets = filteredNodesForPing(
                    nodes = listOf(jp, de),
                    subscriptions = subs,
                    latencies = emptyMap(),
                    filter = ServerFilterState(selectedCountries = setOf("JP")),
                )
                assertEquals(listOf("jp"), filteredTargets.map { it.id })

                val gate = CompletableDeferred<Unit>()
                val firstJob = coordinator.ping(
                    nodes = filteredTargets,
                    mode = PingMode.Tcp,
                    measure = { targets, _, onResult ->
                        measureCalls++
                        assertEquals(listOf("jp"), targets.map { it.id })
                        gate.await()
                        val res = LatencyResult("jp", 25, 1_000L, false)
                        onResult(res)
                        listOf(res)
                    },
                    onComplete = { completedCalls++ },
                )
                assertNotNull(firstJob)
                assertTrue(coordinator.isRunning)
                assertEquals(setOf("jp"), coordinator.pingingNodeIds.value)

                // Double-tap while active returns null and does not start a second sweep.
                assertNull(
                    coordinator.ping(
                        nodes = listOf(jp, de),
                        mode = PingMode.Tcp,
                        measure = { _, _, _ -> measureCalls++; emptyList() },
                        onComplete = { completedCalls++ },
                    ),
                )
                assertEquals(1, measureCalls)

                gate.complete(Unit)
                firstJob!!.join()
                assertFalse(coordinator.isRunning)
                assertTrue(coordinator.pingingNodeIds.value.isEmpty())
                assertEquals(listOf("jp"), recorded)
                assertEquals(1, completedCalls)

                // 3. Cancellation / failure cleans up pingingNodeIds without firing onComplete.
                val cancelGate = CompletableDeferred<Unit>()
                val cancelJob = coordinator.ping(
                    nodes = listOf(de),
                    mode = PingMode.Tcp,
                    measure = { _, _, _ ->
                        cancelGate.await()
                        emptyList()
                    },
                    onComplete = { completedCalls++ },
                )!!
                assertEquals(setOf("de"), coordinator.pingingNodeIds.value)
                cancelJob.cancel()
                cancelJob.join()
                assertFalse(coordinator.isRunning)
                assertTrue(coordinator.pingingNodeIds.value.isEmpty())
                assertEquals(1, completedCalls)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `FilteredPingCoordinator prevents double-starts across 50 concurrent multi-threaded rounds`() =
        runBlocking {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val dispatcher = pool.asCoroutineDispatcher()
            try {
                val n1 = node("n1", "DE #1")
                repeat(50) {
                    val scope = CoroutineScope(SupervisorJob() + dispatcher)
                    try {
                        val coordinator = FilteredPingCoordinator(scope) {}
                        val starts = AtomicInteger(0)
                        val gate = CompletableDeferred<Unit>()
                        val ready = java.util.concurrent.CountDownLatch(2)
                        val go = java.util.concurrent.CountDownLatch(1)
                        val jobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job?>())

                        val t1 = thread {
                            ready.countDown()
                            go.await()
                            jobs += coordinator.ping(
                                nodes = listOf(n1),
                                mode = PingMode.Tcp,
                                measure = { _, _, _ ->
                                    starts.incrementAndGet()
                                    gate.await()
                                    emptyList()
                                },
                            )
                        }
                        val t2 = thread {
                            ready.countDown()
                            go.await()
                            jobs += coordinator.ping(
                                nodes = listOf(n1),
                                mode = PingMode.Tcp,
                                measure = { _, _, _ ->
                                    starts.incrementAndGet()
                                    gate.await()
                                    emptyList()
                                },
                            )
                        }

                        assertTrue(
                            "Worker threads failed to become ready",
                            ready.await(5, TimeUnit.SECONDS),
                        )
                        go.countDown()
                        t1.join(2000)
                        t2.join(2000)
                        assertFalse("t1 timed out", t1.isAlive)
                        assertFalse("t2 timed out", t2.isAlive)

                        val nonNullJobs = jobs.filterNotNull()
                        assertEquals("Exactly 1 job must be created per concurrent pair", 1, nonNullJobs.size)
                        gate.complete(Unit)
                        nonNullJobs.single().join()
                        assertEquals("measure must be invoked exactly once per round", 1, starts.get())
                    } finally {
                        scope.cancel()
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

    @Test
    fun `collapsed subscription group is excluded from filteredNodesForPing and receives zero loopback dials`() =
        runBlocking {
            val loopback = InetAddress.getByName("127.0.0.1")
            val expandedAccepts = AtomicInteger(0)
            val collapsedAccepts = AtomicInteger(0)
            val running = AtomicBoolean(true)
            val threadsReady = CountDownLatch(2)
            val expandedAccepted = CountDownLatch(1)

            ServerSocket(0, 8, loopback).use { expandedSocket ->
                ServerSocket(0, 8, loopback).use { collapsedSocket ->
                    val t1 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !expandedSocket.isClosed) {
                            runCatching {
                                expandedSocket.accept().use {
                                    expandedAccepts.incrementAndGet()
                                    expandedAccepted.countDown()
                                }
                            }
                        }
                    }
                    val t2 = thread(isDaemon = true) {
                        threadsReady.countDown()
                        while (running.get() && !collapsedSocket.isClosed) {
                            runCatching { collapsedSocket.accept().use { collapsedAccepts.incrementAndGet() } }
                        }
                    }

                    try {
                        val sOpen = sub("sub-open", enabled = true)
                        val sCollapsed = sub("sub-collapsed", enabled = true)
                        val openNode = node(
                            id = "open-node",
                            name = "${WorldMap.flagOf("DE")} Berlin Open",
                            server = "127.0.0.1",
                            port = expandedSocket.localPort,
                            subscriptionId = sOpen.id,
                        )
                        val collapsedNode = node(
                            id = "collapsed-node",
                            name = "${WorldMap.flagOf("DE")} Berlin Collapsed",
                            server = "127.0.0.1",
                            port = collapsedSocket.localPort,
                            subscriptionId = sCollapsed.id,
                        )

                        val targets = filteredNodesForPing(
                            nodes = listOf(openNode, collapsedNode),
                            subscriptions = listOf(sOpen, sCollapsed),
                            latencies = emptyMap(),
                            filter = ServerFilterState(),
                            collapsedGroupIds = setOf(sCollapsed.id),
                        )
                        assertEquals(listOf("open-node"), targets.map { it.id })

                        assertTrue(
                            "Accept threads did not start within timeout",
                            threadsReady.await(5, TimeUnit.SECONDS),
                        )
                        val results = LatencyTester().measureAll(targets, PingMode.Tcp)
                        assertEquals(listOf("open-node"), results.map { it.nodeId })
                        assertTrue(expandedAccepted.await(5, TimeUnit.SECONDS))
                        Thread.sleep(50)
                        assertEquals(1, expandedAccepts.get())
                        assertEquals(0, collapsedAccepts.get())
                    } finally {
                        running.set(false)
                        runCatching { expandedSocket.close() }
                        runCatching { collapsedSocket.close() }
                        t1.join(2_000)
                        t2.join(2_000)
                    }
                }
            }
        }

    @Test
    fun `collapsed manual group and disabled subscriptions are excluded and all-collapsed short-circuits`() =
        runBlocking {
            val sEnabled = sub("sub-on", enabled = true)
            val sDisabled = sub("sub-off", enabled = false)
            val enabledNode = node("on-1", "DE On", subscriptionId = sEnabled.id)
            val disabledNode = node("off-1", "DE Off", subscriptionId = sDisabled.id)
            val manualNode = node("man-1", "Manual", subscriptionId = null)
            val allNodes = listOf(enabledNode, disabledNode, manualNode)
            val subs = listOf(sEnabled, sDisabled)

            // Disabled subscription excluded even when raw allNodes is passed directly.
            assertEquals(
                listOf("on-1", "man-1"),
                filteredNodesForPing(
                    nodes = allNodes,
                    subscriptions = subs,
                    latencies = emptyMap(),
                    filter = ServerFilterState(),
                ).map { it.id },
            )

            // Collapsing manual group skips manual servers while keeping expanded enabled subscription.
            assertEquals(
                listOf("on-1"),
                filteredNodesForPing(
                    nodes = allNodes,
                    subscriptions = subs,
                    latencies = emptyMap(),
                    filter = ServerFilterState(),
                    collapsedGroupIds = setOf("manual"),
                ).map { it.id },
            )

            // All groups collapsed yields empty targets and coordinator performs 0 dials.
            val emptyTargets = filteredNodesForPing(
                nodes = allNodes,
                subscriptions = subs,
                latencies = emptyMap(),
                filter = ServerFilterState(),
                collapsedGroupIds = setOf(sEnabled.id, "manual"),
            )
            assertTrue(emptyTargets.isEmpty())

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = FilteredPingCoordinator(scope) {}
                var measureCalls = 0
                assertNull(
                    coordinator.ping(
                        nodes = emptyTargets,
                        mode = PingMode.Tcp,
                        measure = { _, _, _ -> measureCalls++; emptyList() },
                    ),
                )
                assertEquals(0, measureCalls)
            } finally {
                scope.cancel()
            }
        }
}
