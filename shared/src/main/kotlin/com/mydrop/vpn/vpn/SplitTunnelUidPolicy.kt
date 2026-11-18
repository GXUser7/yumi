package com.mydrop.vpn.vpn

import android.os.Build
import android.os.Process
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.xray.XrayConfigFactory
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Fail-closed per-UID policy evaluated for every TCP SYN and UDP flow entering `tun-in`.
 *
 * Designed to stay pure-JVM safe (no `OsConstants`, no `android.util.Log`) so unit and E2E tests
 * can exercise every edge case without Robolectric or mocks.
 */
class SplitTunnelUidPolicy internal constructor(
    val mode: SplitTunnelMode,
    val allowedUids: Set<Int>,
    val excludedUids: Set<Int>,
    private val sdkInt: Int,
    private val myUid: Int,
    private val ownerUidResolver: (proto: Int, src: InetSocketAddress, dst: InetSocketAddress) -> Int,
) {

    fun allowConnection(
        proto: Int,
        srcIP: ByteArray,
        srcPort: Int,
        dstIP: ByteArray,
        dstPort: Int,
    ): Boolean = runCatching {
        if (sdkInt < Build.VERSION_CODES.Q) return@runCatching true
        if (proto != PROTO_TCP && proto != PROTO_UDP) return@runCatching false
        if (srcPort !in 1..65535 || dstPort !in 1..65535) return@runCatching false

        val srcBytes = normalizeIpBytes(srcIP) ?: return@runCatching false
        val dstBytes = normalizeIpBytes(dstIP) ?: return@runCatching false
        val srcAddr = InetAddress.getByAddress(srcBytes)
        val dstAddr = InetAddress.getByAddress(dstBytes)
        if (srcAddr.isAnyLocalAddress || dstAddr.isAnyLocalAddress) return@runCatching false

        val uid = ownerUidResolver(
            proto,
            InetSocketAddress(srcAddr, srcPort),
            InetSocketAddress(dstAddr, dstPort),
        )
        if (uid < 0) return@runCatching false
        if (uid == myUid || uid in excludedUids) return@runCatching false

        when (mode) {
            SplitTunnelMode.Off, SplitTunnelMode.BlockList -> true
            SplitTunnelMode.AllowList -> {
                if (allowedUids.isEmpty()) {
                    false
                } else {
                    uid in allowedUids || isNetdTunDnsQuery(uid, dstBytes, dstPort)
                }
            }
        }
    }.getOrDefault(false)

    private fun isNetdTunDnsQuery(uid: Int, dstBytes: ByteArray, dstPort: Int): Boolean =
        (uid == NETD_ROOT_UID || uid == NETD_DNS_UID) &&
            dstPort == DNS_PORT &&
            dstBytes.contentEquals(TUN_DNS_V4_BYTES)

    private fun normalizeIpBytes(raw: ByteArray): ByteArray? = when (raw.size) {
        4 -> raw
        16 -> {
            var isMappedV4 = true
            for (i in 0 until 10) {
                if (raw[i].toInt() != 0) {
                    isMappedV4 = false
                    break
                }
            }
            if (isMappedV4 && raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()) {
                raw.copyOfRange(12, 16)
            } else {
                raw
            }
        }
        else -> null
    }

    companion object {
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
        const val NETD_ROOT_UID = 0
        const val NETD_DNS_UID = 1051
        const val DNS_PORT = 53

        /**
         * Packages kept outside the tunnel regardless of split-tunneling mode.
         * Shared by [MyDropVpnService], [InstalledApps], and `SplitTunnelScreen`.
         */
        val ALWAYS_OUTSIDE_PACKAGES: List<String> = listOf(
            "com.android.phone",
            "com.shannon.imsservice",
        )

        private val TUN_DNS_V4_BYTES: ByteArray =
            InetAddress.getByName(XrayConfigFactory.TUN_DNS_V4).address

        fun create(
            settings: AppSettings,
            packageUidResolver: (String) -> Int?,
            sdkInt: Int = Build.VERSION.SDK_INT,
            myUid: Int = Process.myUid(),
            ownPackageName: String = "com.mydrop.vpn",
            ownerUidResolver: (proto: Int, src: InetSocketAddress, dst: InetSocketAddress) -> Int,
        ): SplitTunnelUidPolicy {
            val alwaysOutsidePackages = (ALWAYS_OUTSIDE_PACKAGES + ownPackageName).toSet()
            val resolvedOutside = alwaysOutsidePackages
                .mapNotNull(packageUidResolver)
                .filterTo(HashSet()) { it >= 0 }
            if (myUid >= 0) {
                resolvedOutside.add(myUid)
            }

            return when (settings.splitTunnelMode) {
                SplitTunnelMode.Off -> SplitTunnelUidPolicy(
                    mode = settings.splitTunnelMode,
                    allowedUids = emptySet(),
                    excludedUids = resolvedOutside,
                    sdkInt = sdkInt,
                    myUid = myUid,
                    ownerUidResolver = ownerUidResolver,
                )

                SplitTunnelMode.BlockList -> {
                    val blocked = settings.splitTunnelPackages
                        .mapNotNull(packageUidResolver)
                        .filterTo(HashSet(resolvedOutside)) { it >= 0 }
                    SplitTunnelUidPolicy(
                        mode = settings.splitTunnelMode,
                        allowedUids = emptySet(),
                        excludedUids = blocked,
                        sdkInt = sdkInt,
                        myUid = myUid,
                        ownerUidResolver = ownerUidResolver,
                    )
                }

                SplitTunnelMode.AllowList -> {
                    val allowed = (settings.splitTunnelPackages - alwaysOutsidePackages)
                        .mapNotNull(packageUidResolver)
                        .filterTo(HashSet()) { it >= 0 && it !in resolvedOutside }
                    SplitTunnelUidPolicy(
                        mode = settings.splitTunnelMode,
                        allowedUids = allowed,
                        excludedUids = resolvedOutside,
                        sdkInt = sdkInt,
                        myUid = myUid,
                        ownerUidResolver = ownerUidResolver,
                    )
                }
            }
        }
    }
}
