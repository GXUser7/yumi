package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.DnsProfile
import com.mydrop.vpn.core.model.ProxyNode

sealed interface DeepLinkPayload {
    data class AddSubscription(val url: String, val name: String?) : DeepLinkPayload

    data class AddNodes(val nodes: List<ProxyNode>) : DeepLinkPayload

    /** Resolvers: `tls://`, `quic://`, a DoH URL, or an `sdns://` stamp. */
    data class AddDns(val profiles: List<DnsProfile>) : DeepLinkPayload

    data class Unsupported(
        val raw: String,
        val reason: UnsupportedReason,
        /** Only for [UnsupportedReason.UnsupportedAction]: the verb the link asked for. */
        val action: String? = null,
    ) : DeepLinkPayload
}

/**
 * Why a link could not be turned into anything.
 *
 * A code rather than a sentence, so this file stays free of user-facing text — and therefore of
 * any opinion about which language the reader has. The wording lives in resources and is picked
 * up where the payload is shown; see `DeepLinkPayload.describe` in the data layer.
 */
enum class UnsupportedReason {
    EmptyLink,
    BadDnsAddress,
    NoPayload,
    UnknownFormat,
    UnsupportedAction,
    NotRecognised,
}

/**
 * Handles what arrives from QR scans, shared text and `happ://` links.
 *
 * Happ-style links wrap the real target in base64url after an action segment
 * (`happ://add/<base64url(https://provider/sub/token)>`), but plenty of providers publish the
 * unencoded form too, so both are accepted.
 */
object DeepLinkParser {

    private val subscriptionActions = setOf("add", "install", "import", "sub", "subscription")

    fun parse(raw: String): DeepLinkPayload {
        val text = raw.trim()
        if (text.isEmpty()) return DeepLinkPayload.Unsupported(text, UnsupportedReason.EmptyLink)

        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()

        return when (scheme) {
            "happ", "mydrop" -> parseAppLink(text, scheme)
            // A DoH link and a subscription link are both https; only the resolver has the
            // well-known path, so asking the DNS parser first cannot swallow a subscription.
            "http", "https" -> DnsUriParser.parse(text)
                ?.let { DeepLinkPayload.AddDns(listOf(it)) }
                ?: DeepLinkPayload.AddSubscription(text, null)

            "sdns", "tls", "quic" -> DnsUriParser.parse(text)
                ?.let { DeepLinkPayload.AddDns(listOf(it)) }
                ?: DeepLinkPayload.Unsupported(text, UnsupportedReason.BadDnsAddress)

            else -> parseProxyPayload(text)
        }
    }

    private fun parseAppLink(text: String, scheme: String): DeepLinkPayload {
        val rest = text.removePrefix("$scheme://")
        val action = rest.substringBefore('/', missingDelimiterValue = "").lowercase()
        val payloadWithQuery = rest.substringAfter('/', missingDelimiterValue = "")
        if (payloadWithQuery.isEmpty()) {
            return DeepLinkPayload.Unsupported(text, UnsupportedReason.NoPayload)
        }

        val name = parseQuery(payloadWithQuery.substringAfter('?', ""))["name"]
            ?.takeIf(String::isNotEmpty)
        val payload = payloadWithQuery.substringBefore('?')
        val decoded = base64DecodeOrNull(payload)?.takeIf { it.contains("://") }
            ?: urlDecode(payload)

        return when {
            action in subscriptionActions && decoded.startsWith("http") ->
                DeepLinkPayload.AddSubscription(decoded, name)

            decoded.contains("://") -> parseProxyPayload(decoded, name)

            action.isEmpty() -> DeepLinkPayload.Unsupported(text, UnsupportedReason.UnknownFormat)

            // A verb we understand, carrying something we cannot read: the payload is what failed,
            // and saying "action «add» is not supported" sends the reader after the wrong thing.
            action in subscriptionActions ->
                DeepLinkPayload.Unsupported(text, UnsupportedReason.NotRecognised)

            else -> DeepLinkPayload.Unsupported(
                text,
                UnsupportedReason.UnsupportedAction,
                action = action,
            )
        }
    }

    private fun parseProxyPayload(text: String, name: String? = null): DeepLinkPayload {
        // A share blob may hold many links at once (QR codes of whole bundles do this).
        val nodes = ProxyUriParser.parseAll(text)
        if (nodes.isNotEmpty()) return DeepLinkPayload.AddNodes(nodes)

        // Servers first: a page of mixed text usually carries both, and a resolver is the thing
        // the user can still add by hand if this misses it.
        val resolvers = DnsUriParser.parseAll(text)
        if (resolvers.isNotEmpty()) return DeepLinkPayload.AddDns(resolvers)

        // Bare base64 with no scheme: a subscription body pasted directly.
        val decoded = base64DecodeOrNull(text)
        if (decoded != null) {
            val decodedNodes = ProxyUriParser.parseAll(decoded)
            if (decodedNodes.isNotEmpty()) return DeepLinkPayload.AddNodes(decodedNodes)
            if (decoded.startsWith("http")) {
                return DeepLinkPayload.AddSubscription(decoded.trim(), name)
            }
        }

        return DeepLinkPayload.Unsupported(text, UnsupportedReason.NotRecognised)
    }
}
