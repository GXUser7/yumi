package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The routing and DNS a provider wrote into one server's configuration document.
 *
 * Panels that hand out whole Xray documents — what Happ, Streisand and v2rayTun are given — put
 * more in them than the server: which sites go direct, what is blocked, which resolver to ask. This
 * app used to read only the outbounds and build everything else from its own settings, so a
 * provider's "Russian sites direct" list or its "torrents never through the proxy" rule simply did
 * not exist here. A Quattro subscription carries both, in every one of its five hundred documents.
 *
 * Stored already translated into roles rather than as the provider's own tags. The tags in a
 * document name *its* outbounds — `proxy`, `direct`, `block`, a balancer called `bal_price` — and
 * none of them exist in the configuration this app builds, where every server sits behind one
 * balancer. What survives the import is what a rule means, which is the only part that can be
 * carried over.
 */
@Serializable
data class ProviderProfile(
    /** In the provider's order, which is the order Xray tries them in. */
    val rules: List<ProviderRule> = emptyList(),
    /** Null when the document named no resolver this core can use. */
    val dns: ProviderDns? = null,
    /**
     * Rules in the document that could not be carried, and so were left out rather than guessed
     * at: a rule bound to the provider's own local inbounds, one aimed at an outbound that is not
     * a server, one matching on something this side does not translate. Counted for the journal.
     */
    val skippedRules: Int = 0,
) {
    val isEmpty: Boolean get() = rules.isEmpty() && dns == null
}

/**
 * One routing rule: the conditions exactly as the provider wrote them, and where matching traffic
 * goes.
 *
 * [match] holds only conditions known to mean the same thing in this app's configuration — see
 * `ProviderProfileParser.CARRIED_CONDITIONS`. A rule with any other condition is not here at all,
 * because dropping a condition from a rule widens it: "torrents from the SOCKS inbound go direct"
 * without its inbound becomes "every torrent goes direct", which is a rule nobody wrote.
 */
@Serializable
data class ProviderRule(
    val match: JsonObject,
    val target: ProviderTarget,
)

/** What a provider's rule does with the traffic it matches, independent of anybody's tag names. */
@Serializable
enum class ProviderTarget {
    /** Through the server the tunnel is on — whatever server that is by then. */
    Proxy,
    Direct,
    Block,
}

/**
 * The provider's resolvers.
 *
 * [servers] are `dns.servers` entries as written: a bare address or URL, or an object with an
 * `address`. Kept as JSON because the object form carries per-name routing (`domains`) that is the
 * provider's to decide and has no equivalent in this app's settings.
 */
@Serializable
data class ProviderDns(
    val servers: List<JsonElement>,
    /** `UseIP`, `UseIPv4` or `UseIPv6` as the provider set it; the tunnel may narrow it, see the factory. */
    val queryStrategy: String? = null,
)

/**
 * Every distinct provider profile in a subscription, and which server carries which.
 *
 * Deduplicated because the documents repeat themselves: Quattro's five hundred and thirty-two
 * carry eight distinct rule sets, the largest of them five hundred domains long. Stored once per
 * server that would be a two-megabyte settings file rewritten on every refresh.
 */
@Serializable
data class ProviderRouting(
    /** Each distinct profile once, under a key derived from its content. */
    val profiles: Map<String, ProviderProfile> = emptyMap(),
    /** Server id to the key of the profile its document carried. */
    val byNode: Map<String, String> = emptyMap(),
) {
    fun profileFor(nodeId: String): ProviderProfile? = byNode[nodeId]?.let(profiles::get)

    /** Only the servers that still exist, and only the profiles one of them still uses. */
    fun keepingOnly(nodeIds: Set<String>): ProviderRouting {
        val kept = byNode.filterKeys { it in nodeIds }
        val used = kept.values.toSet()
        return ProviderRouting(profiles = profiles.filterKeys { it in used }, byNode = kept)
    }
}
