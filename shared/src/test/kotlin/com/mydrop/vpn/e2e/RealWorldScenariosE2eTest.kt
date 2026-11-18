package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.PingMode
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.SplitTunnelRules
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.LatencyTester
import com.mydrop.vpn.data.TunnelHealthCheck
import com.mydrop.vpn.pairing.PairingCrypto
import com.mydrop.vpn.pairing.PairingInvite
import com.mydrop.vpn.pairing.SubscriptionTransfer
import com.mydrop.vpn.remote.RemoteCrypto
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteSnapshot
import com.mydrop.vpn.remote.RemoteTunnelState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 3 Cross-Feature Pairwise Combination Tests & Tier 4 Real-World Multi-Area E2E Scenarios
 * on `:shared` JVM + real loopback sockets.
 *
 * Covers:
 * - Tier 3: `T3-01`, `T3-02`, `T3-03`, `T3-08`, `T3-09`, `T3-11`, `T3-12`, `T3-14`, `T3-15`
 * - Tier 4: `T4-01`, `T4-02`, `T4-04`, `T4-06`, `T4-07`
 */
class RealWorldScenariosE2eTest {

    private val protoTcp = 6
    private val protoUdp = 17
    private val ownPkg = "com.mydrop.vpn"
    private val myUid = 10100
    private val phoneUid = 1001
    private val imsUid = 10101
    private val netdUid = 1051
    private val browserPkg = "org.mozilla.firefox"
    private val browserUid = 10300
    private val rknPkg = "com.notcvnt.rknhardering"
    private val rknUid = 10400

    private val tunSrcV4 = byteArrayOf(172.toByte(), 19, 0, 1)
    private val tunDnsV4 = byteArrayOf(172.toByte(), 19, 0, 2)
    private val extDstV4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
    private val tunSrcV6 = InetAddress.getByName("fdfe:dcba:9876::1").address
    private val extDstV6 = InetAddress.getByName("2606:4700:4700::1111").address

    private fun resolver(extra: Map<String, Int> = emptyMap()): (String) -> Int? {
        val map = mutableMapOf(
            ownPkg to myUid,
            "com.android.phone" to phoneUid,
            "com.shannon.imsservice" to imsUid,
            browserPkg to browserUid,
            rknPkg to rknUid,
        )
        map.putAll(extra)
        return { pkg -> map[pkg] }
    }

    // ------------------------------------------------------------------ Tier 3

    @Test
    fun t3_01_blockListWithAlwaysOutsideAndOwnUidBlocksExcludedAndAllowsBrowser() {
        var currentUid = rknUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(rknPkg),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (blocked in listOf(rknUid, myUid, phoneUid, imsUid)) {
            currentUid = blocked
            assertFalse("UID $blocked must be blocked on TCP", policy.allowConnection(protoTcp, tunSrcV4, 50001, extDstV4, 443))
            assertFalse("UID $blocked must be blocked on UDP", policy.allowConnection(protoUdp, tunSrcV4, 50002, tunDnsV4, 53))
        }
        currentUid = browserUid
        assertTrue(policy.allowConnection(protoTcp, tunSrcV4, 50003, extDstV4, 443))
    }

    @Test
    fun t3_02_emptyAllowListWithDnsBlocksAllAppAndYumiDnsWithoutFallback() {
        var currentUid = rknUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = emptySet(),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (uid in listOf(rknUid, myUid, browserUid, phoneUid, imsUid)) {
            currentUid = uid
            assertFalse(
                "Empty AllowList must deny DNS 172.19.0.2:53 for UID $uid",
                policy.allowConnection(protoUdp, tunSrcV4, 53000, tunDnsV4, 53),
            )
            assertFalse(
                "Empty AllowList must deny TCP 443 for UID $uid",
                policy.allowConnection(protoTcp, tunSrcV4, 53001, extDstV4, 443),
            )
        }
    }

