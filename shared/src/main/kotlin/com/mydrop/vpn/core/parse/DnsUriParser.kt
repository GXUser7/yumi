package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.DnsProfile
import java.util.Base64
import com.mydrop.vpn.core.net.splitHostPort
import com.mydrop.vpn.core.net.isNumericAddress

/**
 * Recognises the many ways a resolver is written down.
 *
 * Four of them appear in the wild: a DoH URL, a `tls://` or `quic://` address, a bare IP, and a
 * `sdns://` stamp — the base64 blob every DNSCrypt-era provider still publishes and no proxy
 * client on Android reads. Stamps are decoded here rather than refused, because for several
 * providers it is the only form they hand out.
 *
 * Two of those four are then refused, and it is worth saying why here rather than leaving it to be
 * discovered. The core has no DNS-over-TLS implementation at all — the scheme is simply not in the
 * list its resolver factory matches on (`app/dns/nameserver.go`) — and `quic://` exists only in a
 * local form that bypasses routing, which for a resolver meant to be reached *through* the tunnel
 * is the opposite of what was asked for.
 *
 * Refused rather than downgraded. Silently turning somebody's encrypted resolver into plaintext UDP
 * would hand their ISP the list of names they look up, which is the one thing they chose an
 * encrypted resolver to prevent — and it would do it without a word on screen.
 */
object DnsUriParser {

    /** Whether this core can actually query the resolver; see the note on the object. */
    private fun carriable(url: String): Boolean =
        !url.startsWith("tls://", ignoreCase = true) &&
            !url.startsWith("quic://", ignoreCase = true) &&
            !url.startsWith("h3://", ignoreCase = true)

    /** Null when the text is not a resolver at all — a proxy link, a subscription URL, prose. */
    fun parse(text: String, subscriptionId: String? = null): DnsProfile? {
        val trimmed = text.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null

        val name = trimmed.substringAfter('#', "").takeIf { it.isNotBlank() }?.let(::urlDecode)
        val body = trimmed.substringBefore('#')

        val url = when {
            // A stamp reaches the same place by another road, so the refusals below are applied
            // to what it decodes to rather than to the `sdns://` it arrived as.
            body.startsWith("sdns://", ignoreCase = true) ->
                fromStamp(body)?.takeIf(::carriable) ?: return null
            body.startsWith("https://", ignoreCase = true) -> body
            // See the note above: no DoT in this core, and DoQ only in a form that cannot be
            // routed. Both refused rather than quietly turned into something weaker.
            body.startsWith("tls://", ignoreCase = true) -> return null
            body.startsWith("quic://", ignoreCase = true) -> return null
            body.startsWith("h3://", ignoreCase = true) -> return null
            body.startsWith("tcp://", ignoreCase = true) -> body
            body.startsWith("udp://", ignoreCase = true) -> body.removePrefix("udp://")
            // A bare address is a plain resolver: `1.1.1.1`, `8.8.8.8:53`, `[2606:4700::1111]`.
            looksLikeBareAddress(body) -> body
            else -> return null
        }

        // A DoH link and a subscription link are both https, so the caller cannot tell them apart
        // by scheme; requiring the well-known path is what keeps a subscription from being
        // imported as a resolver.
        if (url.startsWith("https://") && !url.contains("/dns-query") && !url.contains("/dns")) {
            return null
        }

        return DnsProfile(
            id = DnsProfile.stableId(url, subscriptionId),
            name = name ?: url.substringAfter("://", missingDelimiterValue = url).substringBefore('/'),
            url = url,
            subscriptionId = subscriptionId,
        )
    }

    /** Every resolver in a blob of text, for pasting a provider's whole page. */
    fun parseAll(text: String, subscriptionId: String? = null): List<DnsProfile> = text
        .split('\n', ' ', '\t', ',')
        .mapNotNull { parse(it, subscriptionId) }
        .distinctBy { it.id }

    /**
     * Decodes a DNS stamp into a URL the core can be handed.
     *
     * Layout (dnscrypt.info/stamps-specifications): one protocol byte, eight bytes of properties,
     * then length-prefixed fields. Hash lists use the high bit of the length byte to mean "another
     * one follows", which is why they cannot be skipped by a fixed offset.
     *
     * DNSCrypt stamps (`0x01`) are refused rather than mangled: sing-box has no DNSCrypt
     * transport, and turning one into a plain resolver would quietly downgrade an encrypted
     * service to an unencrypted one.
     */
    private fun fromStamp(stamp: String): String? = runCatching {
        val payload = Base64.getUrlDecoder()
            .decode(stamp.removePrefix("sdns://").removePrefix("SDNS://").trimEnd('='))
        if (payload.size < 9) return null

        var offset = 9
        fun readField(): String {
            val length = payload[offset].toInt() and 0x7f
            val start = offset + 1
            offset = start + length
            return String(payload, start, length, Charsets.UTF_8)
        }

        fun skipHashes() {
            while (true) {
                val raw = payload[offset].toInt() and 0xff
                val length = raw and 0x7f
                offset += 1 + length
                if (raw and 0x80 == 0) break
            }
        }

        when (payload[0].toInt() and 0xff) {
            0x00 -> readField().ifEmpty { null }

            0x02 -> {
                val address = readField()
                skipHashes()
                val hostname = readField().ifEmpty { address }
                val path = readField().ifEmpty { "/dns-query" }
                "https://${hostname.substringBefore(':')}$path"
            }

            0x03 -> {
                val address = readField()
                skipHashes()
                "tls://${readField().ifEmpty { address }.substringBefore(':')}"
            }

            0x04 -> {
                val address = readField()
                skipHashes()
                "quic://${readField().ifEmpty { address }.substringBefore(':')}"
            }

            else -> null
        }
    }.getOrNull()

    /**
     * An address, not a domain: a hostname without a scheme is far more likely to be a typo or a
     * fragment of prose than a resolver someone meant to add.
     *
     * The check used to demand digits, dots and colons only, which quietly refused every IPv6
     * address containing a hex letter — `2620:fe::fe`, which is Quad9, simply did nothing when
     * pasted.
     */
    private fun looksLikeBareAddress(text: String): Boolean {
        if (text.contains("://") || text.contains(' ')) return false
        val parsed = splitHostPort(text) ?: return false
        return isNumericAddress(parsed.host)
    }

    /** Shared with the proxy parser, and for the same reason; see [com.mydrop.vpn.core.parse.urlDecode]. */
    private fun urlDecode(value: String): String = com.mydrop.vpn.core.parse.urlDecode(value)
}
