package com.mydrop.vpn.core.net

import java.net.InetAddress
import java.net.URL

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
    if (value.contains(":::")) return false

    // A trailing IPv4 part is legal: ::ffff:192.0.2.1, 64:ff9b::203.0.113.77, ::203.0.113.77
    val lastColon = value.lastIndexOf(':')
    val tail = value.substring(lastColon + 1)
    val hasIpv4Tail = tail.contains('.')
    val head = if (hasIpv4Tail) {
        if (!isIpv4(tail)) return false
        if (lastColon > 0 && value[lastColon - 1] == ':') {
            value.substring(0, lastColon + 1)
        } else {
            value.substring(0, lastColon)
        }
    } else {
        value
    }

    if (head.startsWith(':') && !head.startsWith("::")) return false
    if (head.endsWith(':') && !head.endsWith("::")) return false

    val doubleColons = Regex("::").findAll(head).count()
    if (doubleColons > 1) return false
    if (head.contains(":::")) return false

    val groups = head.split(':').filter { it.isNotEmpty() }
    if (groups.any { group -> group.length > 4 || !group.all { it.isHexDigit() } }) return false

    val expected = if (hasIpv4Tail) 6 else 8
    return if (doubleColons == 1) groups.size < expected else groups.size == expected
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun stripIpv6Zone(host: String): String {
    if (':' !in host) return host
    val pct = host.indexOf('%')
    return if (pct > 0) host.substring(0, pct) else host
}

private fun parseIpv4AtonPart(part: String): Long? {
    if (part.isEmpty()) return null
    return when {
        part.startsWith("0x", ignoreCase = true) -> {
            val digits = part.substring(2)
            if (digits.isEmpty()) return null
            var acc = 0L
            for (ch in digits) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> 10 + (ch - 'a')
                    in 'A'..'F' -> 10 + (ch - 'A')
                    else -> return null
                }
                acc = (acc shl 4) + digit
                if (acc > 0xFFFFFFFFL) return null
            }
            acc
        }
        part.length > 1 && part[0] == '0' -> {
            var acc = 0L
            for (ch in part) {
                if (ch !in '0'..'7') return null
                acc = (acc shl 3) + (ch - '0')
                if (acc > 0xFFFFFFFFL) return null
            }
            acc
        }
        else -> {
            var acc = 0L
            for (ch in part) {
                if (ch !in '0'..'9') return null
                acc = acc * 10 + (ch - '0')
                if (acc > 0xFFFFFFFFL) return null
            }
            acc
        }
    }
}

private fun parseIpv4AtonBytes(value: String): ByteArray? {
    val parts = value.split('.')
    if (parts.size !in 1..4) return null
    val values = LongArray(parts.size) { i ->
        parseIpv4AtonPart(parts[i]) ?: return null
    }
    val b0: Long
    val b1: Long
    val b2: Long
    val b3: Long
    when (values.size) {
        1 -> {
            val v = values[0]
            b0 = (v ushr 24) and 0xFFL
            b1 = (v ushr 16) and 0xFFL
            b2 = (v ushr 8) and 0xFFL
            b3 = v and 0xFFL
        }
        2 -> {
            if (values[0] > 0xFFL || values[1] > 0xFFFFFFL) return null
            b0 = values[0]
            b1 = (values[1] ushr 16) and 0xFFL
            b2 = (values[1] ushr 8) and 0xFFL
            b3 = values[1] and 0xFFL
        }
        3 -> {
            if (values[0] > 0xFFL || values[1] > 0xFFL || values[2] > 0xFFFFL) return null
            b0 = values[0]
            b1 = values[1]
            b2 = (values[2] ushr 8) and 0xFFL
            b3 = values[2] and 0xFFL
        }
        else -> {
            if (values.any { it > 0xFFL }) return null
            b0 = values[0]
            b1 = values[1]
            b2 = values[2]
            b3 = values[3]
        }
    }
    return byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
}

/** Extracts the bare host (without scheme, userinfo, path, query, fragment, port, or IPv6 zone). */
fun extractAuthorityHost(target: String): String? {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return null
    val withoutScheme = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
    val authority = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    return splitHostPort(authority)?.host?.let(::stripIpv6Zone)?.takeIf { it.isNotBlank() }
}

