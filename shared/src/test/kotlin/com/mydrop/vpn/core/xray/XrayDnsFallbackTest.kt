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

    /**
     * The watchdog announces `fallbackResolver(chosenResolver(…))` before it has the core rebuilt;
     * this is the promise that the rebuilt core queries the resolver it named.
     */
    @Test
    fun `the document queries the replacement the watchdog announces`() {
        val cases = listOf(
            AppSettings(remoteDns = chosen) to null,
            AppSettings(remoteDns = chosen) to "https://dns.adguard-dns.com/dns-query",
            AppSettings(remoteDns = chosen) to "1.1.1.1",
            // A blank profile URL is no override at all, for the announcement as for the document.
            AppSettings(remoteDns = chosen) to "",
            AppSettings(routingMode = RoutingMode.Global, remoteDns = "udp://1.1.1.1") to null,
            AppSettings(routingMode = RoutingMode.Direct, directDns = "77.88.8.8") to null,
            AppSettings(routingMode = RoutingMode.Direct) to "https://1.1.1.1/dns-query",
        )
        for ((settings, override) in cases) {
            val announced = XrayConfigFactory.fallbackResolver(
                XrayConfigFactory.chosenResolver(settings, override),
            )
            assertEquals(
                "$settings with override $override",
                announced,
                document(settings = settings, dnsOverride = override).servers().first(),
            )
        }
    }

    @Test
    fun `the fallback wins over a provider's resolvers and keeps its rules`() {
        val provider = ProviderProfileParser.parse(ProviderFixtures.ordinary())!!.values.single()
        val withoutFallback = document(provider = provider, dnsFallback = false)
        // The premise: left alone, the provider's resolvers are the ones in force.
        assertTrue(withoutFallback.providerDns)

        val built = document(provider = provider)

        assertFalse(built.providerDns)
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
