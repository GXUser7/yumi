package com.mydrop.vpn.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverGroupTest {

    private fun node(
        id: String,
        server: String = "$id.example.com",
        port: Int = 443,
        subscriptionId: String? = "sub-a",
        settings: ProxySettings = ProxySettings.Trojan(password = "p"),
        name: String = id,
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = server,
        port = port,
        settings = settings,
        subscriptionId = subscriptionId,
        group = group,
    )

    private fun latency(id: String, millis: Int, failed: Boolean = false) =
        id to LatencyResult(id, millis, measuredAtEpochMillis = 0L, failed = failed)

    @Test
    fun `orders by measured latency`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = nodes[0],
            latencies = mapOf(latency("b", 200), latency("c", 40)),
            limit = 8,
        )

        assertEquals(listOf("c", "b"), group.map { it.id })
    }

    @Test
    fun `an explicit choice replaces the subscription rule`() {
        val mine = node("a")
        val nodes = listOf(mine, node("same"), node("other", subscriptionId = "sub-b"))

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = mine,
            latencies = emptyMap(),
            limit = 8,
            chosen = setOf("other"),
        )

        assertEquals(listOf("other"), group.map { it.id })
    }

    @Test
    fun `an explicit choice still cannot smuggle in a direct outbound`() {
        val mine = node("a")
        val nodes = listOf(mine, node("bypass", settings = ProxySettings.Direct))

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = mine,
            latencies = emptyMap(),
            limit = 8,
            chosen = setOf("bypass"),
        )

        assertTrue(group.isEmpty())
    }

    @Test
    fun `never measured beats last known failure`() {
        val nodes = listOf(node("a"), node("dead"), node("unknown"))
        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = nodes[0],
            latencies = mapOf(latency("dead", 0, failed = true)),
            limit = 8,
        )

        assertEquals(listOf("unknown", "dead"), group.map { it.id })
    }

    @Test
    fun `stays inside the selected server's subscription`() {
        val mine = node("a")
        val nodes = listOf(mine, node("same"), node("other", subscriptionId = "sub-b"))

        val group = FailoverGroup.candidates(nodes, mine, emptyMap(), limit = 8)

        assertEquals(listOf("same"), group.map { it.id })
    }

    @Test
    fun `manually added servers form their own group`() {
        val manual = node("a", subscriptionId = null)
        val nodes = listOf(manual, node("b", subscriptionId = null), node("fromSub"))

        val group = FailoverGroup.candidates(nodes, manual, emptyMap(), limit = 8)

        assertEquals(listOf("b"), group.map { it.id })
    }

    @Test
    fun `same endpoint is not probed twice`() {
        val selected = node("a", server = "edge.example.com")
        val nodes = listOf(
            selected,
            node("duplicate", server = "edge.example.com"),
            node("distinct", server = "other.example.com"),
        )

        val group = FailoverGroup.candidates(nodes, selected, emptyMap(), limit = 8)

        assertEquals(listOf("distinct"), group.map { it.id })
    }

    @Test
    fun `a direct outbound is never a fallback`() {
        val selected = node("a")
        val nodes = listOf(selected, node("bypass", settings = ProxySettings.Direct))

        val group = FailoverGroup.candidates(nodes, selected, emptyMap(), limit = 8)

        assertTrue(group.isEmpty())
    }

    /**
     * A server nominated for cellular is not an ordinary spare. Both callers that build an
     * ordinary pool pass this — FailoverWatchdog.swapAwayFrom off cellular and its ordinaryPoolFor
     * — because landing on one at home undoes the list from the other direction.
     */
    @Test
    fun `a mobile server is not an ordinary spare`() {
        val selected = node("a")
        val nodes = listOf(selected, node("m1"), node("b"))

        val group = FailoverGroup.candidates(
            nodes,
            selected,
            emptyMap(),
            limit = 8,
            exclude = setOf("m1"),
        )

        assertEquals(listOf("b"), group.map { it.id })
    }

    /**
     * Excluded before the cut, not after it.
     *
     * Here the two fastest servers are the mobile ones, so a limit of three filled with them and
     * filtered afterwards hands back nothing — which is how coming back to Wi-Fi once found no
     * ordinary server to come back to.
     */
    @Test
    fun `the excluded do not eat the slots on their way out`() {
        val selected = node("a")
        val nodes = listOf(selected, node("m1"), node("m2"), node("slow"))
        val latencies = mapOf(latency("m1", 10), latency("m2", 20), latency("slow", 900))

        val group = FailoverGroup.candidates(
            nodes,
            selected,
            latencies,
            limit = 3,
            exclude = setOf("m1", "m2"),
        )

        assertEquals(listOf("slow"), group.map { it.id })
    }

    /**
     * The list the core was given wins over the list derived a second time.
     *
     * Both come from [FailoverGroup.candidates], which orders by last measured latency before
     * truncating — and those measurements move while the tunnel runs. A journal caught the result:
     * `selectOutbound refused: outbound not found in selector`, and a full restart of the core
     * where a pointer swap was intended.
     */
    @Test
    fun `a candidate the core was never given is passed over`() {
        val inside = node("in")
        val outside = node("out")

        val kept = FailoverGroup.preferSwitchable(listOf(outside, inside), setOf("in"))

        assertEquals(listOf("in"), kept.map { it.id })
    }

    /** Nothing known about the group is not a reason to start refusing candidates. */
    @Test
    fun `an unknown group leaves the list alone`() {
        val nodes = listOf(node("a"), node("b"))

        assertEquals(nodes, FailoverGroup.preferSwitchable(nodes, emptySet()))
    }

    /**
     * A reconnect is worse than a pointer swap and better than staying on a dead server, so when
     * the filter would empty the list it does not apply.
     */
    @Test
    fun `a list with nothing inside the group survives whole`() {
        val nodes = listOf(node("a"), node("b"))

        val kept = FailoverGroup.preferSwitchable(nodes, setOf("somebody-else"))

        assertEquals(nodes, kept)
    }

    /** Order is the caller's business — the lot was already drawn by [sample] before this runs. */
    @Test
    fun `filtering keeps the order it was handed`() {
        val nodes = listOf(node("c"), node("a"), node("b"))

        val kept = FailoverGroup.preferSwitchable(nodes, setOf("a", "b", "c"))

        assertEquals(listOf("c", "a", "b"), kept.map { it.id })
    }

    @Test
    fun `limit counts the selected server`() {
        val nodes = (1..10).map { node("n$it") }

        val group = FailoverGroup.candidates(nodes, nodes[0], emptyMap(), limit = 4)

        assertEquals(3, group.size)
    }

    @Test
    fun `a limit of one leaves no room for companions`() {
        val nodes = listOf(node("a"), node("b"))

        assertTrue(FailoverGroup.candidates(nodes, nodes[0], emptyMap(), limit = 1).isEmpty())
    }

    @Test
    fun `a lot is drawn from the whole list, not its head`() {
        // Twenty nominated servers, seven drawn, many times over: if the draw were the ordering
        // in disguise, the same seven would come back every time. The bug this replaces did
        // exactly that — a phone retried one corner of the list all evening while the rest of it
        // sat untouched.
        val pool = (1..20).map { node("n$it", "h$it.example.com") }
        val seen = mutableSetOf<String>()
        repeat(30) { seed ->
            val drawn = FailoverGroup.sample(pool, random = Random(seed.toLong()))
            assertEquals(7, drawn.size)
            drawn.forEach { seen += it.id }
        }
        assertEquals(pool.size, seen.size)
    }

    @Test
    fun `the same seed draws the same lot`() {
        val pool = (1..20).map { node("n$it", "h$it.example.com") }
        assertEquals(
            FailoverGroup.sample(pool, random = Random(7)).map { it.id },
            FailoverGroup.sample(pool, random = Random(7)).map { it.id },
        )
    }

    @Test
    fun `a short list is taken whole rather than thinned`() {
        val pool = (1..4).map { node("n$it", "h$it.example.com") }
        assertEquals(4, FailoverGroup.sample(pool, random = Random(1)).size)
    }

    @Test
    fun `the group always leaves room for the servers alongside it`() {
        // Whatever the mobile list costs, the parts have to fit in one selector group: a draw from
        // a longer list than the core holds is a switch that cannot happen.
        for (mobile in 0..60) {
            val spares = (FailoverGroup.roomFor(mobile) - 1).coerceAtLeast(0)
            assertTrue(
                "selected + mobile + spares must fit in one selector group",
                1 + FailoverGroup.mobileSlots(mobile) + spares <= FailoverGroup.SWITCHABLE + 1,
            )
        }
        // This used to assert the opposite — that a mobile list filling the group on its own left
        // no spares at all, which read as honesty about a group that was full. On a phone with
        // thirty-four mobile servers it meant the core was handed no ordinary server at any point,
        // and «нечем заменить» on a subscription of a hundred and forty. The list gives way now.
        assertTrue(
            FailoverGroup.candidates(
                nodes = (1..5).map { node("n$it") },
                selected = node("cur"),
                latencies = emptyMap(),
                limit = FailoverGroup.roomFor(FailoverGroup.SWITCHABLE),
            ).isNotEmpty(),
        )
    }


    /**
     * A phone with thirty-four servers on the mobile list and twenty-four slots. `roomFor` went
     * negative, [FailoverGroup.candidates] answered every request with nothing, and the group the
     * core received held no ordinary server at all — so on Wi-Fi there was nothing to come back to
     * and nothing to replace a dead server with.
     */
    @Test
    fun `a long mobile list cannot starve the ordinary side`() {
        val room = FailoverGroup.roomFor(34)
        assertTrue("thirty-four mobile servers left room for $room", room >= FailoverGroup.MIN_ORDINARY)
        assertTrue(
            "the two parts must still fit the group",
            FailoverGroup.mobileSlots(34) + room <= FailoverGroup.SWITCHABLE,
        )
    }

    /** However long the list, the ordinary side keeps enough for a draw. */
    @Test
    fun `the ordinary reserve survives any mobile list`() {
        for (count in intArrayOf(0, 1, 12, 24, 34, 60, 500)) {
            assertTrue(
                "mobile=$count left ${FailoverGroup.roomFor(count)}",
                FailoverGroup.roomFor(count) >= FailoverGroup.MIN_ORDINARY,
            )
            assertTrue(
                "mobile=$count claimed more slots than it named",
                FailoverGroup.mobileSlots(count) <= count,
            )
        }
    }

    /** A reserve smaller than one draw would be a reserve in name only. */
    @Test
    fun `the reserve holds a whole draw`() {
        assertTrue(FailoverGroup.MIN_ORDINARY >= FailoverGroup.MAX_GROUP)
    }

    /** With room restored, the ordinary pool is actually populated again. */
    @Test
    fun `ordinary candidates come back for a long mobile list`() {
        val mobileIds = (1..34).map { "m$it" }.toSet()
        val nodes = (1..34).map { node("m$it") } +
            (1..20).map { node("o$it") }

        val ordinary = FailoverGroup.candidates(
            nodes = nodes,
            selected = nodes.last(),
            latencies = emptyMap(),
            limit = FailoverGroup.roomFor(mobileIds.size),
            exclude = mobileIds,
        )

        assertTrue("nothing to come back to", ordinary.isNotEmpty())
        assertTrue("a mobile server leaked in", ordinary.none { it.id in mobileIds })
    }

    @Test
    fun `automatic failover skips excluded countries even when they are fastest`() {
        val current = node("cur", name = "${WorldMap.flagOf("NL")} Current")
        val eu = node("eu", name = "${WorldMap.flagOf("EU")} Europe")
        val ru = node("ru", name = "${WorldMap.flagOf("RU")} Moscow")
        val de = node("de", name = "${WorldMap.flagOf("DE")} Berlin")
        val nl = node("nl", name = "${WorldMap.flagOf("NL")} Amsterdam")
        val nodes = listOf(current, eu, ru, de, nl)

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = current,
            latencies = mapOf(
                latency("eu", 5),
                latency("ru", 10),
                latency("de", 40),
                latency("nl", 80),
            ),
            limit = FailoverGroup.MAX_GROUP,
            filterSettings = AppSettings(
                serverFilterCountries = setOf("EU", "RU"),
                serverFilterCountryMode = CountryFilterMode.Exclude,
            ),
        )

        assertEquals(listOf("de", "nl"), group.map { it.id })
    }

    @Test
    fun `automatic failover honors Include country mode and protocol filter including group members`() {
        val current = node(
            "cur",
            name = "${WorldMap.flagOf("DE")} Current",
            settings = ProxySettings.Vless(uuid = "u0"),
        )
        val euVless = node(
            "eu-vless",
            name = "${WorldMap.flagOf("EU")} Europe VLESS",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val nlTrojan = node(
            "nl-trojan",
            name = "${WorldMap.flagOf("NL")} Amsterdam Trojan",
            settings = ProxySettings.Trojan(password = "p1"),
        )
        val nlVless = node(
            "nl-vless",
            name = "${WorldMap.flagOf("NL")} Amsterdam VLESS",
            settings = ProxySettings.Vless(uuid = "u2"),
        )
        val nlGroupWithVless = node(
            "nl-grp",
            name = "${WorldMap.flagOf("NL")} Amsterdam Group",
            settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p2"),
            group = NodeGroup(
                members = listOf(
                    node("m1", settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p2")),
                    node("m2", settings = ProxySettings.Vless(uuid = "u3")),
                ),
            ),
        )
        val nodes = listOf(current, euVless, nlTrojan, nlVless, nlGroupWithVless)

        val group = FailoverGroup.candidates(
            nodes = nodes,
            selected = current,
            latencies = mapOf(
                latency("nl-vless", 30),
                latency("nl-grp", 50),
            ),
            limit = FailoverGroup.MAX_GROUP,
            filterSettings = AppSettings(
                serverFilterProtocols = setOf(Protocol.VLESS),
                serverFilterCountries = setOf("NL"),
                serverFilterCountryMode = CountryFilterMode.Include,
            ),
        )

        assertEquals(listOf("nl-vless", "nl-grp"), group.map { it.id })
    }

    @Test
    fun `automatic failover returns empty when every other server is excluded`() {
        val current = node("cur", name = "${WorldMap.flagOf("DE")} Berlin")
        val eu1 = node("eu1", name = "${WorldMap.flagOf("EU")} Europe 1")
        val eu2 = node("eu2", name = "${WorldMap.flagOf("EU")} Europe 2")

        val group = FailoverGroup.candidates(
            nodes = listOf(current, eu1, eu2),
            selected = current,
            latencies = emptyMap(),
            limit = FailoverGroup.MAX_GROUP,
            filterSettings = AppSettings(
                serverFilterCountries = setOf("EU"),
                serverFilterCountryMode = CountryFilterMode.Exclude,
            ),
        )

        assertTrue(group.isEmpty())
    }

    @Test
    fun `explicit manual failover list is not overridden by server filters`() {
        val current = node("cur", name = "${WorldMap.flagOf("DE")} Berlin")
        val eu = node("eu", name = "${WorldMap.flagOf("EU")} Europe")
        val nl = node("nl", name = "${WorldMap.flagOf("NL")} Amsterdam")

        val group = FailoverGroup.candidates(
            nodes = listOf(current, eu, nl),
            selected = current,
            latencies = emptyMap(),
            limit = FailoverGroup.MAX_GROUP,
            chosen = setOf("eu"),
            filterSettings = AppSettings(
                failoverNodeIds = setOf("eu"),
                serverFilterCountries = setOf("EU"),
                serverFilterCountryMode = CountryFilterMode.Exclude,
            ),
        )

        assertEquals(listOf("eu"), group.map { it.id })
    }

    @Test
    fun `switchable group omits excluded servers from automatic spares while keeping selected and mobile`() {
        val selected = node("cur", name = "${WorldMap.flagOf("DE")} Selected")
        val mobileEu = node("mob-eu", name = "${WorldMap.flagOf("EU")} Mobile EU")
        val spareEu = node("spare-eu", name = "${WorldMap.flagOf("EU")} Spare EU")
        val spareRu = node("spare-ru", name = "${WorldMap.flagOf("RU")} Spare RU")
        val spareNl = node("spare-nl", name = "${WorldMap.flagOf("NL")} Spare NL")

        val group = FailoverGroup.switchable(
            nodes = listOf(selected, mobileEu, spareEu, spareRu, spareNl),
            selected = selected,
            latencies = mapOf(
                latency("spare-eu", 5),
                latency("spare-ru", 10),
                latency("spare-nl", 40),
            ),
            settings = AppSettings(
                mobileNodeIds = setOf("mob-eu"),
                serverFilterCountries = setOf("EU", "RU"),
                serverFilterCountryMode = CountryFilterMode.Exclude,
            ),
        )

        assertEquals(listOf("cur", "mob-eu", "spare-nl"), group.map { it.id })
    }

    @Test
    fun `isAllowedAutomaticTarget enforces filters only in automatic mode and ignores stale filters`() {
        val eu = node("eu", name = "${WorldMap.flagOf("EU")} Europe", settings = ProxySettings.Trojan(password = "p"))
        val de = node("de", name = "${WorldMap.flagOf("DE")} Berlin", settings = ProxySettings.Vless(uuid = "u1"))
        val nl = node("nl", name = "${WorldMap.flagOf("NL")} Amsterdam", settings = ProxySettings.Vless(uuid = "u2"))
        val nodes = listOf(eu, de, nl)

        val autoSettings = AppSettings(
            serverFilterProtocols = setOf(Protocol.VLESS),
            serverFilterCountries = setOf("EU"),
            serverFilterCountryMode = CountryFilterMode.Exclude,
        )
        assertFalse(FailoverGroup.isAllowedAutomaticTarget(eu, nodes, autoSettings))
        assertTrue(FailoverGroup.isAllowedAutomaticTarget(de, nodes, autoSettings))

        val staleFilterSettings = AppSettings(
            serverFilterProtocols = setOf(Protocol.WIREGUARD),
            serverFilterCountries = setOf("JP"),
            serverFilterCountryMode = CountryFilterMode.Include,
        )
        assertTrue(FailoverGroup.isAllowedAutomaticTarget(eu, nodes, staleFilterSettings))

        val manualListSettings = autoSettings.copy(failoverNodeIds = setOf("eu"))
        assertTrue(FailoverGroup.isAllowedAutomaticTarget(eu, nodes, manualListSettings))
    }
}
