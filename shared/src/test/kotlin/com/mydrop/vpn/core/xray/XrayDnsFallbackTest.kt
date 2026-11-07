package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProviderDns
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.parse.ProviderFixtures
import com.mydrop.vpn.core.parse.ProviderProfileParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `dnsFallback` does to the document the watchdog has the core rebuilt with.
 *
 * The flag was set, announced and acted on for several releases while the factory never read it:
 * the core came back with the resolver that had just been declared dead, and the user was told it
 * had been replaced. Each test here is one place the replacement has to actually land.
 */
class XrayDnsFallbackTest {

    private val server = ProxyNode(
        id = "n1",
        name = "Server",
        server = "203.0.113.10",
        port = 443,
        settings = ProxySettings.Vless(uuid = ProviderFixtures.UUID),
    )

    private val probe = ProbeEndpoint(port = 10808, username = "probe", password = "probe-password")

    /** A resolver somebody chose, so the watchdog polices it — the only case the fallback exists for. */
    private val chosen = "https://dns.example.com/dns-query"

    private fun document(
        settings: AppSettings = AppSettings(remoteDns = chosen),
        dnsOverride: String? = null,
        provider: ProviderProfile? = null,
        dnsFallback: Boolean = true,
    ): XrayConfigFactory.Document = XrayConfigFactory.build(
        nodes = listOf(server),
        selected = server,
        settings = settings,
        probe = probe,
        dnsOverride = dnsOverride,
        provider = provider,
        dnsFallback = dnsFallback,
    )

