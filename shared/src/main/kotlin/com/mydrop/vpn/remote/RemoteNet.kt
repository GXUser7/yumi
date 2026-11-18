package com.mydrop.vpn.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mydrop.vpn.core.net.isNumericAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress

internal data class NetworkTransportFlags(
    val hasWifi: Boolean,
    val hasEthernet: Boolean,
    val hasVpn: Boolean,
)

internal fun isLanTransport(flags: NetworkTransportFlags): Boolean =
    !flags.hasVpn && (flags.hasWifi || flags.hasEthernet)

internal fun NetworkCapabilities.toTransportFlags(): NetworkTransportFlags = NetworkTransportFlags(
    hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
    hasVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
)

internal data class LanNetworkCandidate<T>(
    val value: T,
    val transports: NetworkTransportFlags,
    val addresses: List<InetAddress>,
)

internal fun <T> filterLanCandidates(
    candidates: Iterable<LanNetworkCandidate<T>>,
): List<LanNetworkCandidate<T>> = candidates.filter { isLanTransport(it.transports) }

internal fun <T> selectLanAddress(
    candidates: Iterable<LanNetworkCandidate<T>>,
): Inet4Address? = filterLanCandidates(candidates).asSequence()
    .flatMap { it.addresses.asSequence() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { isLanPrivateIpv4(it) }

/** Excludes the Xray TUN subnet 172.19.0.0/30 (e.g. 172.19.0.1 / 172.19.0.2). */
internal fun isTunSubnetIpv4(address: Inet4Address): Boolean {
    val bytes = address.address
    return bytes.size == 4 &&
        bytes[0] == 172.toByte() &&
        bytes[1] == 19.toByte() &&
        bytes[2] == 0.toByte() &&
        (bytes[3].toInt() and 0xFC) == 0
}

internal fun isLanPrivateIpv4(address: InetAddress): Boolean =
    address is Inet4Address &&
        address.isSiteLocalAddress &&
        !address.isLoopbackAddress &&
        !isTunSubnetIpv4(address)

/*
 * The local network, as the remote needs it.
 *
 * The same ground `pairing/` covers, kept separate because this half is asked different questions:
 * that one wants an address to print on a screen, this one also wants the networks to send a
 * broadcast down and the interfaces to bind a datagram socket to.
 *
 * Both apps exclude themselves from their own tunnel — see the `addDisallowedApplication` in
 * MyDropVpnService — so none of this has to fight the VPN for a route. The sockets are still bound
 * to the Wi-Fi or Ethernet network by name, because "excluded from the tunnel" and "left the house
 * by the right door" are not quite the same statement on a phone holding several networks at once.
 */

/** Wi-Fi and Ethernet (never VPN), in that order of usefulness. Cellular can never carry a remote. */
internal fun localNetworks(context: Context): List<Network> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    return manager.allNetworks.filter { network ->
        manager.getNetworkCapabilities(network)?.toTransportFlags()?.let(::isLanTransport) == true
    }
}

/** The address to advertise: the one a set-top box is reachable at from the sofa. */
internal fun localAddress(context: Context): Inet4Address? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return localNetworks(context).asSequence()
        .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { isLanPrivateIpv4(it) }
}

/**
 * Every address a probe should be shouted at.
 *
 * The all-ones broadcast is what most home routers pass, and the subnet-directed one is what the
 * rest do — the two together cover both without needing to know which kind of network this is.
 * Derived from the prefix the system reports rather than guessed at /24, because a set on a /16
 * would never hear a probe addressed to the wrong last octet.
 */
internal fun broadcastAddresses(context: Context): List<InetAddress> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val directed = manager?.let {
        localNetworks(context).flatMap { network ->
            it.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                val address = link.address as? Inet4Address ?: return@mapNotNull null
                if (!isLanPrivateIpv4(address) || link.prefixLength !in 1..31) return@mapNotNull null
                val raw = address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
                val hostBits = 32 - link.prefixLength
                val broadcast = raw or ((1L shl hostBits) - 1)
                runCatching {
                    InetAddress.getByAddress(
                        byteArrayOf(
                            (broadcast ushr 24).toByte(),
                            (broadcast ushr 16).toByte(),
                            (broadcast ushr 8).toByte(),
                            broadcast.toByte(),
                        ),
                    )
                }.getOrNull()
            }
        }
    }.orEmpty()
    return (directed + InetAddress.getByName("255.255.255.255")).distinct()
}

/** Refuses to talk to anything off the local network, which a remote has no business reaching. */
internal fun isPrivateIpv4(host: String): Boolean = runCatching {
    isNumericAddress(host) && isLanPrivateIpv4(InetAddress.getByName(host))
}.getOrDefault(false)

/* ── Framing ──────────────────────────────────────────────────────────────────────────────── */

/**
 * Four bytes of length and then that many bytes.
 *
 * The size is checked before the array is allocated, because the number arrives from the network
 * and `ByteArray(size)` with a hostile size is an out-of-memory error rather than a rejected frame.
 */
internal fun DataInputStream.readFrame(): ByteArray {
    val size = readInt()
    require(size in 1..REMOTE_MAX_FRAME_BYTES) { "Invalid remote frame size" }
    return ByteArray(size).also(::readFully)
}

internal fun DataOutputStream.writeFrame(bytes: ByteArray) {
    require(bytes.size in 1..REMOTE_MAX_FRAME_BYTES)
    writeInt(bytes.size)
    write(bytes)
    flush()
}
