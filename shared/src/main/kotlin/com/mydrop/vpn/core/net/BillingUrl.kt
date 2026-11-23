package com.mydrop.vpn.core.net

import java.net.InetAddress
import java.net.URL

/**
 * Validates billing and checkout URLs before the client opens or queries them.
 *
 * Only `https://` URLs targeting non-loopback, non-unspecified hosts are accepted in release
 * builds. Debug builds and unit tests can pass [allowLoopbackHttp] = true to hit a local mock
 * server (`127.0.0.1`, `[::1]`, `localhost`, or Android emulator host `10.0.2.2`), while
 * unspecified addresses (`0.0.0.0`, `::`), non-canonical IP encodings, embedded userinfo, and
 * non-HTTP schemes (`javascript:`, `file:`, `data:`) are rejected unconditionally.
 */
fun sanitizeBillingUrl(raw: String?, allowLoopbackHttp: Boolean = false): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
    if (!parsed.userInfo.isNullOrEmpty()) return null

    val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
    if (isNonCanonicalOrZonedIpLiteral(host)) return null

    val scheme = parsed.protocol.lowercase()
    val allowedLocal = allowLoopbackHttp && isAllowedLocalDevHost(host)

    return when (scheme) {
        "https" -> if (!isLoopbackOrUnspecifiedHost(host) || allowedLocal) trimmed else null
        "http" -> if (allowedLocal) trimmed else null
        else -> null
    }
}

/**
 * Validates web portal and support links (`profile-web-page-url`, `support-url`,
 * `fallback_web_url`) with the same strict rules against unsafe schemes and SSRF hosts.
 */
fun sanitizePortalUrl(raw: String?, allowLoopbackHttp: Boolean = false): String? =
    sanitizeBillingUrl(raw, allowLoopbackHttp)

/** Human-readable host (and non-default port) shown to the user before opening checkout. */
fun billingDisplayHost(url: String): String {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return url
    val host = parsed.host.orEmpty().lowercase()
    return if (parsed.port != -1 && parsed.port != parsed.defaultPort) {
        "$host:${parsed.port}"
    } else {
        host
    }
}

private fun isAllowedLocalDevHost(host: String): Boolean {
    val clean = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (clean == "localhost" || clean.endsWith(".localhost") || clean == "10.0.2.2") return true
    if (!isNumericAddress(clean)) return false
    return runCatching { InetAddress.getByName(clean).isLoopbackAddress }.getOrDefault(false)
}