    private fun XrayConfigFactory.Document.root(): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun XrayConfigFactory.Document.servers(): List<String> =
        root()["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun XrayConfigFactory.Document.dnsRules(): List<JsonObject> =
        root()["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.filter { rule ->
            rule["inboundTag"]?.jsonArray?.any {
                it.jsonPrimitive.content == XrayConfigFactory.DNS_IN_TAG
            } == true
        }

    private fun JsonObject.listOf(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** Quattro's: `8.8.8.8` then `1.1.1.1`, plain UDP, and routing rules beside them. */
    private val quattro: ProviderProfile =
        ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single()

    /** A provider that sent resolvers and nothing else. */
    private fun resolvingOnly(vararg servers: JsonElement) =
        ProviderProfile(dns = ProviderDns(servers = servers.toList()))

    @Test
    fun `without the flag the chosen resolver is the one queried`() {
        assertEquals(chosen, document(dnsFallback = false).servers().first())
    }

    @Test
    fun `the fallback replaces the chosen resolver in dns servers`() {
        val built = document()

        assertEquals(
            listOf(XrayConfigFactory.FALLBACK_REMOTE_DNS, XrayConfigFactory.DEFAULT_DIRECT_DNS),
            built.servers(),
        )
        // Not kept as a bootstrap either: a resolver the document no longer names has no business
        // being looked up by name.
        assertTrue(built.dnsRules().none { "full:dns.example.com" in it.listOf("domain") })
    }

    @Test
    fun `the fallback replaces the resolver chosen on the dns screen`() {
        val built = document(dnsOverride = "https://dns.adguard-dns.com/dns-query")

        assertEquals(XrayConfigFactory.FALLBACK_REMOTE_DNS, built.servers().first())
        assertFalse("https://dns.adguard-dns.com/dns-query" in built.servers())
    }

    @Test
    fun `falling back from 1_1_1_1 goes somewhere else`() {
        // Spelled as a bare address, so it is not the shipped default and the watchdog polices it.
        val built = document(dnsOverride = "1.1.1.1")

        assertEquals(XrayConfigFactory.FALLBACK_REMOTE_DNS_ALT, built.servers().first())
    }

    @Test
    fun `in direct mode the fallback replaces the direct resolver`() {
        val built = document(settings = AppSettings(routingMode = RoutingMode.Direct, directDns = "77.88.8.8"))

        assertEquals(listOf(XrayConfigFactory.FALLBACK_REMOTE_DNS), built.servers())
    }

    /** The user's resolvers, with and without the DNS screen, in each routing mode. */
    private val userCases = listOf(
        AppSettings(remoteDns = chosen) to null,
        AppSettings(remoteDns = chosen) to "https://dns.adguard-dns.com/dns-query",
        AppSettings(remoteDns = chosen) to "1.1.1.1",
        // A blank profile URL is no override at all, for the announcement as for the document.
        AppSettings(remoteDns = chosen) to "",
        AppSettings(routingMode = RoutingMode.Global, remoteDns = "udp://1.1.1.1") to null,
        AppSettings(routingMode = RoutingMode.Direct, directDns = "77.88.8.8") to null,
        AppSettings(routingMode = RoutingMode.Direct) to "https://1.1.1.1/dns-query",
    )

    @Test
    fun `without a provider's resolvers the document names the user's`() {
        for ((settings, override) in userCases) {
            val built = document(settings = settings, dnsOverride = override, dnsFallback = false)
            assertFalse(built.providerDns)
            assertEquals(
                "$settings with override $override",
                XrayConfigFactory.chosenResolver(settings, override),
                built.resolver,
            )
        }
    }

    /**
     * The watchdog names the running document's [XrayConfigFactory.Document.resolver] as dead and
     * announces `fallbackResolver` of it before it has the core rebuilt. This is the promise that
     * the rebuilt core queries the resolver it named, whoever's resolver was in force before.
     */
    @Test
    fun `the document queries the replacement the watchdog announces`() {
        val providerCases = listOf(
            AppSettings() to quattro,
            AppSettings(remoteDns = chosen) to quattro,
            AppSettings(remoteDns = chosen) to resolvingOnly(JsonPrimitive("1.1.1.1")),
            AppSettings() to resolvingOnly(JsonPrimitive("9.9.9.9")),
            AppSettings(routingMode = RoutingMode.Global) to
                resolvingOnly(JsonPrimitive("https://1.1.1.1/dns-query")),
        )
        // The DNS screen's choice beside every provider: it must not be what the fallback replaces.
        val screen = "https://dns.adguard-dns.com/dns-query"
        val cases: List<Triple<AppSettings, String?, ProviderProfile?>> =
            userCases.map { (settings, override) -> Triple(settings, override, null) } +
                providerCases.map { (settings, provider) -> Triple(settings, screen, provider) }

        for ((settings, override, provider) in cases) {
            val label = "$settings with override $override and provider $provider"
            val running = document(settings, override, provider, dnsFallback = false)
            val announced = XrayConfigFactory.fallbackResolver(running.resolver)

            val rebuilt = document(settings, override, provider, dnsFallback = true)

            assertEquals(label, announced, rebuilt.servers().first())
            // And the rebuilt document names it too, so a fallback that dies as well is reported
            // under its own name.
            assertEquals(label, announced, rebuilt.resolver)
        }
    }

    @Test
    fun `with the provider's resolvers in force the document names the provider's, whatever the user chose`() {
        val users = listOf(
            // The shipped default: the case the watchdog used to leave unpoliced.
            AppSettings() to null,
            AppSettings(remoteDns = chosen) to null,
            AppSettings() to "https://dns.adguard-dns.com/dns-query",
            AppSettings(routingMode = RoutingMode.Global) to null,
        )
        for ((settings, override) in users) {
            val built = document(settings, override, quattro, dnsFallback = false)

            assertTrue(built.providerDns)
            assertEquals("$settings with override $override", "8.8.8.8", built.resolver)
            assertEquals("8.8.8.8", built.servers().first())
        }
    }

    @Test
    fun `the fallback for a provider's resolver is worked out from that resolver`() {
        // Worked out from the user's resolver, this would be 1.1.1.1 again: the host that just
        // stopped answering, only spoken to over a different transport.
        val fromProvider11 = document(provider = resolvingOnly(JsonPrimitive("1.1.1.1")))
        assertEquals(XrayConfigFactory.FALLBACK_REMOTE_DNS_ALT, fromProvider11.servers().first())

        // And the other way round: the user's 1.1.1.1 is not what died, so the fallback can go there.
        val fromProvider99 = document(
            settings = AppSettings(),
            provider = resolvingOnly(JsonPrimitive("9.9.9.9")),
        )
        assertEquals(XrayConfigFactory.FALLBACK_REMOTE_DNS, fromProvider99.servers().first())
        assertFalse(fromProvider99.providerDns)
    }

    @Test
    fun `a provider's resolver that answers only for its own names is not the one named`() {
        val provider = resolvingOnly(
            Json.parseToJsonElement(
                """{"address": "https://dns.provider.example/dns-query",
                   "domains": ["domain:provider.example"], "skipFallback": true}""",
            ),
            JsonPrimitive("9.9.9.9"),
        )

        assertEquals("9.9.9.9", document(provider = provider, dnsFallback = false).resolver)
    }

    @Test
    fun `in direct mode the provider's resolvers are not in force, so neither is their name`() {
        val built = document(
            settings = AppSettings(routingMode = RoutingMode.Direct, directDns = "77.88.8.8"),
            provider = quattro,
            dnsFallback = false,
        )

        assertFalse(built.providerDns)
        assertEquals("77.88.8.8", built.resolver)
    }

    @Test
    fun `the fallback wins over a provider's resolvers and keeps its rules`() {
        val withoutFallback = document(provider = quattro, dnsFallback = false)
        // The premise: left alone, the provider's resolvers are the ones in force.
        assertTrue(withoutFallback.providerDns)

        val built = document(provider = quattro)

        assertFalse(built.providerDns)
        // 8.8.8.8 is the provider's first resolver, and it shares nothing with 1.1.1.1.
        assertEquals("8.8.8.8", withoutFallback.resolver)
        assertEquals(
            listOf(XrayConfigFactory.FALLBACK_REMOTE_DNS, XrayConfigFactory.DEFAULT_DIRECT_DNS),
            built.servers(),
        )
        assertEquals(withoutFallback.providerRules, built.providerRules)
        assertTrue(built.providerRules > 0)
    }

    @Test
    fun `the fallback rides the proxy in rules mode`() {
        val rules = document().dnsRules()

        // 1.1.1.1 is numeric, so nothing has to be resolved to reach it, and nothing sends it
        // around the server the way the direct resolver is sent.
        assertTrue(rules.none { "1.1.1.1" in it.listOf("ip") })
        assertEquals(XrayConfigFactory.BALANCER_TAG, rules.last()["balancerTag"]!!.jsonPrimitive.content)
    }
}
