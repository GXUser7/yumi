package com.mydrop.vpn.core.parse

import java.net.URLDecoder
import java.util.Base64
import com.mydrop.vpn.core.net.splitHostPort

/**
 * Proxy share-links are only URI-shaped. Their userinfo routinely carries raw base64, colons and
 * unescaped bytes that make `java.net.URI` throw, so they are split by hand instead.
 */
internal data class ParsedUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String,
) {
    fun q(vararg names: String): String? =
        names.firstNotNullOfOrNull { query[it]?.takeIf(String::isNotEmpty) }

    fun qBool(vararg names: String): Boolean {
        val raw = q(*names) ?: return false
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    fun qInt(vararg names: String): Int? = q(*names)?.toIntOrNull()
}

internal fun splitUri(raw: String): ParsedUri? {
    val trimmed = raw.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    var rest = trimmed.substring(schemeEnd + 3)

    // Fragment first: the name may legitimately contain '?' once decoded.
    var fragment = ""
    val hashIndex = rest.indexOf('#')
    if (hashIndex >= 0) {
        fragment = urlDecode(rest.substring(hashIndex + 1))
        rest = rest.substring(0, hashIndex)
    }

    var query = emptyMap<String, String>()
    val queryIndex = rest.indexOf('?')
    if (queryIndex >= 0) {
        query = parseQuery(rest.substring(queryIndex + 1))
        rest = rest.substring(0, queryIndex)
    }

    // Trailing path segment ("/") carries no meaning for these schemes.
    rest = rest.trimEnd('/')

    var userInfo = ""
    val atIndex = rest.lastIndexOf('@')
    if (atIndex >= 0) {
        userInfo = rest.substring(0, atIndex)
        rest = rest.substring(atIndex + 1)
    }

    val (host, port) = splitHostAndPort(rest) ?: return null
    return ParsedUri(scheme, userInfo, host, port, query, fragment)
}

/**
 * Host and port for a link that needs both.
 *
 * A proxy share-link without a port cannot be dialled, so a missing one rejects the line rather
 * than inventing a default — and an unbracketed IPv6 literal has no port to find, which is
 * exactly the case the old hand-rolled split got wrong: it cut `2001:db8::1` on the last colon
 * and produced host `2001:db8:` with port 1, a node that looked fine in the list and could never
 * connect. The splitting itself now lives in [splitHostPort].
 */
internal fun splitHostAndPort(raw: String): Pair<String, Int>? {
    val parsed = splitHostPort(raw) ?: return null
    val port = parsed.port ?: return null
    return parsed.host to port
}

internal fun parseQuery(raw: String): Map<String, String> =
    raw.split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) urlDecode(pair).lowercase() to ""
            else urlDecode(pair.substring(0, eq)).lowercase() to urlDecode(pair.substring(eq + 1))
        }

internal fun urlDecode(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

/**
 * Tolerant base64: subscription bodies and vmess payloads show up standard or URL-safe, padded
 * or not, and often wrapped across lines. Returns null only when the bytes are genuinely not
 * base64 — callers use that to fall back to plain-text parsing.
 */
internal fun base64DecodeOrNull(value: String): String? {
    val cleaned = value.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
    if (cleaned.isEmpty()) return null
    val normalized = cleaned.replace('-', '+').replace('_', '/')
    val padded = when (normalized.length % 4) {
        2 -> "$normalized=="
        3 -> "$normalized="
        1 -> return null
        else -> normalized
    }
    return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
}

internal fun base64UrlEncode(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

internal fun splitCsv(value: String?): List<String> =
    value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
