package com.mydrop.vpn.ui.screens.remote

import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.remote.RemoteNode
import com.mydrop.vpn.remote.RemoteSubscription
import com.mydrop.vpn.ui.screens.servers.ServerFilterState
import com.mydrop.vpn.ui.screens.servers.ServerSort
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteServersFilterTest {

    private fun node(
        id: String,
        name: String,
        protocol: Protocol = Protocol.VLESS,
        group: String = "Quattro VPN",
        subscriptionId: String? = "sub-1",
        latencyMillis: Int? = null,
        latencyFailed: Boolean = false,
        memberProtocols: List<Protocol> = emptyList(),
    ) = RemoteNode(
        id = id,
        name = name,
        group = group,
        latencyMillis = latencyMillis,
        latencyFailed = latencyFailed,
        protocol = protocol,
        subscriptionId = subscriptionId,
        memberProtocols = memberProtocols,
    )

    @Test
    fun `extractRemoteProtocolFacets deduplicates member protocols per node`() {
        val grouped = node(
            id = "auto-1",
            name = "🇩🇪 Auto",
            protocol = Protocol.VLESS,
            memberProtocols = listOf(Protocol.VLESS, Protocol.TROJAN),
        )
        val hy2 = node(id = "hy-1", name = "🇳🇱 Fast", protocol = Protocol.HYSTERIA2)
        val vless = node(id = "vl-1", name = "🇫🇮 Plain", protocol = Protocol.VLESS)

        val map = extractRemoteProtocolFacets(listOf(grouped, hy2, vless))
            .associate { it.protocol to it.count }

        assertEquals(
            mapOf(
                Protocol.VLESS to 2,
                Protocol.TROJAN to 1,
                Protocol.HYSTERIA2 to 1,
            ),
            map,
        )
    }

    @Test
    fun `extractRemoteCountryFacets extracts flags and sorts by count then code`() {
        val nodes = listOf(
            node("1", "🇩🇪 Berlin"),
            node("2", "🇩🇪 Frankfurt"),
            node("3", "🇳🇱 Amsterdam"),
            node("4", "🇱🇻 Riga"),
            node("5", "No flag auto"),
        )

        val facets = extractRemoteCountryFacets(nodes)
        assertEquals(listOf("DE", "LV", "NL"), facets.map { it.code })
        assertEquals(listOf(2, 1, 1), facets.map { it.count })
    }

    @Test
    fun `buildRemoteServerGroups filters by protocol, country, query and sorts by latency`() {
        val subs = listOf(
            RemoteSubscription(id = "sub-1", name = "Quattro"),
            RemoteSubscription(id = "sub-2", name = "Backup"),
        )
        val nodes = listOf(
            node("de-slow", "🇩🇪 Munich", Protocol.VLESS, subscriptionId = "sub-1", latencyMillis = 180),
            node("de-fast", "🇩🇪 Berlin", Protocol.HYSTERIA2, subscriptionId = "sub-1", latencyMillis = 42),
            node("de-dead", "🇩🇪 Frankfurt", Protocol.HYSTERIA2, subscriptionId = "sub-1", latencyFailed = true),
            node("nl-fast", "🇳🇱 Amsterdam", Protocol.HYSTERIA2, subscriptionId = "sub-1", latencyMillis = 30),
            node("de-manual", "🇩🇪 Custom", Protocol.HYSTERIA2, group = "", subscriptionId = null, latencyMillis = 60),
        )

        val queryFilter = ServerFilterState(
            query = "ber",
            sort = ServerSort.Latency,
            selectedProtocols = setOf(Protocol.HYSTERIA2),
            selectedCountries = setOf("DE"),
        )
        val queryGroups = buildRemoteServerGroups(nodes, subs, queryFilter, "Manual")
        assertEquals(listOf("sub-1"), queryGroups.map { it.id })
        assertEquals(listOf("de-fast"), queryGroups.single().nodes.map { it.id })

        val countryProtocolOnly = queryFilter.copy(query = "")
        val groups = buildRemoteServerGroups(nodes, subs, countryProtocolOnly, "Manual")
        assertEquals(listOf("sub-1", "manual"), groups.map { it.id })
        assertEquals(listOf("de-fast", "de-dead"), groups.first().nodes.map { it.id })

        val collapsedIds = filteredRemoteNodeIdsForPing(
            nodes = nodes,
            subscriptions = subs,
            filter = countryProtocolOnly,
            collapsedGroupIds = setOf("sub-1"),
        )
        assertEquals(listOf("de-manual"), collapsedIds)
    }
}
