package com.mydrop.vpn.vpn

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelUidPolicyTest {

    private val ownPkg = "com.mydrop.vpn"
    private val myUid = 10050
    private val phoneUid = 1001
    private val imsUid = 10099
    private val allowedAppUid = 10111
    private val allowedApp2Uid = 10112
    private val excludedAppUid = 10222
    private val bystanderAppUid = 10333

    private val packageMap = mapOf(
        ownPkg to myUid,
        "com.android.phone" to phoneUid,
        "com.shannon.imsservice" to imsUid,
        "com.example.allowed" to allowedAppUid,
        "com.example.allowed2" to allowedApp2Uid,
        "com.example.excluded" to excludedAppUid,
        "com.example.sharedradio" to phoneUid,
    )

    private val tunSrcV4 = byteArrayOf(172.toByte(), 19, 0, 1)
    private val dstExternalV4 = byteArrayOf(1, 1, 1, 1)
    private val tunDnsV4 = byteArrayOf(172.toByte(), 19, 0, 2)
    private val tunSrcV6 = InetAddress.getByName("fdfe:dcba:9876::1").address
    private val dstExternalV6 = InetAddress.getByName("2606:4700:4700::1111").address

    @Test
    fun `Off mode allows third-party apps on TCP and UDP IPv4 and IPv6, blocks own and telephony UIDs`() {
        val policy = SplitTunnelUidPolicy.create(
            settings = AppSettings(splitTunnelMode = SplitTunnelMode.Off),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> bystanderAppUid },
        )
        assertTrue(policy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))
        assertTrue(policy.allowConnection(17, tunSrcV6, 51000, dstExternalV6, 443))

        for (blockedUid in listOf(myUid, phoneUid, imsUid, -1)) {
            val blockedPolicy = SplitTunnelUidPolicy.create(
                settings = AppSettings(splitTunnelMode = SplitTunnelMode.Off),
                packageUidResolver = packageMap::get,
                sdkInt = 34,
                myUid = myUid,
                ownPackageName = ownPkg,
                ownerUidResolver = { _, _, _ -> blockedUid },
            )
            assertFalse("UID $blockedUid must fail-closed in Off mode", blockedPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))
        }
    }

    @Test
    fun `BlockList mode blocks listed packages, own UID, and telephony UIDs while allowing unlisted apps`() {
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf("com.example.excluded"),
        )
        var resolvedUid = excludedAppUid
        val policy = SplitTunnelUidPolicy.create(
            settings = settings,
            packageUidResolver = packageMap::get,
            sdkInt = 33,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> resolvedUid },
        )

        assertFalse(policy.allowConnection(6, tunSrcV4, 12345, dstExternalV4, 443))
        resolvedUid = myUid
        assertFalse(policy.allowConnection(17, tunSrcV4, 12345, dstExternalV4, 53))
        resolvedUid = phoneUid
        assertFalse(policy.allowConnection(6, tunSrcV4, 12345, dstExternalV4, 443))
        resolvedUid = imsUid
        assertFalse(policy.allowConnection(6, tunSrcV4, 12345, dstExternalV4, 443))

        resolvedUid = bystanderAppUid
        assertTrue(policy.allowConnection(6, tunSrcV4, 12345, dstExternalV4, 443))
        assertTrue(policy.allowConnection(17, tunSrcV4, 12345, tunDnsV4, 53))
    }

    @Test
    fun `AllowList mode allows only selected UIDs, strips own and telephony even if injected, and enforces netd DNS boundary`() {
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf(
                "com.example.allowed",
                ownPkg,
                "com.android.phone",
                "com.shannon.imsservice",
                "com.example.sharedradio",
            ),
        )
        var resolvedUid = allowedAppUid
        val policy = SplitTunnelUidPolicy.create(
            settings = settings,
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> resolvedUid },
        )

        assertEquals(setOf(allowedAppUid), policy.allowedUids)
        assertTrue(policy.allowConnection(6, tunSrcV4, 22222, dstExternalV4, 443))

        // Excluded / unlisted / own / shared-telephony UIDs fail closed even for TUN DNS 53
        for (denied in listOf(bystanderAppUid, myUid, phoneUid, imsUid, -1)) {
            resolvedUid = denied
            assertFalse("UID $denied must be denied on TCP 443", policy.allowConnection(6, tunSrcV4, 22222, dstExternalV4, 443))
            assertFalse("UID $denied must be denied on TUN DNS 53", policy.allowConnection(17, tunSrcV4, 22222, tunDnsV4, 53))
        }

        // netd (0 and 1051) allowed ONLY for 172.19.0.2:53
        for (netd in listOf(0, 1051)) {
            resolvedUid = netd
            assertTrue("netd $netd allowed for 172.19.0.2:53", policy.allowConnection(17, tunSrcV4, 33333, tunDnsV4, 53))
            assertFalse("netd $netd denied for 172.19.0.2:443", policy.allowConnection(6, tunSrcV4, 33333, tunDnsV4, 443))
            assertFalse("netd $netd denied for 1.1.1.1:53", policy.allowConnection(17, tunSrcV4, 33333, dstExternalV4, 53))
            assertFalse("netd $netd denied for 1.1.1.1:80", policy.allowConnection(6, tunSrcV4, 33333, dstExternalV4, 80))
        }
    }

    @Test
    fun `empty or all-uninstalled AllowList fails closed for every UID including netd`() {
        val emptyPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(splitTunnelMode = SplitTunnelMode.AllowList, splitTunnelPackages = emptySet()),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> bystanderAppUid },
        )
        assertTrue(emptyPolicy.allowedUids.isEmpty())
        assertFalse(emptyPolicy.allowConnection(6, tunSrcV4, 44444, dstExternalV4, 443))
        assertFalse(emptyPolicy.allowConnection(17, tunSrcV4, 44444, tunDnsV4, 53))

        var uid = 1051
        val uninstalledPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(
                splitTunnelMode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf("com.missing.app", ownPkg, "com.android.phone"),
            ),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> uid },
        )
        assertTrue(uninstalledPolicy.allowedUids.isEmpty())
        assertFalse(uninstalledPolicy.allowConnection(17, tunSrcV4, 44444, tunDnsV4, 53))
        uid = allowedApp2Uid
        assertFalse(uninstalledPolicy.allowConnection(6, tunSrcV4, 44444, dstExternalV4, 443))
    }

    @Test
    fun `IPv4-mapped IPv6 is unmapped while IPv4-compatible IPv6 is preserved and invalid inputs fail closed`() {
        val captured = mutableListOf<Pair<InetSocketAddress, InetSocketAddress>>()
        val policy = SplitTunnelUidPolicy.create(
            settings = AppSettings(
                splitTunnelMode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf("com.example.allowed"),
            ),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, src, dst ->
                captured += src to dst
                1051
            },
        )

        // ::ffff:172.19.0.1 -> ::ffff:172.19.0.2:53
        val mappedSrc = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 172.toByte(), 19, 0, 1)
        val mappedDns = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 172.toByte(), 19, 0, 2)
        assertTrue(policy.allowConnection(17, mappedSrc, 55555, mappedDns, 53))
        assertTrue(captured.single().first.address is Inet4Address)
        assertTrue(captured.single().second.address is Inet4Address)
        assertEquals("172.19.0.2", captured.single().second.address.hostAddress)

        // ::ac13:1 (IPv4-compatible IPv6) must remain Inet6Address
        captured.clear()
        val compatSrc = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 172.toByte(), 19, 0, 1)
        assertFalse(policy.allowConnection(6, compatSrc, 55555, dstExternalV6, 443))
        assertTrue(captured.single().first.address is Inet6Address)

        // Invalid proto, port, IP length, unspecified addresses, and exceptions never reach resolver or throw
        var resolverCalls = 0
        val throwingPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ ->
                resolverCalls++
                throw SecurityException("boom")
            },
        )
        assertFalse(throwingPolicy.allowConnection(1, tunSrcV4, 1000, dstExternalV4, 443))
        assertFalse(throwingPolicy.allowConnection(6, tunSrcV4, 0, dstExternalV4, 443))
        assertFalse(throwingPolicy.allowConnection(6, tunSrcV4, 1000, dstExternalV4, 65536))
        assertFalse(throwingPolicy.allowConnection(6, byteArrayOf(1, 2, 3), 1000, dstExternalV4, 443))
        assertFalse(throwingPolicy.allowConnection(6, byteArrayOf(0, 0, 0, 0), 1000, dstExternalV4, 443))
        assertFalse(throwingPolicy.allowConnection(6, tunSrcV6, 1000, ByteArray(16), 443))
        val mappedZero = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 0)
        assertFalse(throwingPolicy.allowConnection(6, tunSrcV4, 1000, mappedZero, 443))
        assertEquals(0, resolverCalls)

        // Resolver throwing exception fails closed cleanly
        assertFalse(throwingPolicy.allowConnection(6, tunSrcV4, 1000, dstExternalV4, 443))
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `API 26 to 28 allows cleanly without invoking getConnectionOwnerUid and API 29 enforces`() {
        var resolverCalls = 0
        for (sdk in 26..28) {
            val legacyPolicy = SplitTunnelUidPolicy.create(
                settings = AppSettings(splitTunnelMode = SplitTunnelMode.AllowList),
                packageUidResolver = packageMap::get,
                sdkInt = sdk,
                myUid = myUid,
                ownPackageName = ownPkg,
                ownerUidResolver = { _, _, _ ->
                    resolverCalls++
                    -1
                },
            )
            assertTrue("API $sdk must allow cleanly", legacyPolicy.allowConnection(6, tunSrcV4, 1000, dstExternalV4, 443))
        }
        assertEquals(0, resolverCalls)

        val api29Policy = SplitTunnelUidPolicy.create(
            settings = AppSettings(splitTunnelMode = SplitTunnelMode.AllowList),
            packageUidResolver = packageMap::get,
            sdkInt = 29,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ ->
                resolverCalls++
                bystanderAppUid
            },
        )
        assertFalse(api29Policy.allowConnection(6, tunSrcV4, 1000, dstExternalV4, 443))
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `F12 AppSettings declares zero split-tunnel guard or leak toggles`() {
        val fields = AppSettings::class.java.declaredFields.map { it.name.lowercase() }
        val forbidden = listOf("guard", "leak", "owneruid", "trafficguard", "uidpolicy")
        fields.forEach { name ->
            forbidden.forEach { token ->
                assertFalse("AppSettings must not declare toggle '$name'", token in name)
            }
        }
    }

    @Test
    fun `onDenied reports every fail-closed DenyReason, ignores allowed flows, and swallows callback exceptions`() {
        data class DeniedEvent(
            val reason: SplitTunnelUidPolicy.DenyReason,
            val proto: Int,
            val dstPort: Int,
            val uid: Int,
        )

        val events = mutableListOf<DeniedEvent>()
        var nextUid = bystanderAppUid
        var shouldThrow = false

        val blockListPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(
                splitTunnelMode = SplitTunnelMode.BlockList,
                splitTunnelPackages = setOf("com.example.excluded"),
            ),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ ->
                if (shouldThrow) throw SecurityException("binder error")
                nextUid
            },
            onDenied = { reason, proto, dstPort, uid ->
                events += DeniedEvent(reason, proto, dstPort, uid)
            },
        )

        // Allowed flows never trigger onDenied
        assertTrue(blockListPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))
        assertTrue(blockListPolicy.allowConnection(17, tunSrcV6, 51000, dstExternalV6, 53))
        assertTrue(events.isEmpty())

        // UNSUPPORTED_PROTO, INVALID_PORT, INVALID_ADDRESS
        assertFalse(blockListPolicy.allowConnection(1, tunSrcV4, 41000, dstExternalV4, 443))
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 0, dstExternalV4, 80))
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 65536))
        assertFalse(blockListPolicy.allowConnection(6, byteArrayOf(1, 2, 3), 41000, dstExternalV4, 443))
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 41000, byteArrayOf(0, 0, 0, 0), 8443))

        // UNRESOLVED_UID, OWN_UID, EXCLUDED_UID
        nextUid = -1
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 5228))
        nextUid = myUid
        assertFalse(blockListPolicy.allowConnection(17, tunSrcV4, 41000, dstExternalV4, 53))
        nextUid = excludedAppUid
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))

        // EXCEPTION
        shouldThrow = true
        assertFalse(blockListPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 993))

        assertEquals(
            listOf(
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.UNSUPPORTED_PROTO, 1, 443, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.INVALID_PORT, 6, 80, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.INVALID_PORT, 6, 65536, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.INVALID_ADDRESS, 6, 443, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.INVALID_ADDRESS, 6, 8443, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.OWN_UID, 17, 53, myUid),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.EXCLUDED_UID, 6, 443, excludedAppUid),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.EXCEPTION, 6, 993, -1),
            ),
            events,
        )

        // EMPTY_ALLOWLIST & NOT_IN_ALLOWLIST
        events.clear()
        val emptyAllowPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(splitTunnelMode = SplitTunnelMode.AllowList),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> bystanderAppUid },
            onDenied = { reason, proto, dstPort, uid ->
                events += DeniedEvent(reason, proto, dstPort, uid)
            },
        )
        assertFalse(emptyAllowPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))

        val allowPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(
                splitTunnelMode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf("com.example.allowed"),
            ),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> bystanderAppUid },
            onDenied = { reason, proto, dstPort, uid ->
                events += DeniedEvent(reason, proto, dstPort, uid)
            },
        )
        assertFalse(allowPolicy.allowConnection(17, tunSrcV4, 41000, dstExternalV4, 443))
        assertEquals(
            listOf(
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.EMPTY_ALLOWLIST, 6, 443, bystanderAppUid),
                DeniedEvent(SplitTunnelUidPolicy.DenyReason.NOT_IN_ALLOWLIST, 17, 443, bystanderAppUid),
            ),
            events,
        )

        // Throwing onDenied callback never crashes or alters fail-closed return
        val crashingCallbackPolicy = SplitTunnelUidPolicy.create(
            settings = AppSettings(),
            packageUidResolver = packageMap::get,
            sdkInt = 34,
            myUid = myUid,
            ownPackageName = ownPkg,
            ownerUidResolver = { _, _, _ -> myUid },
            onDenied = { _, _, _, _ -> throw IllegalStateException("log sink broke") },
        )
        assertFalse(crashingCallbackPolicy.allowConnection(6, tunSrcV4, 41000, dstExternalV4, 443))
    }

    @Test
    fun `TrafficGuardRateLimiter emits immediately, suppresses repeats within window, resets, and bounds maxKeys`() {
        var now = 1_000L
        val limiter = TrafficGuardRateLimiter(
            windowMillis = 15_000L,
            maxKeys = 2,
            nowMillis = { now },
        )

        assertEquals(
            "denied reason=unresolved_uid proto=tcp dstPort=5228 uid=-1",
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )
        now += 5_000L
        assertEquals(
            null,
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )
        now += 5_000L
        assertEquals(
            null,
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )
        now += 5_000L // 16_000L - 1_000L == 15_000L -> window elapsed
        assertEquals(
            "denied reason=unresolved_uid proto=tcp dstPort=5228 uid=-1 (+2 suppressed)",
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )

        // Fill maxKeys = 2 with fresh entries, then insert a 3rd key -> oldest (16_000L) evicted
        now += 1_000L // 17_000L
        assertEquals(
            "denied reason=excluded_uid proto=udp dstPort=443 uid=10222",
            limiter.record(SplitTunnelUidPolicy.DenyReason.EXCLUDED_UID, 17, 443, 10222),
        )
        now += 1_000L // 18_000L
        assertEquals(
            "denied reason=own_uid proto=tcp dstPort=80 uid=10050",
            limiter.record(SplitTunnelUidPolicy.DenyReason.OWN_UID, 6, 80, 10050),
        )
        // Confirm (UNRESOLVED_UID, 5228) was evicted -> re-recording emits cleanly with 0 suppressed
        now += 1_000L // 19_000L (still within 15s of 16_000L!)
        assertEquals(
            "denied reason=unresolved_uid proto=tcp dstPort=5228 uid=-1",
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )

        // Reset clears suppression and windows
        now += 100L
        assertEquals(null, limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1))
        limiter.reset()
        assertEquals(
            "denied reason=unresolved_uid proto=tcp dstPort=5228 uid=-1",
            limiter.record(SplitTunnelUidPolicy.DenyReason.UNRESOLVED_UID, 6, 5228, -1),
        )
    }
}
