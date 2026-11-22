package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.TransportOptions
import com.mydrop.vpn.core.net.extractAuthorityHost
import com.mydrop.vpn.core.net.isForbiddenDnsHost
import com.mydrop.vpn.core.net.isNumericAddress
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Resolves VPN server domains via RFC 8484 DNS-over-HTTPS before building Xray configs so
 * operator DNS poisoning/blocking of the server hostname cannot misdirect the tunnel dial.
 */
object ServerAddressResolver {

    const val DEFAULT_DOH = "https://1.1.1.1/dns-query"
    private const val TIMEOUT_MILLIS = 3_000
    private const val MIN_TTL_SECONDS = 60
    private const val MAX_TTL_SECONDS = 3_600

    private data class CacheKey(val dohUrl: String, val host: String)
    private data class Entry(val address: String, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<CacheKey, Entry>()

    /**
     * Validates a DoH endpoint URL: must be `https://`, carry a valid non-blank host, and must not
     * target loopback, unspecified, `localhost`, or `fakedns` hosts.
     */
    fun isValidDohUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) return false
        val host = extractAuthorityHost(trimmed) ?: return false
        return !isForbiddenDnsHost(host)
    }

    /**
     * Returns a copy of [node] with its domain `server` replaced by the DoH-resolved IPv4 address
     * while preserving the original hostname in `tls.serverName` and transport Host/authority fields.
     * Group members are resolved recursively, keeping every node `id` untouched.
     */
    fun resolveNode(
        node: ProxyNode,
        dohUrl: String,
        lookup: (host: String, dohUrl: String) -> String? = ::resolve,
    ): ProxyNode {
        val resolvedGroup = node.group?.let { group ->
            group.copy(members = group.members.map { resolveNode(it, dohUrl, lookup) })
        }
        val baseNode = if (resolvedGroup != null) node.copy(group = resolvedGroup) else node
        if (baseNode.settings is ProxySettings.Direct) return baseNode

        val host = baseNode.server.trim()
        if (host.isEmpty() || isNumericAddress(host)) return baseNode

        val address = lookup(host, dohUrl) ?: return baseNode
        return baseNode.copy(
            server = address,
            tls = baseNode.tls?.let { tls ->
                if (tls.enabled && tls.serverName.isNullOrBlank()) tls.copy(serverName = host) else tls
            },
            transport = baseNode.transport?.withHost(host),
        )
    }

    /** Returns an IPv4 address for [host] from the TTL cache or via wire-format DoH; null on failure. */
    fun resolve(host: String, dohUrl: String): String? {
        val normalizedHost = host.trim().trimEnd('.').lowercase()
        if (normalizedHost.isEmpty()) return null

        val effectiveDoh = dohUrl.trim().takeIf(::isValidDohUrl) ?: DEFAULT_DOH
        val key = CacheKey(effectiveDoh, normalizedHost)
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMillis > now }?.let { return it.address }

        val onMainThread = runCatching {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        }.getOrDefault(false)
        if (onMainThread) return null

        val (address, ttl) = runCatching { query(normalizedHost, effectiveDoh) }.getOrNull() ?: return null
        val ttlMillis = ttl.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1_000L
        cache[key] = Entry(address, now + ttlMillis)
        return address
    }

    internal fun clearCache() {
        cache.clear()
    }

    private fun query(host: String, dohUrl: String): Pair<String, Int>? {
        val id = Random.nextInt(0, 0xFFFF)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(buildQuestion(id, host))
        val separator = if ('?' in dohUrl) '&' else '?'
        val url = URL("$dohUrl${separator}dns=$encoded")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/dns-message")
        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { it.readBytes() }
            parseFirstAddress(body, id)
        } finally {
            connection.disconnect()
        }
    }

    /** Builds a recursive RFC 1035/8484 A-record query packet for [host]. */
    internal fun buildQuestion(id: Int, host: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // RD = 1
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        repeat(6) { out.write(0x00) } // ANCOUNT, NSCOUNT, ARCOUNT = 0
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "invalid DNS label in $host" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QTYPE = A
        out.write(0x00)
        out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    /** Extracts the first non-loopback, non-unspecified IPv4 A record matching [id]. */
    internal fun parseFirstAddress(message: ByteArray, id: Int): Pair<String, Int>? {
        if (message.size < 12 || u16(message, 0) != id) return null
        if (message[3].toInt() and 0x0F != 0) return null
        val questions = u16(message, 4)
        val answers = u16(message, 6)
        var at = 12
        repeat(questions) {
            at = skipName(message, at) ?: return null
            if (at + 4 > message.size) return null
            at += 4
        }
        repeat(answers) {
            at = skipName(message, at) ?: return null
            if (at + 10 > message.size) return null
            val type = u16(message, at)
            val ttl = (u16(message, at + 4) shl 16) or u16(message, at + 6)
            val rdLength = u16(message, at + 8)
            at += 10
            if (at + rdLength > message.size) return null
            if (type == 1 && rdLength == 4) {
                val b0 = message[at].toInt() and 0xFF
                val b1 = message[at + 1].toInt() and 0xFF
                val b2 = message[at + 2].toInt() and 0xFF
                val b3 = message[at + 3].toInt() and 0xFF
                val isLoopback = b0 == 127
                val isUnspecified = b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0
                if (!isLoopback && !isUnspecified) {
                    return "$b0.$b1.$b2.$b3" to ttl
                }
            }
            at += rdLength
        }
        return null
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var at = start
        var steps = 0
        while (at < message.size && steps++ < 128) {
            val length = message[at].toInt() and 0xFF
            if (length == 0) return at + 1
            if (length and 0xC0 == 0xC0) {
                return if (at + 2 <= message.size) at + 2 else null
            }
            if (length > 63) return null
            at += length + 1
        }
        return null
    }

    private fun u16(message: ByteArray, at: Int): Int =
        ((message[at].toInt() and 0xFF) shl 8) or (message[at + 1].toInt() and 0xFF)

    private fun TransportOptions.withHost(host: String): TransportOptions = when (this) {
        is TransportOptions.WebSocket ->
            if (headers.keys.none { it.equals("Host", ignoreCase = true) }) {
                copy(headers = headers + ("Host" to host))
            } else {
                this
            }
        is TransportOptions.HttpUpgrade -> if (this.host.isBlank()) copy(host = host) else this
        is TransportOptions.Xhttp -> if (this.host.isBlank()) copy(host = host) else this
        is TransportOptions.Http -> if (this.host.isEmpty()) copy(host = listOf(host)) else this
        is TransportOptions.Grpc -> if (this.authority.isBlank()) copy(authority = host) else this
        else -> this
    }
}