/**
 * True for resolver/redirect hosts that target loopback, unspecified addresses, `localhost`,
 * or `fakedns`, including non-canonical `inet_aton` IPv4 forms, zoned IPv6, IPv4-mapped IPv6,
 * and trailing-dot forms.
 */
fun isLoopbackOrUnspecifiedHost(host: String): Boolean {
    val raw = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    val clean = stripIpv6Zone(raw)
    if (clean.isEmpty() || clean == "localhost" || clean.endsWith(".localhost")) return true
    if (clean.all { it in '0'..'9' }) return true
    if (clean.startsWith("0x") && clean.length > 2 && clean.drop(2).all { it.isHexDigit() }) return true

    parseIpv4AtonBytes(clean)?.let { bytes ->
        val b0 = bytes[0].toInt() and 0xFF
        return b0 == 127 || b0 == 0
    }

    val normalized = if (':' in clean && '.' in clean) {
        val lastColon = clean.lastIndexOf(':')
        val tail = clean.substring(lastColon + 1)
        val tailBytes = parseIpv4AtonBytes(tail)
        if (tailBytes != null && tailBytes.size == 4) {
            val canonicalTail = tailBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            "${clean.substring(0, lastColon)}:$canonicalTail"
        } else {
            clean
        }
    } else {
        clean
    }

    if (!isNumericAddress(normalized)) return false
    return runCatching {
        val addr = InetAddress.getByName(normalized)
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return@runCatching true
        val bytes = addr.address
        (bytes.size == 4 && ((bytes[0].toInt() and 0xFF) == 127 || (bytes[0].toInt() and 0xFF) == 0)) ||
            (bytes.size == 16 && (0..11).all { bytes[it] == 0.toByte() } && (bytes[12].toInt() and 0xFF) == 127)
    }.getOrDefault(true)
}

/** True when [host] must never be emitted as an Xray DNS server address. */
fun isForbiddenDnsHost(host: String): Boolean {
    val clean = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (clean == "fakedns") return true
    return isLoopbackOrUnspecifiedHost(clean)
}

private fun isNonCanonicalOrZonedIpLiteral(host: String): Boolean {
    val raw = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (raw.isEmpty()) return false
    if (':' in raw && '%' in raw) return true
    if (raw.all { it in '0'..'9' }) return true
    if (raw.startsWith("0x") && raw.length > 2 && raw.drop(2).all { it.isHexDigit() }) return true
    if (parseIpv4AtonBytes(raw) != null && !isIpv4(raw)) return true
    return false
}

/**
 * Resolves [location] relative to [source] for direct (non-tunnelled) HTTP requests, blocking
 * non-http(s) schemes, non-canonical/zoned IP literals, and SSRF redirects from external hosts
 * (or cross-origin hosts) into loopback / unspecified / `localhost` targets.
 */
fun resolveSafeDirectRedirect(source: URL, location: String): URL {
    val next = URL(source, location)
    val scheme = next.protocol.lowercase()
    check(scheme == "http" || scheme == "https") {
        "Blocked unsafe redirect scheme: ${next.protocol}"
    }
    val nextHost = next.host.orEmpty()
    check(nextHost.isNotBlank()) { "Blocked redirect without a host" }
    check(!isNonCanonicalOrZonedIpLiteral(nextHost)) {
        "Blocked unsafe redirect to non-canonical or zoned IP host: $nextHost"
    }
    if (isLoopbackOrUnspecifiedHost(nextHost)) {
        val sourceHost = source.host.orEmpty()
        val sameLoopbackOrigin = isLoopbackOrUnspecifiedHost(sourceHost) &&
            !isNonCanonicalOrZonedIpLiteral(sourceHost) &&
            sourceHost.equals(nextHost, ignoreCase = true) &&
            effectivePort(source) == effectivePort(next)
        check(sameLoopbackOrigin) {
            "Blocked unsafe redirect to loopback/unspecified host: $nextHost"
        }
    }
    return next
}

private fun effectivePort(url: URL): Int =
    if (url.port != -1) url.port else url.defaultPort
