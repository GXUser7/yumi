package com.mydrop.vpn.ui.screens.servers

import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.LatencyResult
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.data.ProfileState
import com.mydrop.vpn.ui.VisibleNodesCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersFilterAndGroupingTest {

    private fun node(
        id: String,
        name: String = "Node $id",
        server: String = "$id.example.com",
        subscriptionId: String? = "sub-1",
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        tls: TlsOptions? = null,
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = server,
        port = 443,
        settings = settings,
        tls = tls,
        subscriptionId = subscriptionId,
        group = group,
    )

    private fun sub(id: String, name: String = "Sub $id", enabled: Boolean = true) = Subscription(
        id = id,
        name = name,
        url = "https://example.com/$id",
        enabled = enabled,
    )

    @Test
    fun `protocol facets count direct protocols and NodeGroup member protocols once per node`() {
        val grouped = node(
            id = "g1",
            settings = ProxySettings.Vless(uuid = "u1"),
            group = NodeGroup(
                members = listOf(
                    node(id = "m1", settings = ProxySettings.Vless(uuid = "u1")),
                    node(id = "m2", settings = ProxySettings.Trojan(password = "p1")),
                    node(id = "m3", settings = ProxySettings.Trojan(password = "p2")),
                ),
            ),
        )
        val plainHysteria = node(
            id = "h1",
            settings = ProxySettings.Hysteria2(password = "p"),
        )
        val plainVless = node(
            id = "v1",
            settings = ProxySettings.Vless(uuid = "u2"),
        )

        val facets = extractProtocolFacets(listOf(grouped, plainHysteria, plainVless))
            .associate { it.protocol to it.count }

        assertEquals(
            mapOf(
                Protocol.VLESS to 2,
                Protocol.TROJAN to 1,
                Protocol.HYSTERIA2 to 1,
            ),
            facets,
        )
    }

    @Test
    fun `country facets include only subscription nodes, exclude manual and flagless, and sort deterministically`() {
        val nodes = listOf(
            node(id = "lv1", name = "🇱🇻 Latvia 1", subscriptionId = "sub-1"),
            node(id = "lv2", name = "🇱🇻 Latvia 2", subscriptionId = "sub-1"),
            node(id = "de1", name = "🇩🇪 Germany 1", subscriptionId = "sub-1"),
            node(id = "nl1", name = "🇳🇱 Netherlands 1", subscriptionId = "sub-2"),
            node(id = "jp-manual", name = "🇯🇵 Japan Manual", subscriptionId = null),
            node(id = "no-flag", name = "Fast Auto", subscriptionId = "sub-1"),
        )

        val facets = extractSubscriptionCountryFacets(nodes)

        assertEquals(listOf("LV", "DE", "NL"), facets.map { it.code })
        assertEquals(listOf(2, 1, 1), facets.map { it.count })
        assertEquals(listOf("🇱🇻", "🇩🇪", "🇳🇱"), facets.map { it.flag })
        assertFalse(facets.any { it.code == "JP" })
    }

    @Test
    fun `country facets scale cleanly across 175+ distinct subscription countries`() {
        val codes = buildList {
            for (first in 'A'..'Z') {
                for (second in 'A'..'Z') {
                    val code = "$first$second"
                    if (WorldMap.centroidOf(code) != null) add(code)
                }
            }
        }
        assertTrue(codes.size >= 175)

        val nodes = codes.mapIndexed { index, code ->
            val flag = WorldMap.flagOf(code).orEmpty()
            node(id = "n-$index", name = "$flag Server $code", subscriptionId = "sub-1")
        }

        val facets = extractSubscriptionCountryFacets(nodes)
        assertEquals(codes.size, facets.size)
        assertTrue(facets.all { it.count == 1 })
        assertEquals(codes.sorted(), facets.map { it.code })
    }

    @Test
    fun `countryGridColumns follows width and fontScale adaptation matrix`() {
        assertEquals(3, countryGridColumns(320.dp, 1.0f))
        assertEquals(3, countryGridColumns(360.dp, 1.0f))
        assertEquals(3, countryGridColumns(380.dp, 1.0f))
        assertEquals(3, countryGridColumns(419.dp, 1.0f))
        assertEquals(4, countryGridColumns(420.dp, 1.0f))
        assertEquals(4, countryGridColumns(448.dp, 1.0f))

        assertEquals(4, countryGridColumns(420.dp, 1.15f))
        assertEquals(3, countryGridColumns(420.dp, 1.16f))
        assertEquals(3, countryGridColumns(448.dp, 1.30f))
    }

    @Test
    fun `buildGroups applies protocol including NodeGroup, country, and case-insensitive query together`() {
        val s1 = node(
            id = "s1",
            name = "🇩🇪 Berlin Edge",
            server = "de1.vpn.example",
            subscriptionId = "sub-1",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val s2 = node(
            id = "s2",
            name = "🇩🇪 Frankfurt Auto",
            server = "de2.vpn.example",
            subscriptionId = "sub-1",
            settings = ProxySettings.Vless(uuid = "u2"),
            group = NodeGroup(
                members = listOf(
                    node(id = "m1", settings = ProxySettings.Trojan(password = "p")),
                ),
            ),
        )
        val s3 = node(
            id = "s3",
            name = "🇳🇱 Amsterdam",
            server = "nl1.vpn.example",
            subscriptionId = "sub-2",
            settings = ProxySettings.Trojan(password = "p2"),
        )
        val nodes = listOf(s1, s2, s3)
        val subs = listOf(sub("sub-1", "Alpha"), sub("sub-2", "Beta"))

        // Protocol TROJAN matches NodeGroup s2 (sub-1) and s3 (sub-2)
        val trojanGroups = buildGroups(
            nodes = nodes,
            subscriptions = subs,
            latencies = emptyMap(),
            query = "",
            sort = ServerSort.Default,
            selectedProtocols = setOf(Protocol.TROJAN),
            selectedCountries = emptySet(),
            manualGroupTitle = "Manual",
        )
        assertEquals(listOf("sub-1", "sub-2"), trojanGroups.map { it.id })
        assertEquals(listOf("s2"), trojanGroups[0].nodes.map { it.id })
        assertEquals(listOf("s3"), trojanGroups[1].nodes.map { it.id })

        // Combined protocol + country + case-insensitive query omits empty groups
        val combined = buildGroups(
            nodes = nodes,
            subscriptions = subs,
            latencies = emptyMap(),
            query = "FRANKFURT",
            sort = ServerSort.Default,
            selectedProtocols = setOf(Protocol.TROJAN),
            selectedCountries = setOf("DE"),
            manualGroupTitle = "Manual",
        )
        assertEquals(listOf("sub-1"), combined.map { it.id })
        assertEquals(listOf("s2"), combined.single().nodes.map { it.id })
    }

    @Test
    fun `buildGroups sorts by Default, Name, Protocol, Latency and collects orphaned subscription ids into manual`() {
        val n1 = node(
            id = "n1",
            name = "zeta",
            subscriptionId = "sub-1",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val n2 = node(
            id = "n2",
            name = "Alpha",
            subscriptionId = "sub-1",
            settings = ProxySettings.Hysteria2(password = "p1"),
        )
        val n3 = node(
            id = "n3",
            name = "Beta",
            subscriptionId = "sub-1",
            settings = ProxySettings.Trojan(password = "p2"),
        )
        val manual = node(id = "man", name = "Manual Node", subscriptionId = null)
        val orphan = node(id = "orp", name = "Orphan Node", subscriptionId = "deleted-sub")

        val latencies = mapOf(
            "n1" to LatencyResult(nodeId = "n1", millis = 90, measuredAtEpochMillis = 1L, failed = false),
            "n2" to LatencyResult(nodeId = "n2", millis = 0, measuredAtEpochMillis = 1L, failed = true),
            // n3 unmeasured (null) -> ahead of failed
        )
        val subs = listOf(sub("sub-1", "Main"))
        val allNodes = listOf(n1, n2, n3, manual, orphan)

        val byName = buildGroups(
            nodes = allNodes,
            subscriptions = subs,
            latencies = latencies,
            query = "",
            sort = ServerSort.Name,
            selectedProtocols = emptySet(),
            selectedCountries = emptySet(),
            manualGroupTitle = "Manual",
        )
        assertEquals(listOf("sub-1", "manual"), byName.map { it.id })
        assertEquals(listOf("n2", "n3", "n1"), byName[0].nodes.map { it.id })
        assertEquals(listOf("man", "orp"), byName[1].nodes.map { it.id })

        val byLatency = buildGroups(
            nodes = allNodes,
            subscriptions = subs,
            latencies = latencies,
            query = "",
            sort = ServerSort.Latency,
            selectedProtocols = emptySet(),
            selectedCountries = emptySet(),
            manualGroupTitle = "Manual",
        )
        assertEquals(listOf("n1", "n3", "n2"), byLatency[0].nodes.map { it.id })

        val byProtocol = buildGroups(
            nodes = allNodes,
            subscriptions = subs,
            latencies = latencies,
            query = "",
            sort = ServerSort.Protocol,
            selectedProtocols = emptySet(),
            selectedCountries = emptySet(),
            manualGroupTitle = "Manual",
        )
        assertEquals(listOf("n2", "n3", "n1"), byProtocol[0].nodes.map { it.id })
    }

    @Test
    fun `ProfileState visibleNodes and VisibleNodesCache preserve referential stability`() {
        val n1 = node(id = "1", subscriptionId = "s1")
        val n2 = node(id = "2", subscriptionId = "s2")
        val nodes = listOf(n1, n2)
        val subs = listOf(sub("s1", enabled = true), sub("s2", enabled = false))

        val state1 = ProfileState(nodes = nodes, subscriptions = subs, selectedNodeId = "1")
        assertSame(state1.visibleNodes, state1.visibleNodes)
        assertEquals(listOf("1"), state1.visibleNodes.map { it.id })

        val cache = VisibleNodesCache()
        val firstResolved = cache.resolve(state1)

        // Unrelated copy (selection or latency update) preserves exact list reference
        val state2 = state1.copy(
            selectedNodeId = "2",
            latencies = mapOf("1" to LatencyResult("1", 25, 100L)),
        )
        assertSame(firstResolved, cache.resolve(state2))

        // Enabling sub s2 invalidates cache
        val state3 = state2.copy(subscriptions = listOf(sub("s1", enabled = true), sub("s2", enabled = true)))
        val thirdResolved = cache.resolve(state3)
        assertNotSame(firstResolved, thirdResolved)
        assertEquals(listOf("1", "2"), thirdResolved.map { it.id })
    }

    @Test
    fun `ProxyNode badges caches instance and recomputes on copy`() {
        val initial = node(
            id = "b1",
            tls = TlsOptions(enabled = true, insecure = false),
        )
        val firstBadges = initial.badges
        assertSame(firstBadges, initial.badges)
        assertFalse("no-verify" in firstBadges)

        val updated = initial.copy(tls = TlsOptions(enabled = true, insecure = true))
        val updatedBadges = updated.badges
        assertNotSame(firstBadges, updatedBadges)
        assertTrue("no-verify" in updatedBadges)
    }
}
