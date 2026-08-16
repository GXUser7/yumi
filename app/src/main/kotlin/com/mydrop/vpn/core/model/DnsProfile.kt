package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A resolver the tunnel can be pointed at.
 *
 * DNS used to be two text fields in settings, which assumes the user has one resolver and knows
 * its URL by heart. In practice resolvers arrive the same way servers do — as a link from a
 * provider, a QR code, an entry inside a subscription — and services exist whose entire product is
 * a resolver rather than a proxy. So they are stored, named and selected like servers.
 *
 * [url] is kept in the form the configuration builder already understands: `https://host/path`,
 * `tls://host`, `quic://host`, or a bare address for plain UDP.
 */
@Serializable
data class DnsProfile(
    val id: String,
    val name: String,
    val url: String,
    val subscriptionId: String? = null,
) {
    /** Short badge for the list: the transport, which is the thing that differs between them. */
    val kind: String
        get() = when (url.substringBefore("://", missingDelimiterValue = "").lowercase()) {
            "https", "h3" -> "DoH"
            "tls" -> "DoT"
            "quic" -> "DoQ"
            "tcp" -> "TCP"
            else -> "UDP"
        }

    val host: String
        get() = url.substringAfter("://", missingDelimiterValue = url).substringBefore('/')

    companion object {
        /** Same identity rule as servers: the address decides, so a rename is not a new profile. */
        fun stableId(url: String, subscriptionId: String? = null): String {
            val seed = "${subscriptionId ?: "manual"}|${url.trim().lowercase()}"
            return MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