    @Test
    fun t3_03_dynamicAllowListAndBlockListRuleChangeRejectsRevokedAndInvalidUids() {
        var currentUid = browserUid
        val policyBefore = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }
        assertTrue(policyBefore.allowConnection(protoTcp, tunSrcV4, 50100, extDstV4, 443))

        // Revoke browser from AllowList -> new policy immediately rejects browserUid and -1
        val policyAfter = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = emptySet(),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }
        assertFalse(policyAfter.allowConnection(protoTcp, tunSrcV4, 50101, extDstV4, 443))
        currentUid = -1
        assertFalse(policyAfter.allowConnection(protoTcp, tunSrcV4, 50102, extDstV4, 443))
    }

    @Test
    fun t3_08_ipv6DisabledStillCapturesIpv6LoopbackAndRoutesDnsViaUseIPv4() {
        val node = ProxyNode(
            id = "n1",
            name = "DE",
            server = "de.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        )
        val doc = XrayConfigFactory.build(
            nodes = listOf(node),
            selected = node,
            settings = AppSettings(enableIpv6 = false, bypassLan = true, hijackDns = true),
            geoAvailable = false,
        )
        val json = Json.parseToJsonElement(doc.json).jsonObject
        assertEquals("UseIPv4", json["dns"]!!.jsonObject["queryStrategy"]?.jsonPrimitive?.content)

        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val blocksV6Loopback = rules.any { rule ->
            "::1/128" in rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() &&
                XrayConfigFactory.TUN_TAG in rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        assertTrue("::1/128 from tun-in must still be blocked when enableIpv6=false", blocksV6Loopback)
    }

    @Test
    fun t3_09_loopback127AndIpv6LoopbackBlockedEvenBeforePrivateRanges() {
        val node = ProxyNode(
            id = "n1",
            name = "NL",
            server = "nl.example.com",
            port = 443,
            settings = ProxySettings.Trojan(password = "pw"),
        )
        val doc = XrayConfigFactory.build(
            nodes = listOf(node),
            selected = node,
            settings = AppSettings(bypassLan = true, routingMode = RoutingMode.Rules),
            geoAvailable = false,
        )
        val rules = Json.parseToJsonElement(doc.json).jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray.map {
            it.jsonObject
        }
        val blockV4Idx = rules.indexOfFirst {
            "127.0.0.0/8" in it["ip"]?.jsonArray?.map { e -> e.jsonPrimitive.content }.orEmpty() &&
                it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val blockV6Idx = rules.indexOfFirst {
            "::1/128" in it["ip"]?.jsonArray?.map { e -> e.jsonPrimitive.content }.orEmpty() &&
                it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val lanDirectIdx = rules.indexOfFirst {
            "192.168.0.0/16" in it["ip"]?.jsonArray?.map { e -> e.jsonPrimitive.content }.orEmpty() &&
                it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG
        }
        assertTrue(blockV4Idx >= 0 && blockV6Idx >= 0 && lanDirectIdx > blockV4Idx && lanDirectIdx > blockV6Idx)
    }

    @Test
    fun t3_11_api26To28SafetyCombinedWithZeroLoopbackProxyPorts() = runBlocking {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(rknPkg),
            packageUidResolver = resolver(),
            sdkInt = 28,
            myUid = myUid,
        ) { _, _, _ -> throw NoSuchMethodError("getConnectionOwnerUid requires API 29") }

        assertTrue(policy.allowConnection(protoTcp, tunSrcV4, 51000, extDstV4, 443))

        E2eContractAdapters.LoopbackTrapServer().use { trap ->
            val probe = com.mydrop.vpn.core.model.ProbeEndpoint(trap.port, "u", "p")
            E2eContractAdapters.invokeHealthCheckPasses(TunnelHealthCheck(), probe)
            assertEquals(0, trap.acceptCount.get())
        }
    }

    @Test
    fun t3_12_fullProtocolMatrixWithSplitTunnelModesAndZeroGuardToggle() {
        val settingsDesc = AppSettings.serializer().descriptor
        val names = (0 until settingsDesc.elementsCount).map { settingsDesc.getElementName(it) }
        assertFalse(names.any { it.contains("guard", ignoreCase = true) })

        val protocols = listOf(
            ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
            ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "pw"),
            ProxySettings.Hysteria2(password = "pw"),
            ProxySettings.Direct,
        )
        for (mode in SplitTunnelMode.entries) {
            for (proto in protocols) {
                val node = ProxyNode(id = proto.protocol.name, name = proto.protocol.label, server = "s.example", port = 443, settings = proto)
                val doc = XrayConfigFactory.build(
                    nodes = listOf(node),
                    selected = node,
                    settings = AppSettings(splitTunnelMode = mode),
                    geoAvailable = false,
                )
                val inbounds = Json.parseToJsonElement(doc.json).jsonObject["inbounds"]!!.jsonArray
                assertEquals(1, inbounds.size)
                assertEquals(XrayConfigFactory.TUN_TAG, inbounds.single().jsonObject["tag"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun t3_14_remoteSnapshotReflectsLatencyUpdatesWithZeroServerIpLeak() = runBlocking {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { listener ->
            val targetNode = ProxyNode(
                id = "node-jp",
                name = "🇯🇵 Tokyo",
                server = "127.0.0.1",
                port = listener.localPort,
                settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
            )
            val results = LatencyTester().measureAll(listOf(targetNode), PingMode.Tcp)
            val latency = results.single()
            assertFalse(latency.failed)

            val snapshot = RemoteSnapshot(
                hostName = "Yumi TV",
                state = RemoteTunnelState.On,
                selectedNodeId = targetNode.id,
                nodesRevision = 2,
                nodes = listOf(
                    RemoteNode(
                        id = targetNode.id,
                        name = targetNode.name,
                        group = "Provider",
                        latencyMillis = latency.millis,
                        latencyFailed = latency.failed,
                    ),
                ),
            )
            val wire = RemoteCrypto.json.encodeToString(snapshot)
            assertFalse(wire.contains("127.0.0.1"))
            assertFalse(wire.contains(listener.localPort.toString()))
            assertTrue(wire.contains(targetNode.name))
        }
    }

    @Test
    fun t3_15_latencyTesterProbesOnlySelectedNodesWithZeroLoopbackProxyDependency() = runBlocking {
        val hitsA = AtomicInteger(0)
        val hitsB = AtomicInteger(0)
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { sockA ->
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { sockB ->
                val tA = Thread {
                    while (!sockA.isClosed) {
                        runCatching {
                            sockA.accept().close()
                            hitsA.incrementAndGet()
                        }
                    }
                }.apply { isDaemon = true; start() }
                val tB = Thread {
                    while (!sockB.isClosed) {
                        runCatching {
                            sockB.accept().close()
                            hitsB.incrementAndGet()
                        }
                    }
                }.apply { isDaemon = true; start() }
                try {
                    val nodeA = ProxyNode("a", "🇯🇵 Tokyo", "127.0.0.1", sockA.localPort, ProxySettings.Direct)
                    val nodeB = ProxyNode("b", "🇩🇪 Berlin", "127.0.0.1", sockB.localPort, ProxySettings.Direct)
                    assertNotEquals(nodeA.id, nodeB.id)

                    val measured = LatencyTester().measureAll(listOf(nodeA), PingMode.Tcp)
                    assertEquals(1, measured.size)
                    assertEquals("a", measured.single().nodeId)

                    val deadline = System.nanoTime() + 1_000_000_000L
                    while (hitsA.get() < 1 && System.nanoTime() < deadline) {
                        Thread.sleep(5)
                    }
                    assertTrue("Expected at least 1 accept on selected nodeA, got ${hitsA.get()}", hitsA.get() >= 1)
                    Thread.sleep(50)
                    assertEquals("Excluded nodeB must receive 0 probes", 0, hitsB.get())
                } finally {
                    sockA.close()
                    sockB.close()
                    tA.join(200)
                    tB.join(200)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Tier 4: Real-World Scenarios

    @Test
    fun t4_01_rknHarderingExcludedAppSimulationVsAllowedBrowser() = runBlocking {
        var callerUid = rknUid
        val mappedV4Src = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 172.toByte(), 19, 0, 1)
        val mappedV4Dst = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 1, 1, 1, 1)

        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, src, _ ->
            assertTrue("Expected unmapped Inet4Address or pure Inet6Address, got ${src.address}", src.address is Inet4Address || src.address is Inet6Address)
            callerUid
        }

        // 1. RKNHardering direct tun0 probes (IPv4, IPv4-mapped IPv6, IPv6, DNS 172.19.0.2:53)
        callerUid = rknUid
        assertFalse(policy.allowConnection(protoTcp, tunSrcV4, 60001, extDstV4, 443))
        assertFalse(policy.allowConnection(protoUdp, tunSrcV4, 60002, tunDnsV4, 53))
        assertFalse(policy.allowConnection(protoTcp, mappedV4Src, 60003, mappedV4Dst, 443))
        assertFalse(policy.allowConnection(protoTcp, tunSrcV6, 60004, extDstV6, 443))

        // 2. Yumi itself excluded
        callerUid = myUid
        assertFalse(policy.allowConnection(protoTcp, tunSrcV4, 60005, extDstV4, 443))

        // 3. Loopback probe-in absent in Xray config & TunnelHealthCheck does not dial 127.0.0.1
        E2eContractAdapters.LoopbackTrapServer().use { trap ->
            E2eContractAdapters.invokeHealthCheckPasses(
                TunnelHealthCheck(),
                com.mydrop.vpn.core.model.ProbeEndpoint(trap.port, "u", "p"),
            )
            assertEquals(0, trap.acceptCount.get())
        }

        // 4. Allowed browser + netd DNS succeed
        callerUid = browserUid
        assertTrue(policy.allowConnection(protoTcp, tunSrcV4, 60010, extDstV4, 443))
        assertTrue(policy.allowConnection(protoUdp, tunSrcV6, 60011, extDstV6, 443))
        callerUid = netdUid
        assertTrue(policy.allowConnection(protoUdp, tunSrcV4, 60012, tunDnsV4, 53))
    }

    @Test
    fun t4_02_emptyAndAllUninstalledAllowListRecoveryWithoutSelfTunneling() {
        // Phase 1: Empty / all-uninstalled AllowList denies everyone (no fallback to allow-all)
        var currentUid = rknUid
        val emptyPolicy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf("com.removed.browser"),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (uid in listOf(rknUid, myUid, phoneUid, imsUid, browserUid)) {
            currentUid = uid
            assertFalse("UID $uid must be blocked when AllowList has 0 valid packages", emptyPolicy.allowConnection(protoTcp, tunSrcV4, 61001, extDstV4, 443))
        }

        // Phase 2: User adds browser -> browser allowed, RKNHardering + Yumi + telephony stay blocked
        val recoveredPolicy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = resolver(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        currentUid = browserUid
        assertTrue(recoveredPolicy.allowConnection(protoTcp, tunSrcV4, 61002, extDstV4, 443))
        currentUid = rknUid
        assertFalse(recoveredPolicy.allowConnection(protoTcp, tunSrcV4, 61003, extDstV4, 443))
        currentUid = myUid
        assertFalse(recoveredPolicy.allowConnection(protoTcp, tunSrcV4, 61004, extDstV4, 443))
    }

    @Test
    fun t4_04_android8And9Api26To28StabilityAndLoopbackNonDisclosure() = runBlocking {
        for (api in listOf(26, 28)) {
            val lookupCalls = AtomicInteger(0)
            val policy = E2eContractAdapters.createSplitTunnelPolicy(
                mode = SplitTunnelMode.BlockList,
                splitTunnelPackages = setOf(rknPkg),
                packageUidResolver = resolver(),
                sdkInt = api,
                myUid = myUid,
            ) { _, _, _ ->
                lookupCalls.incrementAndGet()
                throw NoSuchMethodError("API 29+")
            }
            assertTrue(policy.allowConnection(protoTcp, tunSrcV4, 62001, extDstV4, 443))
            assertTrue(policy.allowConnection(protoUdp, tunSrcV6, 62002, extDstV6, 443))
            assertEquals(0, lookupCalls.get())
        }

        E2eContractAdapters.LoopbackTrapServer().use { trap ->
            E2eContractAdapters.invokeHealthCheckPasses(
                TunnelHealthCheck(),
                com.mydrop.vpn.core.model.ProbeEndpoint(trap.port, "u", "p"),
            )
            assertEquals(0, trap.acceptCount.get())
        }
    }

    @Test
    fun t4_06_liveAppReinstallUidChangeAndPackageRemovalUpdatesSplitTunnelPolicy() {
        val oldUid = 10300
        val newUid = 10488
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
        )

        val rulesBefore = SplitTunnelRules.resolve(settings) { pkg ->
            if (pkg == browserPkg) oldUid else null
        }
        val rulesAfterReinstall = SplitTunnelRules.resolve(settings) { pkg ->
            if (pkg == browserPkg) newUid else null
        }
        assertNotEquals(rulesBefore, rulesAfterReinstall)

        var callerUid = oldUid
        val policyAfterReinstall = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = resolver(mapOf(browserPkg to newUid)),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> callerUid }

        assertFalse("Stale UID $oldUid must be rejected after reinstall", policyAfterReinstall.allowConnection(protoTcp, tunSrcV4, 63001, extDstV4, 443))
        callerUid = newUid
        assertTrue("New UID $newUid must be allowed after reinstall", policyAfterReinstall.allowConnection(protoTcp, tunSrcV4, 63002, extDstV4, 443))
    }

    @Test
    fun t4_07_tvLanRemoteControlAndPairingStripsXhwidWithZeroIpDisclosure() {
        val receiverKeys = PairingCrypto.keyPair()
        val invite = PairingInvite(
            version = com.mydrop.vpn.pairing.VERSION,
            host = "192.168.1.50",
            port = 40404,
            sessionId = PairingCrypto.sessionId(),
            receiverPublicKey = PairingCrypto.publicKey(receiverKeys.public),
            deviceName = "Yumi TV",
        )
        // Phone transfer with x-hwid must be rejected by Pairing validation
        assertNull(
            SubscriptionTransfer(
                name = "Provider",
                url = "https://sub.example.com/abc",
                headers = mapOf("X-HWID" to "secret-device-id"),
            ).validate(),
        )
        val cleanTransfer = SubscriptionTransfer(
            name = "Provider",
            url = "https://sub.example.com/abc",
        )
        val (req, _) = PairingCrypto.request(invite, cleanTransfer)
        val (opened, _) = PairingCrypto.openRequest(req, receiverKeys)
        assertFalse(opened.headers.keys.any { it.equals("x-hwid", ignoreCase = true) })

        val snapshot = RemoteSnapshot(
            hostName = "Yumi TV",
            state = RemoteTunnelState.On,
            selectedNodeId = "n1",
            nodes = listOf(RemoteNode("n1", "🇯🇵 Tokyo", "Provider", latencyMillis = 77)),
        )
        val snapshotWire = RemoteCrypto.json.encodeToString(snapshot)
        assertFalse(snapshotWire.contains("203.0.113."))
        assertFalse(snapshotWire.contains("198.51.100."))
    }
}
