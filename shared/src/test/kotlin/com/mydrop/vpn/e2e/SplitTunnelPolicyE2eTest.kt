package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.SplitTunnelRules
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier 1 & Tier 2 Opaque-Box E2E Tests for Split-Tunneling UID Policy & API 26–28 vs 29+ Guards.
 *
 * Covers:
 * - Area A1: `T1-A1-01..05`, `T2-A1-01..05`
 * - Area A2: `T1-A2-01..03`, `T2-A2-01..04`
 * - Area A4 (`netd` DNS UID): `T1-A4-04`, `T2-A4-03`
 * - Area A6 (IPv4-mapped IPv6 unmapping & IPv6 preservation): `T1-A6-03`, `T2-A6-01`, `T2-A6-02`
 * - F9 `MyDropVpnService` & `SplitTunnelScreen.loadApps` contracts
 */
class SplitTunnelPolicyE2eTest {

    private val protoTcp = 6
    private val protoUdp = 17
    private val ownPkg = "com.mydrop.vpn"
    private val myUid = 10100
    private val evilPkg = "com.evil.probe"
    private val evilUid = 10200
    private val browserPkg = "org.mozilla.firefox"
    private val browserUid = 10300
    private val phoneUid = 1001
    private val imsUid = 10101
    private val netdUid = 1051

