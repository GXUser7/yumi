package com.mydrop.vpn.core.model

/**
 * Pure filtering rules shared by the server list UI and [com.mydrop.vpn.data.TunnelLauncher].
 *
 * Resolving saved filters against the current node list avoids locking out connections when a
 * previously selected protocol or subscription country disappears on subscription refresh.
 */
object ServerFilter {

    /** Protocols actually offered by [nodes], including members of auto-select groups. */
    fun availableProtocols(nodes: List<ProxyNode>): Set<Protocol> {
        if (nodes.isEmpty()) return emptySet()
        val result = HashSet<Protocol>()
        for (node in nodes) {
            result.add(node.protocol)
            val members = node.group?.members
            if (!members.isNullOrEmpty()) {
                for (i in members.indices) {
                    result.add(members[i].protocol)
                }
            }
        }
        return result
    }

    /**
     * Country codes offered by subscription servers in [nodes], matching the country facet picker.
     *
     * Manual servers carry no subscription and are excluded here for the same reason they are
     * absent from the country filter chips: they do not participate in country facet availability.
     */
    fun availableSubscriptionCountries(nodes: List<ProxyNode>): Set<String> {
        if (nodes.isEmpty()) return emptySet()
        val result = HashSet<String>()
        for (node in nodes) {
            if (node.subscriptionId == null) continue
            val code = WorldMap.countryCodeOf(node.name) ?: continue
            result.add(code)
        }
        return result
    }

    /** Intersects [selected] protocols with those currently present in [nodes]. */
    fun resolveProtocols(nodes: List<ProxyNode>, selected: Set<Protocol>): Set<Protocol> {
        if (selected.isEmpty() || nodes.isEmpty()) return emptySet()
        val available = availableProtocols(nodes)
        if (available.isEmpty()) return emptySet()
        return selected.filterTo(HashSet()) { it in available }
    }

    /** Intersects [selected] country codes with subscription countries currently present in [nodes]. */
    fun resolveCountries(nodes: List<ProxyNode>, selected: Set<String>): Set<String> {
        if (selected.isEmpty() || nodes.isEmpty()) return emptySet()
        val available = availableSubscriptionCountries(nodes)
        if (available.isEmpty()) return emptySet()
        return selected.filterTo(HashSet()) { it in available }
    }

    /** Whether [node] passes the resolved protocol and country filters under [countryMode]. */
    fun matches(
        node: ProxyNode,
        activeProtocols: Set<Protocol>,
        activeCountries: Set<String>,
        countryMode: CountryFilterMode,
    ): Boolean {
        if (activeProtocols.isNotEmpty()) {
            val protocolMatches = node.protocol in activeProtocols ||
                node.group?.members?.any { it.protocol in activeProtocols } == true
            if (!protocolMatches) return false
        }
        if (activeCountries.isNotEmpty()) {
            val code = WorldMap.countryCodeOf(node.name)
            when (countryMode) {
                CountryFilterMode.Include -> if (code == null || code !in activeCountries) return false
                CountryFilterMode.Exclude -> if (code != null && code in activeCountries) return false
            }
        }
        return true
    }

    /** Filters [nodes] according to the persisted protocol/country filters in [settings]. */
    fun filterNodes(nodes: List<ProxyNode>, settings: AppSettings): List<ProxyNode> {
        if (nodes.isEmpty()) return emptyList()
        val activeProtocols = resolveProtocols(nodes, settings.serverFilterProtocols)
        val activeCountries = resolveCountries(nodes, settings.serverFilterCountries)
        if (activeProtocols.isEmpty() && activeCountries.isEmpty()) return nodes
        return nodes.filter { matches(it, activeProtocols, activeCountries, settings.serverFilterCountryMode) }
    }
}
