package com.mydrop.vpn.core.model

import com.mydrop.vpn.core.net.isNumericAddress
import com.mydrop.vpn.core.net.splitHostPort
import kotlinx.serialization.Serializable

/**
 * User-defined routing exceptions applied above the global routing mode and provider rules:
 * three lists matching Xray routing targets (`Via VPN`, `Direct`, `Block`).
 */
@Serializable
data class CustomRules(
    val proxy: List<String> = emptyList(),
    val direct: List<String> = emptyList(),
    val block: List<String> = emptyList(),
) {
    enum class Action { Proxy, Direct, Block }

    fun of(action: Action): List<String> = when (action) {
        Action.Proxy -> proxy
        Action.Direct -> direct
        Action.Block -> block
    }

    /** Adds [entry] to [action] while removing it from all three lists first to prevent duplicates or conflicts. */
    fun adding(action: Action, entry: String): CustomRules {
        val cleaned = copy(
            proxy = proxy - entry,
            direct = direct - entry,
            block = block - entry,
        )
        return when (action) {
            Action.Proxy -> cleaned.copy(proxy = cleaned.proxy + entry)
            Action.Direct -> cleaned.copy(direct = cleaned.direct + entry)
            Action.Block -> cleaned.copy(block = cleaned.block + entry)
        }
    }

    fun removing(action: Action, entry: String): CustomRules = when (action) {
        Action.Proxy -> copy(proxy = proxy - entry)
        Action.Direct -> copy(direct = direct - entry)
        Action.Block -> copy(block = block - entry)
    }

    val isEmpty: Boolean get() = proxy.isEmpty() && direct.isEmpty() && block.isEmpty()

    val size: Int get() = proxy.size + direct.size + block.size

    companion object {
        /**
         * Normalizes user input (URL, domain, `host:port`, IPv4/IPv6, CIDR, or Xray matcher prefix)
         * into a valid Xray rule entry, or `null` if invalid.
         */
        fun normalize(raw: String): String? {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty() || value.any { it.isWhitespace() }) return null

            val colonIndex = value.indexOf(':')
            if (colonIndex > 0) {
                val prefix = value.substring(0, colonIndex).lowercase()
                if (prefix in KEPT_PREFIXES) {
                    val rest = value.substring(colonIndex + 1).trim()
                    return if (rest.isEmpty()) null else "$prefix:$rest"
                }
            }

            // Parse IPv4/IPv6 CIDR before stripping URL paths.
            val slash = value.indexOf('/')
            if (slash > 0 && "://" !in value) {
                val address = value.substring(0, slash).trim('[', ']')
                val bits = value.substring(slash + 1).toIntOrNull()
                if (isNumericAddress(address)) {
                    val widest = if (':' in address) 128 else 32
                    return if (bits != null && bits in 0..widest) "$address/$bits" else null
                }
            }

            if ("://" in value) value = value.substringAfter("://")
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            value = value.substringAfterLast('@')
            if (value.isEmpty()) return null

            val host = splitHostPort(value)?.host ?: return null
            if (isNumericAddress(host)) return host

            val normalizedHost = host.lowercase().trimEnd('.')
            if (normalizedHost.isEmpty() || normalizedHost.all { it in '0'..'9' }) return null
            return if (HOST.matches(normalizedHost)) "domain:$normalizedHost" else null
        }

        /** True when [entry] matches IP addresses rather than domain names in Xray routing. */
        fun isAddress(entry: String): Boolean {
            val value = entry.trim().lowercase()
            if (value.startsWith("geoip:")) return true
            val bare = value.substringBefore('/').trim('[', ']')
            return isNumericAddress(bare)
        }

        private val KEPT_PREFIXES = setOf("domain", "full", "keyword", "regexp", "geosite", "geoip")
        private val HOST =
            Regex("""^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")
    }
}
