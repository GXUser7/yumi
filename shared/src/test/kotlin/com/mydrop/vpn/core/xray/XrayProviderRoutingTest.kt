package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.parse.ProviderFixtures
import com.mydrop.vpn.core.parse.ProviderProfileParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a provider's rules and resolvers land in the document, and what is refused on the way in.
 *
 * The order is the feature: the provider's rules ahead of the user's own, and behind the plumbing
 * that makes the tunnel work at all. Each test here stands for one way of getting that wrong.
 */
class XrayProviderRoutingTest {

    private val server = ProxyNode(
        id = "n1",
        name = "🇩🇪 Германия #2",
        server = "203.0.113.10",
        port = 443,
        settings = ProxySettings.Vless(uuid = ProviderFixtures.UUID),
    )

    private val probe = ProbeEndpoint(port = 10808, username = "probe", password = "probe-password")

    private fun profile(document: String = ProviderFixtures.ordinary()): ProviderProfile =
        ProviderProfileParser.parse(document)!!.values.single()

    private fun document(
        provider: ProviderProfile? = profile(),
        settings: AppSettings = AppSettings(),
        geoAvailable: Boolean = true,
        geoKnown: (String) -> Boolean = { true },
    ): XrayConfigFactory.Document = XrayConfigFactory.build(
        nodes = listOf(server),
        selected = server,
        settings = settings,
        probe = probe,
        geoAvailable = geoAvailable,
        provider = provider,
        geoKnown = geoKnown,
    )

