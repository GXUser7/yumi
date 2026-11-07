package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProviderTarget
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileParserTest {

    private fun node(id: String, name: String) = ProxyNode(
        id = id,
        name = name,
        server = "203.0.113.1",
        port = 443,
        settings = ProxySettings.Vless(uuid = ProviderFixtures.UUID),
        subscriptionId = "sub",
    )

    @Test
    fun `an ordinary document keeps every rule, in order, by what it means`() {
        val profile = ProviderProfileParser.parse(ProviderFixtures.list(ProviderFixtures.ordinary()))!!
            .getValue("🇩🇪 Германия #2")

        assertEquals(
            listOf(ProviderTarget.Proxy, ProviderTarget.Block, ProviderTarget.Direct, ProviderTarget.Direct),
            profile.rules.map { it.target },
        )
        assertEquals(0, profile.skippedRules)
        // Conditions as written; the tags and the type are rebuilt by the factory, not stored.
        val russian = profile.rules.last().match
        assertEquals(setOf("domain"), russian.keys)
        assertEquals(5, russian.getValue("domain").jsonArray.size)
    }

    @Test
    fun `the provider's resolvers are read with their query strategy`() {
        val dns = ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single().dns!!
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), dns.servers.map { it.jsonPrimitive.content })
        assertEquals("UseIP", dns.queryStrategy)
    }

    @Test
    fun `a balancer counts as the proxy and the catch-all bound to local inbounds is left out`() {
        val profile = ProviderProfileParser.parse(ProviderFixtures.auto())!!.values.single()

        assertEquals(ProviderTarget.Proxy, profile.rules.first().target)
        // Four carried; the fifth names `socks` and `http`, which do not exist in this tunnel, and
        // translated it would have been a catch-all ahead of every rule the user chose.
        assertEquals(4, profile.rules.size)
        assertEquals(1, profile.skippedRules)
        assertTrue(profile.rules.none { "inboundTag" in it.match })
    }

    @Test
    fun `rules aimed at a fragmenting outbound, at the dns outbound or carrying unknown conditions are left out`() {
        val profile = ProviderProfileParser.parse(ProviderFixtures.fragmenting())!!.values.single()

        assertEquals(1, profile.rules.size)
        assertEquals("domain:2ip.ru", profile.rules.single().match.getValue("domain").jsonArray.single().jsonPrimitive.content)
        assertEquals(3, profile.skippedRules)
        // No `dns` section in that document, and none is invented.
        assertNull(profile.dns)
    }

    @Test
    fun `resolvers this core cannot ask are dropped and objects keep only what is carried`() {
        val document = """
            {"remarks": "R", "outbounds": [{"tag": "proxy", "protocol": "vless"}],
             "dns": {"servers": ["localhost", "tls://1.1.1.1", "fakedns",
               {"address": "https://dns.example.com/dns-query", "domains": ["domain:example.com"], "clientIp": "198.51.100.1"}],
               "queryStrategy": "Nonsense"}}
        """.trimIndent()
        val dns = ProviderProfileParser.parse(document)!!.getValue("R").dns!!

        val server = dns.servers.single() as JsonObject
        assertEquals(setOf("address", "domains"), server.keys)
        assertEquals(JsonPrimitive("https://dns.example.com/dns-query"), server["address"])
        // A strategy Xray would refuse is not carried; the factory falls back to its own.
        assertNull(dns.queryStrategy)
    }

    @Test
    fun `a document with nothing that can be carried leaves its server on the app's own routing`() {
        val document = """
            {"remarks": "Bare", "outbounds": [{"tag": "proxy", "protocol": "vless"}],
             "routing": {"rules": [{"type": "field", "inboundTag": ["socks"], "outboundTag": "proxy"}]}}
        """.trimIndent()
        val parsed = ProviderProfileParser.parse(document)!!
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `an answer that is not documents is not mistaken for one`() {
        assertNull(ProviderProfileParser.parse("vless://${ProviderFixtures.UUID}@example.com:443#Server"))
        assertNull(ProviderProfileParser.parse("[]"))
        assertNull(ProviderProfileParser.parse("""{"proxies": []}"""))
    }

    @Test
    fun `profiles attach to servers by trimmed name and identical ones are stored once`() {
        val parsed = ProviderProfileParser.parse(
            ProviderFixtures.list(
                ProviderFixtures.ordinary("Germany #1"),
                ProviderFixtures.ordinary("Germany #2"),
                ProviderFixtures.torrent("Netherlands | Torrent"),
            ),
        )!!
        val nodes = listOf(
            node("a", "Germany #1 "),
            node("b", "Germany #2"),
            node("c", "Netherlands | Torrent"),
            node("d", "Listed as a link only"),
        )

        val routing = ProviderProfileParser.routingFor(nodes, parsed)

        assertEquals(setOf("a", "b", "c"), routing.byNode.keys)
        assertEquals(routing.byNode["a"], routing.byNode["b"])
        assertNotEquals(routing.byNode["a"], routing.byNode["c"])
        assertEquals(2, routing.profiles.size)
        assertNull(routing.profileFor("d"))
        assertNotNull(routing.profileFor("c"))
    }

    @Test
    fun `pruning keeps only the profiles a surviving server still uses`() {
        val parsed = ProviderProfileParser.parse(
            ProviderFixtures.list(ProviderFixtures.ordinary("A"), ProviderFixtures.torrent("B")),
        )!!
        val routing = ProviderProfileParser.routingFor(listOf(node("a", "A"), node("b", "B")), parsed)

        val kept = routing.keepingOnly(setOf("b"))

        assertEquals(setOf("b"), kept.byNode.keys)
        assertEquals(1, kept.profiles.size)
        assertEquals(routing.profileFor("b"), kept.profileFor("b"))
    }

    @Test
    fun `the key is stable for equal content`() {
        val first = ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single()
        val second = ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single()
        assertEquals(ProviderProfileParser.keyOf(first), ProviderProfileParser.keyOf(second))
    }
}
