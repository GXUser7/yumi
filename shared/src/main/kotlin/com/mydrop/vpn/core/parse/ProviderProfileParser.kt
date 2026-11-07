package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProviderDns
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProviderRouting
import com.mydrop.vpn.core.model.ProviderRule
import com.mydrop.vpn.core.model.ProviderTarget
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.xray.XrayConfigFactory
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the routing and DNS out of the Xray documents a provider hands out; see [ProviderProfile].
 *
 * Separate from [ConfigDocumentParser] because it answers a separate question about the same
 * answer: that one reads which servers there are, this one reads what the provider wanted done with
 * traffic on each of them. The servers keep coming from wherever they came from before — the share
 * links, usually — and the profiles are attached to them by name afterwards, the same way
 * auto-select groups are.
 *
 * Conservative on purpose. A rule this side cannot carry exactly is left out and counted, never
 * loosened into something that fits, because the loosened version is a rule the provider did not
 * write and the user cannot see.
 */
object ProviderProfileParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Encoding used only to derive a profile's key; field order is the data class's own. */
    private val keyJson = Json { encodeDefaults = true }

    /**
     * Conditions that mean the same thing in this app's configuration as in the provider's.
     *
     * Every one of these looks at the connection itself — where it goes, what it is — and not at
     * how it entered the core. `inboundTag` and `user` are the opposite: they name the provider's
     * own SOCKS and HTTP inbounds, which do not exist in a tunnel built around a TUN interface. A
     * rule carrying either is left out whole. Translating `socks` to this app's TUN inbound would
     * turn Quattro's "everything from the local proxy goes to the balancer" into a catch-all placed
     * ahead of every rule the user chose.
     */
    internal val CARRIED_CONDITIONS =
        setOf("domain", "ip", "port", "sourcePort", "network", "protocol", "source", "sourceIP")

    /** Keys that are not conditions at all, and are rebuilt by the factory. */
    private val STRUCTURAL_KEYS = setOf("type", "ruleTag", "outboundTag", "balancerTag")

    /** What a resolver object may keep; the rest is the provider's tuning of features this side does not set. */
    private val CARRIED_DNS_KEYS = setOf("address", "port", "domains", "expectIPs", "skipFallback")

    /**
     * Resolvers that name the platform rather than a server, and answer nothing on Android: see
     * `XrayConfigFactory.DEFAULT_DIRECT_DNS` for why `localhost` is a dead end here, and `fakedns`
     * needs a pool this configuration never declares.
     */
    private val UNUSABLE_RESOLVERS = setOf("localhost", "fakedns")

    private val QUERY_STRATEGIES = setOf("UseIP", "UseIPv4", "UseIPv6")

    /**
     * Profiles by document name, the names trimmed; null when [body] is not Xray documents at all.
     *
     * A document whose profile turns out empty — no rule that could be carried, no usable resolver
     * — is absent from the map rather than present as an empty profile, so a server named after it
     * keeps this app's own routing instead of being told the provider chose none.
     */
    fun parse(body: String): Map<String, ProviderProfile>? {
        val root = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull() ?: return null
        val documents = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> return null
        }.filter { it["outbounds"] is JsonArray }
        if (documents.isEmpty()) return null

        val byName = LinkedHashMap<String, ProviderProfile>()
        for (document in documents) {
            val name = document.str("remarks")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // The first document of a name wins, as the first link of a name does in the list.
            if (name in byName) continue
            val profile = profileOf(document)
            if (!profile.isEmpty) byName[name] = profile
        }
        return byName
    }

    /** One document's profile; see [ProviderRule] for what is carried and what is left out. */
    fun profileOf(document: JsonObject): ProviderProfile {
        val roles = rolesOf(document)
        val routing = document["routing"] as? JsonObject
        val written = (routing?.get("rules") as? JsonArray).orEmpty()
        val rules = written.mapNotNull { element -> (element as? JsonObject)?.let { translate(it, roles) } }
        return ProviderProfile(
            rules = rules,
            dns = dnsOf(document),
            skippedRules = written.size - rules.size,
        )
    }

    /**
     * [byName] attached to the servers it describes, each distinct profile stored once.
     *
     * Matched on the trimmed name because that is the one thing a share link and a document for the
     * same server share: the panel renders both from the same template. Measured on Quattro, 531 of
     * 533 links found their document this way, and the two that did not were a placeholder whose
     * name differed by a trailing space — which trimming both sides recovers — and a server listed
     * as a link only.
     */
    fun routingFor(nodes: List<ProxyNode>, byName: Map<String, ProviderProfile>): ProviderRouting {
        val keyed = byName.mapValues { (_, profile) -> keyOf(profile) to profile }
        val byNode = nodes.mapNotNull { node ->
            keyed[node.name.trim()]?.let { (key, _) -> node.id to key }
        }.toMap()
        val used = byNode.values.toSet()
        val profiles = keyed.values.filter { (key, _) -> key in used }.toMap()
        return ProviderRouting(profiles = profiles, byNode = byNode)
    }

    /** Stable for equal content, so a refresh that changes nothing rewrites nothing. */
    internal fun keyOf(profile: ProviderProfile): String {
        val encoded = keyJson.encodeToString(ProviderProfile.serializer(), profile)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * What each tag in the document stands for. Null for a tag that exists but is not something
     * a rule here could be pointed at.
     */
    private fun rolesOf(document: JsonObject): Map<String, ProviderTarget?> {
        val roles = HashMap<String, ProviderTarget?>()
        (document["outbounds"] as? JsonArray).orEmpty().forEach { element ->
            val outbound = element as? JsonObject ?: return@forEach
            val tag = outbound.str("tag") ?: return@forEach
            roles[tag] = when (outbound.str("protocol")?.lowercase()) {
                // Only a plain one. A freedom outbound with settings of its own — fragmenting the
                // ClientHello, redirecting, adding noise — is a way of reaching a blocked site
                // directly, and sending its traffic out of an ordinary direct outbound would quietly
                // turn "reachable without the proxy" into "not reachable".
                "freedom" -> if (isPlainFreedom(outbound)) ProviderTarget.Direct else null
                "blackhole" -> ProviderTarget.Block
                // The DNS outbound answers queries; this app routes its own queries itself.
                "dns", "loopback" -> null
                null -> null
                else -> ProviderTarget.Proxy
            }
        }
        val routing = document["routing"] as? JsonObject
        (routing?.get("balancers") as? JsonArray).orEmpty().forEach { element ->
            (element as? JsonObject)?.str("tag")?.let { roles[it] = ProviderTarget.Proxy }
        }
        return roles
    }

    private fun isPlainFreedom(outbound: JsonObject): Boolean {
        val settings = outbound["settings"] as? JsonObject ?: return true
        return settings.keys.all { it == "domainStrategy" }
    }

    private fun translate(rule: JsonObject, roles: Map<String, ProviderTarget?>): ProviderRule? {
        val tag = rule.str("balancerTag") ?: rule.str("outboundTag") ?: return null
        val target = roles[tag] ?: return null
        if (rule.keys.any { it !in CARRIED_CONDITIONS && it !in STRUCTURAL_KEYS }) return null
        val match = JsonObject(rule.filterKeys { it in CARRIED_CONDITIONS })
        // Xray refuses a rule with no condition at all, so there is nothing to carry.
        if (match.isEmpty()) return null
        return ProviderRule(match = match, target = target)
    }

    private fun dnsOf(document: JsonObject): ProviderDns? {
        val dns = document["dns"] as? JsonObject ?: return null
        val servers = (dns["servers"] as? JsonArray).orEmpty().mapNotNull(::carriedServer)
        if (servers.isEmpty()) return null
        return ProviderDns(
            servers = servers,
            queryStrategy = dns.str("queryStrategy")?.takeIf { it in QUERY_STRATEGIES },
        )
    }

    private fun carriedServer(element: JsonElement): JsonElement? = when (element) {
        is JsonPrimitive -> element.contentOrNullSafe()?.let(::usableResolver)?.let { JsonPrimitive(it) }
        is JsonObject -> {
            val address = element.str("address")?.let(::usableResolver)
            if (address == null) {
                null
            } else {
                JsonObject(
                    element.filterKeys { it in CARRIED_DNS_KEYS } + ("address" to JsonPrimitive(address)),
                )
            }
        }
        else -> null
    }

    /** The resolver as this core reads it, or null when it cannot be asked from here. */
    private fun usableResolver(raw: String): String? {
        if (raw.trim().lowercase() in UNUSABLE_RESOLVERS) return null
        return XrayConfigFactory.xrayResolver(raw)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content.takeIf { it.isNotEmpty() && it != "null" } }.getOrNull()
}
