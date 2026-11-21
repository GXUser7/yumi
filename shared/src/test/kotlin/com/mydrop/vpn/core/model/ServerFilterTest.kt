package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFilterTest {

    private fun node(
        id: String,
        name: String,
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        subscriptionId: String? = "sub-1",
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = "example.com",
        port = 443,
        settings = settings,
        subscriptionId = subscriptionId,
        group = group,
    )

    @Test
    fun `exclude country mode drops selected countries and keeps others, flagless, and manual nodes`() {
        val eu = node(id = "eu", name = "${WorldMap.flagOf("EU")} Europe #1")
        val ru = node(id = "ru", name = "${WorldMap.flagOf("RU")} Moscow")
        val de = node(id = "de", name = "${WorldMap.flagOf("DE")} Berlin")
        val noFlag = node(id = "auto", name = "Fast Auto")
        val manual = node(id = "man", name = "Manual Server", subscriptionId = null)

        val filtered = ServerFilter.filterNodes(
            nodes = listOf(eu, ru, de, noFlag, manual),
            settings = AppSettings(
                serverFilterCountries = setOf("EU", "RU"),
                serverFilterCountryMode = CountryFilterMode.Exclude,
            ),
        )

        assertEquals(listOf("de", "auto", "man"), filtered.map { it.id })
    }

    @Test
    fun `include country mode keeps only selected countries and drops flagless nodes`() {
        val eu = node(id = "eu", name = "${WorldMap.flagOf("EU")} Europe #1")
        val de = node(id = "de", name = "${WorldMap.flagOf("DE")} Berlin")
        val nl = node(id = "nl", name = "${WorldMap.flagOf("NL")} Amsterdam")
        val noFlag = node(id = "auto", name = "Fast Auto")
        val manualDe = node(id = "man-de", name = "${WorldMap.flagOf("DE")} Manual DE", subscriptionId = null)

        val filtered = ServerFilter.filterNodes(
            nodes = listOf(eu, de, nl, noFlag, manualDe),
            settings = AppSettings(
                serverFilterCountries = setOf("DE", "NL"),
                serverFilterCountryMode = CountryFilterMode.Include,
            ),
        )

        assertEquals(listOf("de", "nl", "man-de"), filtered.map { it.id })
    }

    @Test
    fun `protocol filter matches primary protocol and group member protocols`() {
        val vless = node(
            id = "vless",
            name = "${WorldMap.flagOf("DE")} VLESS",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val ss = node(
            id = "ss",
            name = "${WorldMap.flagOf("DE")} SS",
            settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p"),
        )
        val groupWithTrojan = node(
            id = "grp",
            name = "${WorldMap.flagOf("DE")} Group",
            settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p"),
            group = NodeGroup(
                members = listOf(
                    node(
                        id = "m1",
                        name = "Member 1",
                        settings = ProxySettings.Shadowsocks(method = "aes-128-gcm", password = "p"),
                    ),
                    node(
                        id = "m2",
                        name = "Member 2",
                        settings = ProxySettings.Trojan(password = "secret"),
                    ),
                ),
            ),
        )

        val filtered = ServerFilter.filterNodes(
            nodes = listOf(vless, ss, groupWithTrojan),
            settings = AppSettings(serverFilterProtocols = setOf(Protocol.TROJAN)),
        )

        assertEquals(listOf("grp"), filtered.map { it.id })
    }

    @Test
    fun `combined protocol and country filters can produce an empty list`() {
        val nlTrojan = node(
            id = "nl-trojan",
            name = "${WorldMap.flagOf("NL")} Trojan",
            settings = ProxySettings.Trojan(password = "p"),
        )
        val deVless = node(
            id = "de-vless",
            name = "${WorldMap.flagOf("DE")} VLESS",
            settings = ProxySettings.Vless(uuid = "u1"),
        )

        val filtered = ServerFilter.filterNodes(
            nodes = listOf(nlTrojan, deVless),
            settings = AppSettings(
                serverFilterProtocols = setOf(Protocol.VLESS),
                serverFilterCountries = setOf("NL"),
                serverFilterCountryMode = CountryFilterMode.Include,
            ),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `stale country and protocol filters absent from current nodes are ignored`() {
        val de = node(
            id = "de",
            name = "${WorldMap.flagOf("DE")} Berlin",
            settings = ProxySettings.Vless(uuid = "u1"),
        )
        val nl = node(
            id = "nl",
            name = "${WorldMap.flagOf("NL")} Amsterdam",
            settings = ProxySettings.Vless(uuid = "u2"),
        )

        val filtered = ServerFilter.filterNodes(
            nodes = listOf(de, nl),
            settings = AppSettings(
                serverFilterProtocols = setOf(Protocol.WIREGUARD),
                serverFilterCountries = setOf("JP"),
                serverFilterCountryMode = CountryFilterMode.Include,
            ),
        )

        assertEquals(listOf("de", "nl"), filtered.map { it.id })
    }
}
