package com.mydrop.vpn.e2e

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProviderDns
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProviderRule
import com.mydrop.vpn.core.model.ProviderTarget
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import com.mydrop.vpn.core.parse.DnsUriParser
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.xray.yumi.Yumi
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 & Tier 2 Opaque-Box E2E Tests for Xray Config Hardening, DNS, Loopback Blocking,
 * `probe-in` Elimination, and Full Protocol/Transport/Security Matrix Coverage.
 *
 * Covers:
 * - Area A4 (`T1-A4-05`, `T2-A4-05`)
 * - Area A6 (`T1-A6-04`, `T1-A6-05`, `T2-A6-03`, `T2-A6-04`, `T2-A6-05`)
 * - Area A7 (`T1-A7-01`)
 * - Area A8 (`T1-A8-01..03`, `T2-A8-01..04`)
 */
class ConfigAndProtocolMatrixLeakE2eTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun fakeKey(seed: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

    private fun fakeRealityKey(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { (it * 7).toByte() })

    private fun sampleNode(
        id: String = "vless-1",
        settings: ProxySettings = ProxySettings.Vless(uuid = uuid),
        tls: TlsOptions? = null,
        transport: TransportOptions? = null,
    ) = ProxyNode(
        id = id,
        name = "Node $id",
        server = "edge.example.com",
        port = 443,
        settings = settings,
        tls = tls,
        transport = transport,
    )

    private fun parseConfig(
        node: ProxyNode = sampleNode(),
        settings: AppSettings = AppSettings(),
        probe: ProbeEndpoint? = null,
        geoAvailable: Boolean = false,
        provider: ProviderProfile? = null,
    ): JsonObject {
        val doc = if (probe != null) {
            E2eContractAdapters.buildXrayConfigWithOptionalProbe(
                nodes = listOf(node),
                selected = node,
                settings = settings,
                probe = probe,
                geoAvailable = geoAvailable,
            )
        } else {
            XrayConfigFactory.build(
                nodes = listOf(node),
                selected = node,
                settings = settings,
                geoAvailable = geoAvailable,
                provider = provider,
            )
        }
        return Json.parseToJsonElement(doc.json).jsonObject
    }

    private fun JsonObject.inbounds(): List<JsonObject> =
        this["inbounds"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.rules(): List<JsonObject> =
        this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.dnsServersText(): List<String> =
        this["dns"]!!.jsonObject["servers"]!!.jsonArray.map { el ->
            when (el) {
                is JsonPrimitive -> el.content
                is JsonObject -> el["address"]?.jsonPrimitive?.content.orEmpty()
                else -> el.toString()
            }
        }

    // ------------------------------------------------------------------ Area A4: DNS

    @Test
    fun t1_a4_05_tunDnsRoutedToDnsOutWithoutHijackAndQuicLocalRejected() {
        val config = parseConfig(
            settings = AppSettings(
                remoteDns = "quic+local://dns.google",
                hijackDns = false,
            ),
        )

        val rules = config.rules()
        val hasTunDnsRule = rules.any { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "${XrayConfigFactory.TUN_DNS_V4}/32" in ips &&
                rule["port"]?.jsonPrimitive?.content == "53" &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DNS_TAG
        }
        assertTrue("172.19.0.2/32:53 must always route to dns-out even when hijackDns=false", hasTunDnsRule)

        val servers = config.dnsServersText()
        assertFalse(
            "quic+local:// bypasses DialSystem/routing and must NOT be emitted into dns.servers: $servers",
            servers.any { it.startsWith("quic+local://", ignoreCase = true) },
        )
    }

    @Test
    fun t2_a4_05_dnsUriParserAndFactoryRejectForbiddenOrUnroutableSchemes() {
        val forbidden = listOf(
            "quic+local://dns.google",
            "quic://dns.google",
            "tls://1.1.1.1",
            "h3://dns.google/dns-query",
            "localhost",
        )
        for (uri in forbidden) {
            assertNull("DnsUriParser.parse must reject $uri", DnsUriParser.parse(uri))
            val config = parseConfig(
                settings = AppSettings(
                    remoteDns = uri,
                    directDns = uri,
                ),
            )
            val servers = config.dnsServersText()
            assertFalse(
                "XrayConfigFactory must NOT emit forbidden/unprotected resolver '$uri' into dns.servers ($servers)",
                servers.any { it.equals(uri, ignoreCase = true) },
            )
        }
    }

    // ------------------------------------------------------------------ Area A6: Loopback & IPv6

    @Test
    fun t1_a6_04_loopback127AndIpv6LoopbackBlockedFromTunInAheadOfBypassLan() {
        val config = parseConfig(
            settings = AppSettings(
                bypassLan = true,
                routingMode = RoutingMode.Rules,
                enableIpv6 = true,
            ),
        )
        val rules = config.rules()

        val loopbackV4BlockIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val inboundTags = rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "127.0.0.0/8" in ips &&
                XrayConfigFactory.TUN_TAG in inboundTags &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val loopbackV6BlockIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val inboundTags = rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "::1/128" in ips &&
                XrayConfigFactory.TUN_TAG in inboundTags &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val directLanIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG &&
                ("10.0.0.0/8" in ips || "192.168.0.0/16" in ips)
        }

        assertTrue("127.0.0.0/8 from tun-in must have an explicit block rule (F11)", loopbackV4BlockIdx >= 0)
        assertTrue("::1/128 from tun-in must have an explicit block rule (F11)", loopbackV6BlockIdx >= 0)
        assertTrue("bypassLan direct rule must exist when bypassLan=true", directLanIdx >= 0)
        assertTrue(
            "127.0.0.0/8 block rule ($loopbackV4BlockIdx) must precede bypassLan direct rule ($directLanIdx)",
            loopbackV4BlockIdx < directLanIdx,
        )
        assertTrue(
            "::1/128 block rule ($loopbackV6BlockIdx) must precede bypassLan direct rule ($directLanIdx)",
            loopbackV6BlockIdx < directLanIdx,
        )
    }

    @Test
    fun t1_a6_05_ipv6AlwaysCapturedOnTunEvenWhenEnableIpv6IsFalse() {
        val config = parseConfig(settings = AppSettings(enableIpv6 = false))
        val dns = config["dns"]!!.jsonObject
        assertEquals("UseIPv4", dns["queryStrategy"]?.jsonPrimitive?.content)

        // Verify MyDropVpnService establishes IPv6 address & ::/0 route unconditionally
        val serviceSrc = E2eContractAdapters.readRepoFile(
            "shared/src/main/kotlin/com/mydrop/vpn/vpn/MyDropVpnService.kt",
        )
        assertTrue(serviceSrc.contains("builder.addAddress(XrayConfigFactory.TUN_ADDRESS_V6, XrayConfigFactory.TUN_PREFIX_V6)"))
        assertTrue(serviceSrc.contains("builder.addRoute(\"::\", 0)"))
    }

    @Test
    fun t2_a6_03_loopback127BlockedWhenBypassLanTrueAndAlsoWhenBypassLanFalse() {
        for (bypassLan in listOf(true, false)) {
            val rules = parseConfig(settings = AppSettings(bypassLan = bypassLan)).rules()
            val blocksV4 = rules.any { rule ->
                val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val inboundTags = rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                "127.0.0.0/8" in ips &&
                    XrayConfigFactory.TUN_TAG in inboundTags &&
                    rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
            }
            assertTrue("127.0.0.0/8 from tun-in must be blocked even with bypassLan=$bypassLan", blocksV4)

            val leakedToDirect = rules.any { rule ->
                val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                "127.0.0.0/8" in ips &&
                    rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG
            }
            assertFalse("127.0.0.0/8 must never appear in a direct rule (bypassLan=$bypassLan)", leakedToDirect)
        }
    }

    @Test
    fun t2_a6_04_ipv6LoopbackBlockedWhenBypassLanAndEnableIpv6True() {
        val rules = parseConfig(settings = AppSettings(bypassLan = true, enableIpv6 = true)).rules()
        val blocksV6 = rules.any { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val inboundTags = rule["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "::1/128" in ips &&
                XrayConfigFactory.TUN_TAG in inboundTags &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        assertTrue("::1/128 from tun-in must be blocked ahead of bypassLan", blocksV6)

        val leakedToDirect = rules.any { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "::1/128" in ips &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG
        }
        assertFalse("::1/128 must never appear in a direct rule", leakedToDirect)
    }

    @Test
    fun t2_a6_05_nonDnsTunSubnetTrafficBlockedAheadOfPrivateAndProxyRules() {
        val rules = parseConfig(settings = AppSettings(bypassLan = true)).rules()
        val dnsIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "${XrayConfigFactory.TUN_DNS_V4}/32" in ips &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DNS_TAG
        }
        val subnetBlockIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "172.19.0.0/30" in ips &&
                "fdfe:dcba:9876::/126" in ips &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val privateDirectIdx = rules.indexOfFirst { rule ->
            val ips = rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            "172.16.0.0/12" in ips &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DIRECT_TAG
        }

        assertTrue(dnsIdx >= 0)
        assertTrue(subnetBlockIdx > dnsIdx)
        assertTrue(privateDirectIdx > subnetBlockIdx)
    }

    // ------------------------------------------------------------------ Area A7: Zero probe-in / loopback inbounds

    @Test
    fun t1_a7_01_vpnXrayConfigHasOnlyTunInboundAndZeroProbeOrApiInbounds() {
        // Test both default build AND calling with a synthetic ProbeEndpoint (if parameter still exists)
        val probe = ProbeEndpoint(port = 49152, username = "u", password = "p")
        val config = parseConfig(settings = AppSettings(), probe = probe)

        val inbounds = config.inbounds()
        assertEquals(
            "VPN Xray config must expose ONLY tun-in (zero probe-in/socks/http/api inbounds, F10): $inbounds",
            listOf(XrayConfigFactory.TUN_TAG),
            inbounds.map { it["tag"]?.jsonPrimitive?.content },
        )
        assertFalse(config.containsKey("api"))
        assertFalse(config.containsKey("observatory"))

        val jsonText = config.toString()
        assertFalse("Config JSON must not contain probe-in", jsonText.contains("probe-in"))
        assertFalse("Config JSON must not listen on 127.0.0.1", jsonText.contains("\"listen\""))
    }

    // ------------------------------------------------------------------ Area A8: All 9 Protocols x 6 Transports

    @Test
    fun t1_a8_01_all9SupportedProtocolsProduceValidSingleTunConfigWithContractTags() {
        val all9 = listOf(
            "vless" to ProxySettings.Vless(uuid = uuid),
            "vmess" to ProxySettings.Vmess(uuid = uuid),
            "trojan" to ProxySettings.Trojan(password = "trojan-pass"),
            "shadowsocks" to ProxySettings.Shadowsocks(method = "2022-blake3-aes-256-gcm", password = fakeKey(1)),
            "hysteria2" to ProxySettings.Hysteria2(password = "hy2-pass"),
            "wireguard" to ProxySettings.WireGuard(
                privateKey = fakeKey(2),
                peerPublicKey = fakeKey(3),
                localAddresses = listOf("10.2.0.2/32"),
            ),
            "http" to ProxySettings.Http(username = "u", password = "p"),
            "socks" to ProxySettings.Socks(username = "u", password = "p"),
            "direct" to ProxySettings.Direct,
        )
        assertEquals(9, all9.size)

        for ((label, protoSettings) in all9) {
            val node = sampleNode(id = label, settings = protoSettings)
            assertNull("Expected protocol $label to be supported", XrayConfigFactory.unsupported(node))
            val doc = XrayConfigFactory.build(
                nodes = listOf(node),
                selected = node,
                settings = AppSettings(),
                geoAvailable = false,
            )
            assertEquals(Yumi.NodeTagPrefix + label, doc.pinnedTag)
            val json = Json.parseToJsonElement(doc.json).jsonObject
            val inbounds = json.inbounds()
            assertEquals(listOf(XrayConfigFactory.TUN_TAG), inbounds.map { it["tag"]?.jsonPrimitive?.content })
            assertNull(
                "autoOutboundsInterface must remain unset so VpnService.protect governs outbound sockets",
                inbounds.single()["settings"]?.jsonObject?.get("autoOutboundsInterface"),
            )
            assertEquals(
                Yumi.BalancerTag,
                json["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject["tag"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun t1_a8_02_fullProtocolTransportSecurityMatrixHasZeroLoopbackInbounds() {
        val protocols = listOf(
            "vless" to ProxySettings.Vless(uuid = uuid),
            "vmess" to ProxySettings.Vmess(uuid = uuid),
            "trojan" to ProxySettings.Trojan(password = "pass"),
            "ss" to ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "pass"),
            "socks" to ProxySettings.Socks(username = "u", password = "p"),
            "http" to ProxySettings.Http(username = "u", password = "p"),
        )
        val transports = listOf(
            "tcp" to null,
            "ws" to TransportOptions.WebSocket(path = "/ws", headers = mapOf("Host" to "cdn.example.com")),
            "grpc" to TransportOptions.Grpc(serviceName = "svc"),
            "httpupgrade" to TransportOptions.HttpUpgrade(host = "cdn.example.com", path = "/up"),
            "xhttp" to TransportOptions.Xhttp(path = "/x", host = "cdn.example.com", mode = "auto"),
        )
        val securities = listOf(
            "none" to null,
            "tls" to TlsOptions(serverName = "edge.example.com", fingerprint = "chrome"),
            "reality" to TlsOptions(
                serverName = "decoy.example.com",
                fingerprint = "firefox",
                reality = RealityOptions(publicKey = fakeRealityKey(), shortId = "01ab"),
            ),
        )

        var count = 0
        for ((pName, pSettings) in protocols) {
            for ((tName, tOpts) in transports) {
                for ((sName, tlsOpts) in securities) {
                    if (tlsOpts?.reality != null && tOpts != null) continue
                    val node = sampleNode("$pName-$tName-$sName", pSettings, tlsOpts, tOpts)
                    val config = parseConfig(node = node)
                    val inbounds = config.inbounds()
                    assertEquals(listOf(XrayConfigFactory.TUN_TAG), inbounds.map { it["tag"]?.jsonPrimitive?.content })
                    assertFalse(config.containsKey("api"))
                    count++
                }
            }
        }
        // Plus Hysteria2 (hysteria transport + mandatory tls)
        val hy2 = sampleNode(
            "hy2",
            ProxySettings.Hysteria2(password = "pw", obfsType = "salamander", obfsPassword = "opw"),
            TlsOptions(serverName = "edge.example.com"),
        )
        assertEquals(listOf(XrayConfigFactory.TUN_TAG), parseConfig(hy2).inbounds().map { it["tag"]?.jsonPrimitive?.content })
        count++

        assertTrue("Expected full matrix count >= 60, got $count", count >= 60)
    }

    @Test
    fun t1_a8_03_unsupportedProtocolsAndTransportsCleanlyRejected() {
        val unsupportedNodes = listOf(
            sampleNode("shadowtls", ProxySettings.ShadowTls(password = "p")),
            sampleNode("tuic", ProxySettings.Tuic(uuid = uuid, password = "p")),
            sampleNode("anytls", ProxySettings.AnyTls(password = "p")),
            sampleNode("ssh", ProxySettings.Ssh(user = "root", password = "p")),
            sampleNode("hy1", ProxySettings.Hysteria(auth = "p")),
            sampleNode("raw", ProxySettings.Raw(outbound = "{}", declaredType = "custom")),
            sampleNode("ss-plugin", ProxySettings.Shadowsocks("aes-256-gcm", "p", plugin = "obfs-local")),
            sampleNode("vless-h2", ProxySettings.Vless(uuid), transport = TransportOptions.Http(path = "/h2")),
            sampleNode("vless-quic", ProxySettings.Vless(uuid), transport = TransportOptions.Quic),
        )
        for (node in unsupportedNodes) {
            assertNotNull(
                "Expected XrayConfigFactory.unsupported(${node.id}) to return non-null reason",
                XrayConfigFactory.unsupported(node),
            )
        }
    }

    @Test
    fun t2_a8_01_providerRoutingPreservesSingleTunInAndLoopbackBlockRules() {
        val provider = ProviderProfile(
            rules = listOf(
                ProviderRule(
                    match = kotlinx.serialization.json.buildJsonObject {
                        put("domain", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("domain:example.org"))))
                    },
                    target = ProviderTarget.Direct,
                ),
            ),
            dns = ProviderDns(servers = listOf(JsonPrimitive("https://dns.quad9.net/dns-query"))),
        )
        val config = parseConfig(
            settings = AppSettings(providerRouting = true, bypassLan = true),
            provider = provider,
        )
        assertEquals(listOf(XrayConfigFactory.TUN_TAG), config.inbounds().map { it["tag"]?.jsonPrimitive?.content })
        val rules = config.rules()
        val loopbackIdx = rules.indexOfFirst { rule ->
            "127.0.0.0/8" in rule["ip"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() &&
                rule["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
        }
        val providerIdx = rules.indexOfFirst { rule ->
            "domain:example.org" in rule["domain"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }
        assertTrue("127.0.0.0/8 block must exist when providerRouting=true", loopbackIdx >= 0)
        assertTrue("Provider rule must come AFTER loopback block", providerIdx > loopbackIdx)
    }

    @Test
    fun t2_a8_02_allRoutingModesWithBlockQuicAndBlockAdsKeepHardenedInboundsAndRules() {
        for (mode in RoutingMode.entries) {
            val config = parseConfig(
                settings = AppSettings(routingMode = mode, blockQuic = true, blockAds = true),
                geoAvailable = true,
            )
            assertEquals(listOf(XrayConfigFactory.TUN_TAG), config.inbounds().map { it["tag"]?.jsonPrimitive?.content })
            val rules = config.rules()
            assertTrue(
                "Mode $mode must keep 172.19.0.2/32:53 -> dns-out",
                rules.any {
                    "${XrayConfigFactory.TUN_DNS_V4}/32" in it["ip"]?.jsonArray?.map { e -> e.jsonPrimitive.content }.orEmpty() &&
                        it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.DNS_TAG
                },
            )
            assertTrue(
                "Mode $mode must keep 127.0.0.0/8 -> block",
                rules.any {
                    "127.0.0.0/8" in it["ip"]?.jsonArray?.map { e -> e.jsonPrimitive.content }.orEmpty() &&
                        it["outboundTag"]?.jsonPrimitive?.content == XrayConfigFactory.BLOCK_TAG
                },
            )
        }
    }

    @Test
    fun t2_a8_03_insecureTlsStrippedAndUnknownFingerprintNormalizedToChrome() {
        val node = sampleNode(
            id = "insecure-vless",
            tls = TlsOptions(enabled = true, serverName = "edge.example.com", insecure = true, fingerprint = "invalid-fp"),
        )
        val doc = XrayConfigFactory.build(
            nodes = listOf(node),
            selected = node,
            settings = AppSettings(),
            geoAvailable = false,
        )
        assertEquals(listOf(node.name), doc.verificationForced)
        assertFalse("allowInsecure must never be emitted", doc.json.contains("allowInsecure"))
        val json = Json.parseToJsonElement(doc.json).jsonObject
        val outbound = json["outbounds"]!!.jsonArray.first().jsonObject
        val fp = outbound["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject["fingerprint"]?.jsonPrimitive?.content
        assertEquals("chrome", fp)
    }

    @Test
    fun t2_a8_04_wireGuardMultiAddressAndHysteria2FinalMaskOmitAutoOutboundsInterface() {
        val wg = sampleNode(
            id = "wg-warp",
            settings = ProxySettings.WireGuard(
                privateKey = fakeKey(10),
                peerPublicKey = fakeKey(11),
                localAddresses = listOf("10.2.0.2/32", "fd01:5ca1:ab1e::2/128"),
                reserved = listOf(1, 2, 3),
            ),
        )
        val hy2 = sampleNode(
            id = "hy2-full",
            settings = ProxySettings.Hysteria2(
                password = "pw",
                obfsType = "salamander",
                obfsPassword = "opw",
                upMbps = 50,
                downMbps = 100,
            ),
            tls = TlsOptions(serverName = "edge.example.com"),
        )
        for (node in listOf(wg, hy2)) {
            val json = parseConfig(node = node)
            val tun = json.inbounds().single()
            assertEquals(XrayConfigFactory.TUN_TAG, tun["tag"]?.jsonPrimitive?.content)
            assertNull(tun["settings"]?.jsonObject?.get("autoOutboundsInterface"))
        }
    }
}
