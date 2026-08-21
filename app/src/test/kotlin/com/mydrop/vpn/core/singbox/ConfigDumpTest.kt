package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.parse.ProxyUriParser
import org.junit.Test
import java.io.File

/**
 * Writes representative configurations to `build/singbox-configs/` so they can be validated
 * with the real `sing-box check` binary. A JSON document that parses in Kotlin can still be
 * rejected by the core, and only the core is authoritative about its own schema.
 */
class ConfigDumpTest {

    private val outputDir = File("build/singbox-configs").apply { mkdirs() }

    /**
     * The real bundled assets, not a placeholder. `sing-box check` opens every local rule-set it
     * is pointed at, so validating against a fake path would only prove the JSON parses.
     */
    private val ruleSetDir = File("src/main/assets/rule-sets").absolutePath

    private fun dump(
        name: String,
        uri: String,
        settings: AppSettings,
        probe: ProbeEndpoint? = null,
    ) {
        val node = requireNotNull(ProxyUriParser.parse(uri)) { "unparsable: $uri" }
        File(outputDir, "$name.json")
            .writeText(SingBoxConfigFactory.build(node, settings, ruleSetDir, probe))
    }

    @Test
    fun `dump configurations for external validation`() {
        dump(
            "vless-reality",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443" +
                "?security=reality&sni=www.microsoft.com&fp=chrome" +
                "&pbk=xR8LmN2pQvT7yZ4aB6cD9eF1gH3jK5lM7nP9qR2sT4U&sid=a1b2c3d4" +
                "&type=tcp&flow=xtls-rprx-vision#reality",
            AppSettings(),
        )
        dump(
            "vless-ws-global",
            "vless://11111111-2222-3333-4444-555555555555@nl.example.com:8443" +
                "?security=tls&type=ws&path=%2Fws&host=nl.example.com&fp=firefox#ws",
            AppSettings(routingMode = RoutingMode.Global, blockAds = true, enableIpv6 = true),
        )
        dump(
            "trojan-grpc-split",
            "trojan://password@fi.example.com:443?sni=fi.example.com&type=grpc" +
                "&serviceName=tg#grpc",
            AppSettings(
                splitTunnelMode = SplitTunnelMode.AllowList,
                splitTunnelPackages = setOf("com.android.chrome"),
            ),
        )
        dump(
            "hysteria2",
            "hysteria2://password@se.example.com:443?sni=se.example.com" +
                "&obfs=salamander&obfs-password=obfspass#hy2",
            AppSettings(),
        )
        dump(
            "brawl-stars",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443" +
                "?security=tls&sni=de.example.com#brawl",
            AppSettings(brawlStarsMode = true, blockAds = true),
        )
        dump(
            "tuic",
            "tuic://11111111-2222-3333-4444-555555555555:password@jp.example.com:443" +
                "?sni=jp.example.com&congestion_control=bbr&alpn=h3#tuic",
            AppSettings(routingMode = RoutingMode.Direct),
        )
        dump(
            "shadowsocks",
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@us.example.com:8388#ss",
            AppSettings(hijackDns = false, bypassLan = false),
        )
        dump(
            "vmess-ws",
            "vmess://eyJ2IjoiMiIsInBzIjoidm1lc3MiLCJhZGQiOiJmci5leGFtcGxlLmNvbSIsInBvcnQiOiI0" +
                "NDMiLCJpZCI6IjExMTExMTExLTIyMjItMzMzMy00NDQ0LTU1NTU1NTU1NTU1NSIsImFpZCI6IjAi" +
                "LCJzY3kiOiJhdXRvIiwibmV0Ijoid3MiLCJob3N0IjoiZnIuZXhhbXBsZS5jb20iLCJwYXRoIjoi" +
                "L3ZtIiwidGxzIjoidGxzIn0=",
            AppSettings(),
        )
        dump(
            "anytls",
            "anytls://password@uk.example.com:443?sni=uk.example.com#anytls",
            AppSettings(),
        )
        // With the speed test's loopback inbound. An inbound the core refuses to parse fails the
        // whole tunnel rather than the measurement, so this shape has to pass `sing-box check`
        // like every other one here.
        dump(
            "probe-inbound",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#probe",
            AppSettings(),
            ProbeEndpoint(port = 41234, username = "probe-user", password = "probe-secret"),
        )
        // Emptied DNS fields, which is what a half-finished edit in settings used to persist.
        // sing-box rejects `"server": ""` outright, so this case has to survive `sing-box check`
        // exactly like the rest.
        dump(
            "blank-dns",
            "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?security=tls#blank",
            AppSettings(remoteDns = "", directDns = ""),
        )
    }
}