    private fun XrayConfigFactory.Document.root(): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.rules(): List<JsonObject> =
        this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.listOf(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun List<JsonObject>.indexOfDomain(entry: String): Int =
        indexOfFirst { entry in it.listOf("domain") }

    @Test
    fun `provider rules come after the plumbing and before the user's own`() {
        val rules = document().root().rules()

        val probeRule = rules.indexOfFirst { XrayConfigFactory.PROBE_TAG in it.listOf("inboundTag") }
        val lanBypass = rules.indexOfFirst { "10.0.0.0/8" in it.listOf("ip") }
        val providerFirst = rules.indexOfDomain("domain:2ip.ru")
        val providerRussian = rules.indexOfDomain("max.ru")
        // The rules mode's own Russian rule, which the provider's must be asked before.
        val userRussian = rules.indexOfFirst {
            it.listOf("domain") == listOf("geosite:category-ru")
        }

        assertTrue(probeRule in 0 until providerFirst)
        assertTrue(lanBypass in 0 until providerFirst)
        assertTrue(providerFirst < providerRussian)
        assertTrue(providerRussian < userRussian)
        // And the catch-all still closes the list, whatever the provider added.
        assertEquals("tcp,udp", rules.last()["network"]!!.jsonPrimitive.content)
        assertEquals(XrayConfigFactory.BALANCER_TAG, rules.last()["balancerTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider targets become this app's balancer and outbounds`() {
        val rules = document().root().rules()

        val viaProxy = rules[rules.indexOfDomain("domain:2ip.ru")]
        assertEquals(XrayConfigFactory.BALANCER_TAG, viaProxy["balancerTag"]!!.jsonPrimitive.content)
        assertFalse("outboundTag" in viaProxy)

        val quic = rules.first { it.listOf("protocol") == listOf("quic") }
        assertEquals(XrayConfigFactory.BLOCK_TAG, quic["outboundTag"]!!.jsonPrimitive.content)

        val torrents = rules.first { it.listOf("protocol") == listOf("bittorrent") }
        assertEquals(XrayConfigFactory.DIRECT_TAG, torrents["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("field", torrents["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the auto-select catch-all never reaches the document`() {
        val rules = document(provider = profile(ProviderFixtures.auto())).root().rules()
        assertTrue(rules.none { "socks" in it.listOf("inboundTag") || "http" in it.listOf("inboundTag") })
    }

    @Test
    fun `geo references are dropped without the databases and the rest of the rule is kept`() {
        val built = document(geoAvailable = false)
        val russian = built.root().rules()[built.root().rules().indexOfDomain("max.ru")]

        assertEquals(listOf("max.ru", "full:go.yandex", "domain:2gis.ru", "domain:1c.ru"), russian.listOf("domain"))
        assertTrue(built.root().rules().none { entry -> entry.listOf("domain").any { it.startsWith("geosite:") } })
        assertEquals(0, built.providerRulesDropped)
    }

    @Test
    fun `a rule left with no entries is dropped whole and counted`() {
        val geoOnly = """
            {"remarks": "G", "outbounds": [{"tag": "proxy", "protocol": "vless"}, {"tag": "direct", "protocol": "freedom"}],
             "routing": {"rules": [
               {"type": "field", "domain": ["geosite:category-gov-ru"], "outboundTag": "direct"},
               {"type": "field", "ip": ["ext:custom.dat:ru"], "outboundTag": "direct"},
               {"type": "field", "domain": ["domain:2ip.ru"], "outboundTag": "proxy"}
             ]}}
        """.trimIndent()
        // The category is not in the databases on this phone, and `ext:` files never are.
        val built = document(provider = profile(geoOnly), geoKnown = { it != "geosite:category-gov-ru" })
        val rules = built.root().rules()

        assertEquals(1, built.providerRules)
        assertEquals(2, built.providerRulesDropped)
        assertTrue(rules.none { entry -> entry.listOf("domain").contains("geosite:category-gov-ru") })
        assertTrue(rules.none { entry -> entry.listOf("ip").any { it.startsWith("ext:") } })
    }

    @Test
    fun `the provider's resolvers replace the user's and ride the proxy`() {
        val built = document()
        val dns = built.root()["dns"]!!.jsonObject

        assertTrue(built.providerDns)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), dns.listOf("servers"))
        // IPv6 is off by default, and the provider's UseIP must not hand out addresses the tunnel
        // has decided it cannot carry.
        assertEquals("UseIPv4", dns["queryStrategy"]!!.jsonPrimitive.content)

        val dnsRules = built.root().rules().filter { XrayConfigFactory.DNS_IN_TAG in it.listOf("inboundTag") }
        // Not the user's direct-resolver rule: 8.8.8.8 is the provider's and goes through the server.
        assertTrue(dnsRules.none { it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG })
        assertEquals(XrayConfigFactory.BALANCER_TAG, dnsRules.single()["balancerTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the provider's query strategy stands when the tunnel carries IPv6`() {
        val dns = document(settings = AppSettings(enableIpv6 = true)).root()["dns"]!!.jsonObject
        assertEquals("UseIP", dns["queryStrategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a resolver named by domain is bootstrapped directly, like the user's`() {
        val named = """
            {"remarks": "D", "outbounds": [{"tag": "proxy", "protocol": "vless"}],
             "dns": {"servers": ["https://dns.example.com/dns-query"]}}
        """.trimIndent()
        val rules = document(provider = profile(named)).root().rules()
            .filter { XrayConfigFactory.DNS_IN_TAG in it.listOf("inboundTag") }

        assertEquals(listOf("full:dns.example.com"), rules.first().listOf("domain"))
        assertEquals(XrayConfigFactory.DIRECT_TAG, rules.first()["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(XrayConfigFactory.BALANCER_TAG, rules.last()["balancerTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct mode ignores the provider entirely`() {
        val built = document(settings = AppSettings(routingMode = RoutingMode.Direct))
        val root = built.root()

        assertEquals(0, built.providerRules)
        assertFalse(built.providerDns)
        assertEquals(listOf(XrayConfigFactory.DEFAULT_DIRECT_DNS), root["dns"]!!.jsonObject.listOf("servers"))
        assertEquals(-1, root.rules().indexOfDomain("domain:2ip.ru"))
    }

    @Test
    fun `no provider means exactly the document there was before`() {
        val before = XrayConfigFactory.build(
            nodes = listOf(server),
            selected = server,
            settings = AppSettings(),
            probe = probe,
            geoAvailable = true,
        )
        val after = document(provider = null)
        assertEquals(before.json, after.json)
        assertEquals(0, after.providerRules)
    }
}
