package com.mydrop.vpn.core.parse

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

    // The path, which several schemes carry and none of them put the host after.
    //
    // Only the trailing slash used to be trimmed, so `trojan://pass@host:443/path?sni=x` reached
    // the host/port split as `host:443/path` — where the port fails to parse and the whole line is
    // refused. A trojan-go link with a path is an ordinary thing for a provider to hand out, and
    // the server disappeared from the subscription without a word.
    val pathStart = rest.indexOf('/', startIndex = rest.lastIndexOf('@') + 1)
    if (pathStart >= 0) rest = rest.substring(0, pathStart)

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

/**
 * Percent-decoding, and deliberately *not* `URLDecoder`.
 *
 * `URLDecoder.decode` implements `application/x-www-form-urlencoded`, where `+` means a space. That
 * is the correct reading of an HTML form and the wrong reading of a proxy share-link: a WireGuard
 * key, a Shadowsocks-2022 key and a VMess password are all standard base64, where `+` is one of the
 * sixty-four characters and appears in roughly half of them. Every one of those keys arrived here
 * with its plus signs turned into spaces, which produces a server that is accepted, listed, and
 * cannot authenticate — with nothing on screen to say the key was altered on the way in.
 *
 * A literal `+` is therefore kept as a literal `+`. Anything that really meant a space is written
 * `%20`, which is decoded below.
 *
 * Malformed input is returned unchanged rather than thrown away: `%` is legal in a password, and a
 * stray one is not a reason to lose the server.
 */
internal fun urlDecode(value: String): String {
    if ('%' !in value) return value
    val out = StringBuilder(value.length)
    val bytes = java.io.ByteArrayOutputStream()

    fun flush() {
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
    }

    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '%' && index + 2 < value.length) {
            val hex = value.substring(index + 1, index + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                // Collected rather than decoded one at a time: a multi-byte UTF-8 character is
                // several escapes in a row, and turning each into its own string would produce
                // mojibake instead of the character.
                bytes.write(byte)
                index += 3
                continue
            }
        }
        flush()
        out.append(ch)
        index++
    }
    flush()
    return out.toString()
}

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

/**
 * A REALITY public key in the one spelling the core accepts.
 *
 * `publicKey` is decoded as raw URL-safe base64 without padding, so the standard alphabet a
 * provider may have used — `+` and `/`, with `=` on the end — is rejected, and rejected is the whole
 * document rather than the one node. Providers do emit both, and the same key written either way is
 * the same thirty-two bytes; converting is lossless.
 *
 * Left alone when it does not look like base64 at all: a key this cannot understand is better
 * handed to the core unaltered, so the core's own error names it, than quietly turned into
 * something else.
 */
internal fun normalizeRealityKey(value: String): String {
    val trimmed = value.trim().trimEnd('=')
    if (trimmed.isEmpty()) return trimmed
    if (!trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' }) {
        return trimmed
    }
    return trimmed.replace('+', '-').replace('/', '_')
}

internal fun base64UrlEncode(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

internal fun splitCsv(value: String?): List<String> =
    value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
