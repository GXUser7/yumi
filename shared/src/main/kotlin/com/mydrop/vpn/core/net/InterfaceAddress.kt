package com.mydrop.vpn.core.net

/**
 * Formats one interface address as the CIDR string the sing-box core expects.
 *
 * The zone suffix is the whole point of this function. `InetAddress.getHostAddress()` renders a
 * link-local IPv6 address with its scope attached — `fe80::38dd:baff:fe7a:5fc%dummy0` — and the
 * core feeds every address it receives to Go's `netip.MustParsePrefix`. That function rejects
 * zones, and being the `Must` variant it **panics** rather than returning an error, which takes
 * the entire process down with a native SIGABRT: no Java exception, and a tombstone whose only
 * frame is `runtime.raise`.
 *
 * Every Android device has at least one link-local address, so this was not an edge case — it
 * aborted the tunnel on the first interface enumeration, every time.
 *
 * Kept out of the VpnService, and free of Android types, so it can be tested.
 */
fun interfaceCidr(hostAddress: String?, prefixLength: Int): String? {
    val address = hostAddress?.substringBefore('%')?.takeIf { it.isNotEmpty() } ?: return null
    if (prefixLength < 0) return null
    return "$address/$prefixLength"
}