    private val tunSrcV4 = byteArrayOf(172.toByte(), 19, 0, 1)
    private val tunDnsV4 = byteArrayOf(172.toByte(), 19, 0, 2)
    private val extDstV4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)

    private fun basePackageUids(extra: Map<String, Int> = emptyMap()): (String) -> Int? {
        val map = mutableMapOf(
            ownPkg to myUid,
            evilPkg to evilUid,
            browserPkg to browserUid,
            "com.android.phone" to phoneUid,
            "com.shannon.imsservice" to imsUid,
        )
        map.putAll(extra)
        return { pkg -> map[pkg] }
    }

    // ------------------------------------------------------------------ Tier 1: Area A1

    @Test
    fun t1_a1_01_blockListAllowsNonListedAppUid() {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(evilPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> browserUid }

        assertTrue(
            "BlockList must allow non-listed browser UID over TCP",
            policy.allowConnection(protoTcp, tunSrcV4, 45001, extDstV4, 443),
        )
        assertTrue(
            "BlockList must allow non-listed browser UID over UDP",
            policy.allowConnection(protoUdp, tunSrcV4, 45002, extDstV4, 443),
        )
    }

    @Test
    fun t1_a1_02_blockListRejectsBlockedAppUidForTcpAndUdp() {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(evilPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> evilUid }

        assertFalse(
            "BlockList must reject blocked app UID over TCP",
            policy.allowConnection(protoTcp, tunSrcV4, 45010, extDstV4, 443),
        )
        assertFalse(
            "BlockList must reject blocked app UID over UDP",
            policy.allowConnection(protoUdp, tunSrcV4, 45011, extDstV4, 443),
        )
        assertFalse(
            "BlockList must reject blocked app UID to TUN DNS 172.19.0.2:53",
            policy.allowConnection(protoUdp, tunSrcV4, 45012, tunDnsV4, 53),
        )
    }

    @Test
    fun t1_a1_03_allowListAllowsListedUidAndBlocksNonListedUid() {
        var currentUid = browserUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        assertTrue(
            "AllowList must allow explicitly listed browser UID",
            policy.allowConnection(protoTcp, tunSrcV4, 45020, extDstV4, 443),
        )

        currentUid = evilUid
        assertFalse(
            "AllowList must reject non-listed excluded UID over TCP",
            policy.allowConnection(protoTcp, tunSrcV4, 45021, extDstV4, 443),
        )
        assertFalse(
            "AllowList must reject non-listed excluded UID over UDP",
            policy.allowConnection(protoUdp, tunSrcV4, 45022, extDstV4, 443),
        )
    }

    @Test
    fun t1_a1_04_offModeAllowsNormalUidAndRejectsOwnUidAndAlwaysOutsideUids() {
        var currentUid = browserUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.Off,
            splitTunnelPackages = emptySet(),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        assertTrue(
            "Off mode must allow ordinary user app UID",
            policy.allowConnection(protoTcp, tunSrcV4, 45030, extDstV4, 443),
        )

        for (excluded in listOf(myUid, phoneUid, imsUid)) {
            currentUid = excluded
            assertFalse(
                "Off mode must always reject UID $excluded (own/telephony)",
                policy.allowConnection(protoTcp, tunSrcV4, 45031, extDstV4, 443),
            )
            assertFalse(
                "Off mode must always reject UID $excluded over UDP",
                policy.allowConnection(protoUdp, tunSrcV4, 45032, extDstV4, 443),
            )
        }
    }

    @Test
    fun t1_a1_05_splitTunnelRulesDetectsUidChangeOnReinstall() {
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
        )
        val before = SplitTunnelRules.resolve(settings) { pkg ->
            if (pkg == browserPkg) 10300 else null
        }
        val after = SplitTunnelRules.resolve(settings) { pkg ->
            if (pkg == browserPkg) 10455 else null
        }

        assertNotEquals(before, after)
        assertEquals(10300, before.packageUids[browserPkg])
        assertEquals(10455, after.packageUids[browserPkg])
    }

    // ------------------------------------------------------------------ Tier 1: Area A2

    @Test
    fun t1_a2_01_api29ResolvesValidUidViaConnectionOwnerLookup() {
        val lookupCount = AtomicInteger(0)
        var capturedProto = -1
        var capturedSrc: InetSocketAddress? = null
        var capturedDst: InetSocketAddress? = null

        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 29,
            myUid = myUid,
        ) { proto, src, dst ->
            lookupCount.incrementAndGet()
            capturedProto = proto
            capturedSrc = src
            capturedDst = dst
            browserUid
        }

        assertTrue(policy.allowConnection(protoTcp, tunSrcV4, 45000, extDstV4, 443))
        assertEquals(1, lookupCount.get())
        assertEquals(protoTcp, capturedProto)
        assertEquals(InetAddress.getByAddress(tunSrcV4), capturedSrc?.address)
        assertEquals(45000, capturedSrc?.port)
        assertEquals(InetAddress.getByAddress(extDstV4), capturedDst?.address)
        assertEquals(443, capturedDst?.port)
    }

    @Test
    fun t1_a2_02_api29FailClosedOnInvalidUidAcrossAllModes() {
        for (mode in SplitTunnelMode.entries) {
            val policy = E2eContractAdapters.createSplitTunnelPolicy(
                mode = mode,
                splitTunnelPackages = setOf(browserPkg),
                packageUidResolver = basePackageUids(),
                sdkInt = 34,
                myUid = myUid,
            ) { _, _, _ -> -1 /* Process.INVALID_UID */ }

            assertFalse(
                "Mode $mode must fail closed when getConnectionOwnerUid returns INVALID_UID (-1) on TCP",
                policy.allowConnection(protoTcp, tunSrcV4, 46001, extDstV4, 443),
            )
            assertFalse(
                "Mode $mode must fail closed when getConnectionOwnerUid returns INVALID_UID (-1) on UDP",
                policy.allowConnection(protoUdp, tunSrcV4, 46002, tunDnsV4, 53),
            )
        }
    }

    @Test
    fun t1_a2_03_api26To28SkipsConnectionOwnerLookupAndAllowsSafely() {
        for (sdk in listOf(26, 27, 28)) {
            val lookupCount = AtomicInteger(0)
            val policy = E2eContractAdapters.createSplitTunnelPolicy(
                mode = SplitTunnelMode.BlockList,
                splitTunnelPackages = setOf(evilPkg),
                packageUidResolver = basePackageUids(),
                sdkInt = sdk,
                myUid = myUid,
            ) { _, _, _ ->
                lookupCount.incrementAndGet()
                throw NoSuchMethodError("getConnectionOwnerUid requires API 29")
            }

            assertTrue(
                "API $sdk (< Q) must allow TCP safely without calling getConnectionOwnerUid",
                policy.allowConnection(protoTcp, tunSrcV4, 46100, extDstV4, 443),
            )
            assertTrue(
                "API $sdk (< Q) must allow UDP safely without calling getConnectionOwnerUid",
                policy.allowConnection(protoUdp, tunSrcV4, 46101, tunDnsV4, 53),
            )
            assertEquals("API $sdk must make 0 getConnectionOwnerUid calls", 0, lookupCount.get())
        }
    }

    // ------------------------------------------------------------------ Tier 1: Area A4 (netd)

    @Test
    fun t1_a4_04_allowListPermitsNetdDnsUidToTunDnsOnlyAndDeniesNonDns() {
        for (dnsSystemUid in listOf(0, netdUid)) {
            val policy = E2eContractAdapters.createSplitTunnelPolicy(
                mode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf(browserPkg),
                packageUidResolver = basePackageUids(),
                sdkInt = 34,
                myUid = myUid,
            ) { _, _, _ -> dnsSystemUid }

            assertTrue(
                "AllowList must permit netd/system UID $dnsSystemUid querying TUN DNS 172.19.0.2:53",
                policy.allowConnection(protoUdp, tunSrcV4, 53004, tunDnsV4, 53),
            )
            assertFalse(
                "AllowList must deny netd/system UID $dnsSystemUid reaching non-DNS 1.1.1.1:443",
                policy.allowConnection(protoTcp, tunSrcV4, 53005, byteArrayOf(1, 1, 1, 1), 443),
            )
        }
    }

    // ------------------------------------------------------------------ Tier 1: Area A6 (IPv4-mapped IPv6)

    @Test
    fun t1_a6_03_ipv4MappedIpv6UnmapsTo4ByteInet4AddressForOwnerLookup() {
        val mappedSrc = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            172.toByte(), 19, 0, 1,
        )
        val mappedDst = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            93.toByte(), 184.toByte(), 216.toByte(), 34,
        )
        var capturedSrcAddr: InetAddress? = null
        var capturedDstAddr: InetAddress? = null

        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, src, dst ->
            capturedSrcAddr = src.address
            capturedDstAddr = dst.address
            browserUid
        }

        assertTrue(policy.allowConnection(protoTcp, mappedSrc, 54010, mappedDst, 443))
        assertTrue(
            "IPv4-mapped IPv6 srcIP must unmap to Inet4Address, got ${capturedSrcAddr?.javaClass?.simpleName}",
            capturedSrcAddr is Inet4Address,
        )
        assertTrue(
            "IPv4-mapped IPv6 dstIP must unmap to Inet4Address, got ${capturedDstAddr?.javaClass?.simpleName}",
            capturedDstAddr is Inet4Address,
        )
        assertEquals("172.19.0.1", capturedSrcAddr?.hostAddress)
        assertEquals("93.184.216.34", capturedDstAddr?.hostAddress)
    }

    // ------------------------------------------------------------------ Tier 2: Area A1

    @Test
    fun t2_a1_01_emptyAllowListDeniesAllUidsAndDoesNotFallbackToAllowAll() {
        var currentUid = browserUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = emptySet(),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (candidateUid in listOf(browserUid, evilUid, myUid, phoneUid, imsUid)) {
            currentUid = candidateUid
            assertFalse(
                "Empty AllowList must deny UID $candidateUid (must NOT fall back to allow-all)",
                policy.allowConnection(protoTcp, tunSrcV4, 47001, extDstV4, 443),
            )
            assertFalse(
                "Empty AllowList must deny UID $candidateUid over UDP",
                policy.allowConnection(protoUdp, tunSrcV4, 47002, tunDnsV4, 53),
            )
        }
    }

    @Test
    fun t2_a1_02_allUninstalledAllowListDeniesAllUidsAndExcludesSelfAndTelephony() {
        var currentUid = browserUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf("com.uninstalled.one", "com.uninstalled.two"),
            packageUidResolver = { pkg ->
                when (pkg) {
                    ownPkg -> myUid
                    "com.android.phone" -> phoneUid
                    "com.shannon.imsservice" -> imsUid
                    else -> null
                }
            },
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (candidateUid in listOf(browserUid, evilUid, myUid, phoneUid, imsUid)) {
            currentUid = candidateUid
            assertFalse(
                "All-uninstalled AllowList must deny UID $candidateUid fail-closed",
                policy.allowConnection(protoTcp, tunSrcV4, 47010, extDstV4, 443),
            )
        }
    }

    @Test
    fun t2_a1_03_allowListStripsOwnPackageAndAlwaysOutsideTheTunnel() {
        var currentUid = myUid
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(ownPkg, "com.android.phone", "com.shannon.imsservice", browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> currentUid }

        for (forbiddenUid in listOf(myUid, phoneUid, imsUid)) {
            currentUid = forbiddenUid
            assertFalse(
                "AllowList must reject self/telephony UID $forbiddenUid even if listed in settings",
                policy.allowConnection(protoTcp, tunSrcV4, 47020, extDstV4, 443),
            )
        }

        currentUid = browserUid
        assertTrue(
            "AllowList must still allow normal app UID $browserUid",
            policy.allowConnection(protoTcp, tunSrcV4, 47021, extDstV4, 443),
        )
    }

    @Test
    fun t2_a1_04_sharedLinuxUidExclusionPropagatesConsistently() {
        val sharedUid = 10250
        val pkgA = "com.shared.blocked"
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(pkgA),
            packageUidResolver = basePackageUids(mapOf(pkgA to sharedUid, "com.shared.sibling" to sharedUid)),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> sharedUid }

        assertFalse(
            "Blocked shared Linux UID $sharedUid must be rejected consistently across packages sharing it",
            policy.allowConnection(protoTcp, tunSrcV4, 47030, extDstV4, 443),
        )
    }

    @Test
    fun t2_a1_05_splitTunnelScreenLoadAppsExcludesOwnPackageAndAlwaysOutside() {
        val src = E2eContractAdapters.stripKotlinComments(
            E2eContractAdapters.readRepoFile(
                "app/src/main/kotlin/com/mydrop/vpn/ui/screens/apps/SplitTunnelScreen.kt",
            ),
        )
        val loadAppsIdx = src.indexOf("fun loadApps(")
        assertTrue("SplitTunnelScreen.kt must define loadApps", loadAppsIdx >= 0)
        val loadAppsBody = src.substring(loadAppsIdx)

        val filtersSelfOrAlwaysOutside =
            loadAppsBody.contains("packageName") &&
                (loadAppsBody.contains("ALWAYS_OUTSIDE", ignoreCase = true) ||
                    loadAppsBody.contains("ALWAYS_EXCLUDED", ignoreCase = true) ||
                    loadAppsBody.contains("com.android.phone") ||
                    loadAppsBody.contains("excluded", ignoreCase = true))

        if (!filtersSelfOrAlwaysOutside) {
            fail(
                "Expected M2 F9 fix in SplitTunnelScreen.loadApps: must filter out own packageName " +
                    "and ALWAYS_OUTSIDE_THE_TUNNEL (com.android.phone, com.shannon.imsservice)",
            )
        }
    }

    // ------------------------------------------------------------------ Tier 2: Area A2

    @Test
    fun t2_a2_01_api29CatchesSecurityAndIllegalArgumentExceptionsFailClosed() {
        for (ex in listOf(SecurityException("denied"), IllegalArgumentException("bad proto"))) {
            val policy = E2eContractAdapters.createSplitTunnelPolicy(
                mode = SplitTunnelMode.BlockList,
                splitTunnelPackages = emptySet(),
                packageUidResolver = basePackageUids(),
                sdkInt = 33,
                myUid = myUid,
            ) { _, _, _ -> throw ex }

            assertFalse(
                "API 29+ must fail closed without throwing when lookup throws ${ex::class.simpleName}",
                policy.allowConnection(protoTcp, tunSrcV4, 47100, extDstV4, 443),
            )
        }
    }

    @Test
    fun t2_a2_02_api29NullOrUnavailableConnectivityLookupFailsClosedWithoutNpe() {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.Off,
            splitTunnelPackages = emptySet(),
            packageUidResolver = basePackageUids(),
            sdkInt = 30,
            myUid = myUid,
        ) { _, _, _ -> -1 }

        assertFalse(
            "Unavailable ConnectivityManager (-1) on API 30 must fail closed without NPE",
            policy.allowConnection(protoTcp, tunSrcV4, 47110, extDstV4, 443),
        )
    }

    @Test
    fun t2_a2_03_invalidIpLengthOrOutOfRangePortFailsClosed() {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.Off,
            splitTunnelPackages = emptySet(),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> browserUid }

        assertFalse(
            "Empty srcIP must fail closed",
            policy.allowConnection(protoTcp, byteArrayOf(), 47120, extDstV4, 443),
        )
        assertFalse(
            "3-byte srcIP must fail closed",
            policy.allowConnection(protoTcp, byteArrayOf(1, 2, 3), 47120, extDstV4, 443),
        )
        assertFalse(
            "15-byte dstIP must fail closed",
            policy.allowConnection(protoTcp, tunSrcV4, 47120, ByteArray(15), 443),
        )
        assertFalse(
            "Port 0 must fail closed",
            policy.allowConnection(protoTcp, tunSrcV4, 0, extDstV4, 443),
        )
        assertFalse(
            "Port 65536 must fail closed",
            policy.allowConnection(protoTcp, tunSrcV4, 47120, extDstV4, 65536),
        )
    }

    @Test
    fun t2_a2_04_boundaryApi28VsApi29TransitionOnInvalidUid() {
        val calls28 = AtomicInteger(0)
        val policy28 = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(evilPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 28,
            myUid = myUid,
        ) { _, _, _ ->
            calls28.incrementAndGet()
            -1
        }
        assertTrue("API 28 must allow", policy28.allowConnection(protoTcp, tunSrcV4, 47130, extDstV4, 443))
        assertEquals(0, calls28.get())

        val calls29 = AtomicInteger(0)
        val policy29 = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf(evilPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 29,
            myUid = myUid,
        ) { _, _, _ ->
            calls29.incrementAndGet()
            -1
        }
        assertFalse(
            "API 29 must invoke lookup and fail closed on -1",
            policy29.allowConnection(protoTcp, tunSrcV4, 47130, extDstV4, 443),
        )
        assertEquals(1, calls29.get())
    }

    // ------------------------------------------------------------------ Tier 2: Area A4 (netd boundary)

    @Test
    fun t2_a4_03_netdUidDeniedOnNonDnsPortsAndExternalResolversInAllowList() {
        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, _, _ -> netdUid }

        assertFalse(
            "netd UID must be denied on 172.19.0.2:443",
            policy.allowConnection(protoTcp, tunSrcV4, 53100, tunDnsV4, 443),
        )
        assertFalse(
            "netd UID must be denied on external 1.1.1.1:53",
            policy.allowConnection(protoUdp, tunSrcV4, 53101, byteArrayOf(1, 1, 1, 1), 53),
        )
        assertFalse(
            "netd UID must be denied on external 1.1.1.1:80",
            policy.allowConnection(protoTcp, tunSrcV4, 53102, byteArrayOf(1, 1, 1, 1), 80),
        )
    }

    // ------------------------------------------------------------------ Tier 2: Area A6 (IPv6 unmapping corners)

    @Test
    fun t2_a6_01_pureIpv6AddressPreservedAs16ByteInet6Address() {
        val pureSrcV6 = InetAddress.getByName("fdfe:dcba:9876::1").address
        val pureDstV6 = InetAddress.getByName("2606:4700:4700::1111").address
        var capturedSrc: InetAddress? = null
        var capturedDst: InetAddress? = null

        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, src, dst ->
            capturedSrc = src.address
            capturedDst = dst.address
            browserUid
        }

        assertTrue(policy.allowConnection(protoTcp, pureSrcV6, 54100, pureDstV6, 443))
        assertTrue("Pure IPv6 src must stay Inet6Address", capturedSrc is Inet6Address)
        assertTrue("Pure IPv6 dst must stay Inet6Address", capturedDst is Inet6Address)
        assertTrue(pureSrcV6.contentEquals(capturedSrc!!.address))
        assertTrue(pureDstV6.contentEquals(capturedDst!!.address))
    }

    @Test
    fun t2_a6_02_ipv4CompatibleIpv6NotUnmappedUnlikeIpv4Mapped() {
        // ::ac13:0001 (IPv4-compatible, NOT ::ffff:172.19.0.1)
        val compatSrc = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            172.toByte(), 19, 0, 1,
        )
        val mappedDst = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            93.toByte(), 184.toByte(), 216.toByte(), 34,
        )
        var capturedSrc: InetAddress? = null
        var capturedDst: InetAddress? = null

        val policy = E2eContractAdapters.createSplitTunnelPolicy(
            mode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(browserPkg),
            packageUidResolver = basePackageUids(),
            sdkInt = 34,
            myUid = myUid,
        ) { _, src, dst ->
            capturedSrc = src.address
            capturedDst = dst.address
            browserUid
        }

        assertTrue(policy.allowConnection(protoTcp, compatSrc, 54110, mappedDst, 443))
        assertTrue("IPv4-compatible ::ac13:1 must stay Inet6Address", capturedSrc is Inet6Address)
        assertTrue("IPv4-mapped ::ffff:93.184.216.34 must unmap to Inet4Address", capturedDst is Inet4Address)
    }

    // ------------------------------------------------------------------ F7 / F9 Service Wiring

    @Test
    fun vpnServiceContract_allowListNeverSkipsOwnPackageOrTelephonyExclusionAndInstallsGuard() {
        val serviceSrc = E2eContractAdapters.stripKotlinComments(
            E2eContractAdapters.readRepoFile(
                "shared/src/main/kotlin/com/mydrop/vpn/vpn/MyDropVpnService.kt",
            ),
        )
        assertFalse(
            "MyDropVpnService.kt must NOT skip ALWAYS_OUTSIDE_THE_TUNNEL + packageName in AllowList mode (F9 bug)",
            serviceSrc.contains("if (settings.splitTunnelMode != SplitTunnelMode.AllowList)"),
        )
        assertTrue(
            "MyDropVpnService.kt must wire SplitTunnelUidPolicy / TrafficGuard into XrayCore (F7)",
            serviceSrc.contains("SplitTunnelUidPolicy"),
        )
    }
}
