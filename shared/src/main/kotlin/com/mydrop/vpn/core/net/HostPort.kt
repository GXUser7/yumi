package com.mydrop.vpn.core.net

/**
 * One answer to "is this an address, and where does the host end and the port begin".
 *
 * There used to be four answers, and three of them were wrong about IPv6. `splitHostPort` in the
 * URI parser cut on the last colon, so `2001:db8::1` became host `2001:db8:` and port 1 — a node
 * that could never dial, created without complaint. `dnsServer` in the config factory did the
 * same to a resolver address, which turned any IPv6 DNS the user typed into a configuration the
 * core rejected outright. And two separate "is this numeric" checks decided by looking for
 * hex-ish characters, so a hostname like `ad.cafe` read as an IP address.
 *
 * Kept free of Android types so it can be tested on the JVM, like [interfaceCidr] next door.
 */

/** Host and port pulled apart, with the host stripped of IPv6 brackets. */
data class HostPort(val host: String, val port: Int?)

/**
 * Splits `host`, `host:port`, `[v6]` or `[v6]:port`.
 *
 * A bare IPv6 literal — no brackets, which is how people type one into a settings field — is
 * recognised by having more than one colon and taken whole. That is the only reading that can be
 * right: `2001:db8::1` has no port, and pretending the last group is one is how the old code
 * produced dead servers.
 *
 * Returns null when there is no host at all, or when a port is present but out of range.
 */
fun splitHostPort(raw: String): HostPort? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf(']')
        if (close <= 1) return null
        val host = trimmed.substring(1, close)
        val rest = trimmed.substring(close + 1)
        if (rest.isEmpty()) return HostPort(host, null)
        if (!rest.startsWith(":")) return null
        val port = rest.drop(1).toIntOrNull() ?: return null
        return if (port in 1..65535) HostPort(host, port) else null
    }

    val colons = trimmed.count { it == ':' }
    if (colons == 0) return HostPort(trimmed, null)
    if (colons > 1) return HostPort(trimmed, null) // bare IPv6 literal

    val host = trimmed.substringBefore(':')
    val port = trimmed.substringAfter(':').toIntOrNull() ?: return null
    if (host.isEmpty()) return null
    return if (port in 1..65535) HostPort(host, port) else null
}

/**
 * True for an address the core can dial without resolving anything first.
 *
 * Parsed rather than sniffed. The check this replaces asked whether every character was a digit,
 * a dot, a colon or a hex letter, which says yes to `dead.beef` and to `ad.cafe` — both perfectly
 * ordinary hostnames. Getting that wrong is not cosmetic: the config factory decides from it
 * whether a bootstrap resolver is needed, and a name it mistakes for an address is a tunnel that
 * fails to start with "missing domain resolver for domain server address".
 */
fun isNumericAddress(value: String): Boolean {
    val address = value.trim().removeSurrounding("[", "]")
    if (address.isEmpty()) return false
    return isIpv4(address) || isIpv6(address)
}

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        // "01" is not a valid octet, and toIntOrNull would happily accept "+1" and " 1".
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
            (part.length == 1 || part[0] != '0') &&
            part.toInt() in 0..255
    }
}

private fun isIpv6(value: String): Boolean {
    if (!value.contains(':')) return false

    // A trailing IPv4 part is legal: ::ffff:192.0.2.1
    val lastColon = value.lastIndexOf(':')
    val tail = value.substring(lastColon + 1)
    val head = if (tail.contains('.')) {
        if (!isIpv4(tail)) return false
        value.substring(0, lastColon)
    } else {
        value
    }

    val doubleColons = Regex("::").findAll(head).count()
    if (doubleColons > 1) return false
    if (head.contains(":::")) return false

    val groups = head.split(':').filter { it.isNotEmpty() }
    if (groups.any { group -> group.length > 4 || !group.all { it.isHexDigit() } }) return false

    val expected = if (tail.contains('.')) 6 else 8
    return if (doubleColons == 1) groups.size < expected else groups.size == expected
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
