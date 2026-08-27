package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.parse.ProxyUriParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes representative configurations to `build/xray-configs/` so they can be validated with the
 * real `xray run -test` binary. A JSON document that parses in Kotlin can still be rejected by the
 * core, and only the core is authoritative about its own schema.
 *
 * This matters more here than it did for sing-box. The two cores disagree about field names,
 * nesting and which transports exist at all, and none of those disagreements announce themselves:
 * a document Xray refuses is at least a loud failure, while a document it accepts and misreads is
 * a server that connects and carries nothing.
 *
 * What this cannot catch is anything that only fails on Android. The validating binary runs on a
 * desktop, where the core's platform-specific paths are different ones — an empty TUN interface name
 * sends it looking for a free one through netlink, which desktops allow and Android has refused
 * since 11. That document validated here and was rejected on the phone. So a pass here means the
 * schema is right, not that the tunnel will come up.
 *
 * These files are a side effect, not a declared task output, and Gradle therefore has no idea they
 * exist: an up-to-date or cached test run skips this class and leaves the directory holding
 * whatever it held before. Anything that validates them has to force this class to run first.
 */
class XrayConfigDumpTest {

    private val outputDir = File("build/xray-configs").apply { mkdirs() }

    private fun dump(
        name: String,
        uri: String,
        settings: AppSettings,
        probe: ProbeEndpoint? = null,
        geoAvailable: Boolean = true,
    ) {
        val node = requireNotNull(ProxyUriParser.parse(uri)) { "unparsable: $uri" }
        File(outputDir, "$name.json")
            .writeText(XrayConfigFactory.build(node, settings, probe, geoAvailable = geoAvailable))
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
            "trojan-grpc",
            "trojan://password@fi.example.com:443?sni=fi.example.com&type=grpc" +
                "&serviceName=tg#grpc",
            AppSettings(),
        )
        dump(
            "vless-httpupgrade",
            "vless://11111111-2222-3333-4444-555555555555@it.example.com:443" +
                "?security=tls&type=httpupgrade&path=%2Fhu&host=it.example.com#hu",
            AppSettings(),
        )
        // The reason this port exists. Two of these turned up in a live subscription, were parsed
        // as ordinary VLESS because sing-box has no XHTTP, and produced a server that connected and
        // then failed every request with `reality verification failed`.
        dump(
            "vless-xhttp",
            "vless://11111111-2222-3333-4444-555555555555@br.example.com:443" +
                "?security=tls&type=xhttp&path=%2Fx&host=br.example.com&mode=auto#xhttp",
            AppSettings(),
        )
        dump(
            "hysteria2",
            "hysteria2://password@se.example.com:443?sni=se.example.com#hy2",
            AppSettings(),
        )
        // Salamander, which Xray keeps outside the protocol entirely: a share link calls it obfs,
        // the core knows it only as a packet mask in `finalmask`. Emitted as `obfs` it would be
        // ignored without complaint, and the client would speak plain QUIC at a server expecting
        // masked packets.
        dump(
            "hysteria2-salamander",
            "hysteria2://password@se.example.com:443?sni=se.example.com" +
                "&obfs=salamander&obfs-password=obfspass#hy2obfs",
            AppSettings(),
        )
        // A link that names no TLS at all. Hysteria2 has no unencrypted form, and its dialer fails
        // with "tls config is nil" unless `security` says tls — so the generator has to supply it
        // rather than reproduce the omission.
        dump(
            "hysteria2-no-tls",
            "hysteria2://password@no-sni.example.com:8443#hy2bare",
            AppSettings(),
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
        // With the speed test's loopback inbound. An inbound the core refuses to parse fails the
        // whole tunnel rather than just the measurement, so this shape has to validate like the
        // rest of them.
        dump(
            "probe-inbound",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#probe",
            AppSettings(),
            ProbeEndpoint(port = 41234, username = "probe-user", password = "probe-secret"),
        )
        dump(
            "direct-routing",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#direct",
            AppSettings(routingMode = RoutingMode.Direct),
        )
        dump(
            "rules-routing",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#rules",
            AppSettings(routingMode = RoutingMode.Rules),
        )
        dump(
            "block-quic",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#quic",
            AppSettings(blockQuic = true),
        )
        // An IPv6 resolver, bare and bracketed. Both used to be cut on their last colon under the
        // old core, which produced a document it rejected — so choosing an IPv6 DNS meant a tunnel
        // that would not start at all.
        dump(
            "dns-ipv6",
            "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?security=tls#v6",
            AppSettings(
                remoteDns = "https://[2606:4700:4700::1111]/dns-query",
                directDns = "2620:fe::fe",
            ),
        )

        // The shape a phone has before it has finished downloading the databases. Validated against
        // a core that has no .dat files at all, which is the only way to prove the omission works.
        dump(
            "no-geo",
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls#nogeo",
            AppSettings(routingMode = RoutingMode.Rules, blockAds = true, bypassLan = true),
            geoAvailable = false,
        )

        assertTrue(
            "нечего проверять: каталог конфигов пуст",
            (outputDir.listFiles()?.size ?: 0) >= 12,
        )
    }
}
