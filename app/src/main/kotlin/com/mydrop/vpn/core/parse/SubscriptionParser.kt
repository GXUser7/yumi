package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.SubscriptionUserInfo

sealed interface SubscriptionBody {
    data class Nodes(val nodes: List<ProxyNode>) : SubscriptionBody

    /** Recognised but not yet supported; surfaced to the user instead of failing silently. */
    data class UnsupportedFormat(val format: String) : SubscriptionBody

    data class Empty(val reason: EmptyReason) : SubscriptionBody
}

/** Why a body yielded no servers. Kept as a code for the same reason as [UnsupportedReason]. */
enum class EmptyReason {
    EmptyResponse,
    LinksUnreadable,
    NotBase64,
    NoSupportedServers,
}

object SubscriptionParser {

    /**
     * Subscription bodies come in several shapes in the wild, and a panel picks between them by
     * reading the client's User-Agent — so the same link answers differently to different apps,
     * and "the format this provider uses" is not a stable property of the provider.
     *
     * Handled here: a base64 blob of share links, the same list in plain text, sing-box JSON,
     * Xray/v2ray JSON (single config or the array of them panels emit), SIP008, and Clash YAML.
     * Anything that parses to no servers at all is reported with the format it looked like,
     * because "empty list" and "I could not read this" are different problems for the user.
     */
    fun parse(body: String, subscriptionId: String): SubscriptionBody {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return SubscriptionBody.Empty(EmptyReason.EmptyResponse)

        structured(trimmed, subscriptionId)?.let { return it }

        // Plain-text list first: base64 decoding a list of URIs would garble it anyway, and
        // checking for a scheme is a cheap, unambiguous test.
        if (looksLikeLinkList(trimmed)) {
            val nodes = ProxyUriParser.parseAll(trimmed, subscriptionId)
            return if (nodes.isEmpty()) {
                SubscriptionBody.Empty(EmptyReason.LinksUnreadable)
            } else {
                SubscriptionBody.Nodes(nodes)
            }
        }

        val decoded = base64DecodeOrNull(trimmed)
            ?: return SubscriptionBody.Empty(EmptyReason.NotBase64)

        // A base64 body can hold a document just as easily as a list of links.
        structured(decoded, subscriptionId)?.let { return it }

        val nodes = ProxyUriParser.parseAll(decoded, subscriptionId)
        return if (nodes.isEmpty()) {
            SubscriptionBody.Empty(EmptyReason.NoSupportedServers)
        } else {
            SubscriptionBody.Nodes(nodes)
        }
    }

    /** Null when the text is not a configuration document at all. */
    private fun structured(text: String, subscriptionId: String): SubscriptionBody? {
        val format = detectStructuredFormat(text) ?: return null

        val nodes = when (format) {
            CLASH -> ClashParser.parse(text, subscriptionId)
            else -> ConfigDocumentParser.parse(text, subscriptionId)
        }

        return if (nodes.isEmpty()) {
            SubscriptionBody.UnsupportedFormat(format)
        } else {
            SubscriptionBody.Nodes(nodes)
        }
    }

    private fun looksLikeLinkList(text: String): Boolean =
        text.lineSequence().any { line ->
            val scheme = line.trim().substringBefore("://", missingDelimiterValue = "")
            scheme.isNotEmpty() && scheme.length < 12 && line.contains("://")
        }

    private const val CLASH = "Clash YAML"

    private fun detectStructuredFormat(text: String): String? = when {
        // The array form is what panels emit for Xray clients: one whole config per server.
        text.startsWith("[") -> "Xray JSON"
        text.startsWith("{") && text.contains("\"outbounds\"") -> "sing-box JSON"
        text.startsWith("{") && text.contains("\"server_port\"") -> "SIP008"
        text.startsWith("{") -> "JSON"
        text.contains("\nproxies:") || text.startsWith("proxies:") -> CLASH
        text.contains("proxy-groups:") -> CLASH
        else -> null
    }

    /**
     * Parses the `subscription-userinfo` response header, e.g.
     * `upload=455; download=2280; total=107374182400; expire=1735689600`.
     */
    fun parseUserInfo(header: String?): SubscriptionUserInfo? {
        if (header.isNullOrBlank()) return null
        val fields = header.split(';')
            .mapNotNull { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k.trim().lowercase() to v.trim()
            }
            .toMap()
        if (fields.isEmpty()) return null

        return SubscriptionUserInfo(
            uploadBytes = fields["upload"]?.toLongOrNull(),
            downloadBytes = fields["download"]?.toLongOrNull(),
            totalBytes = fields["total"]?.toLongOrNull(),
            // Providers send this as seconds; a few send milliseconds. Anything beyond year
            // 10000 in seconds is certainly milliseconds.
            expiresAtEpochSeconds = fields["expire"]?.toDoubleOrNull()?.toLong()?.let {
                if (it > 253_402_300_799L) it / 1000 else it
            },
        )
    }
}
