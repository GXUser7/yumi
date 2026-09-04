package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes one configuration per shape this app can produce, for a core to judge rather than a test.
 *
 * Everything in [XrayConfigFactoryTest] asserts what *this* side believes the schema is, and that
 * belief is exactly what has been wrong before. The documents dumped here are handed to the real
 * binary, built from the revision the app links against:
 *
 * ```bash
 * export GO_TOOLCHAIN=go1.26; source /e/Projects/.tools/env.sh; export GOTOOLCHAIN=local
 * (cd core-xray && go build -o /tmp/xray-cli.exe github.com/xtls/xray-core/main)
 * cd app/build/xray-configs && for c in *.json; do /tmp/xray-cli.exe run -test -c "$c" || echo "FAILED $c"; done
 * ```
 *
 * Building the CLI from `core-xray` rather than cloning the repository is deliberate: the module
 * pins the revision, so the binary and the `.aar` cannot drift apart.
 *
 * Geo rules are left out of every document here. Xray resolves `geosite:`/`geoip:` while parsing
 * and rejects the whole configuration when the databases are missing, so a dump with them would
 * test whether this machine has a 23 MB file rather than whether the schema is right.
 */
class XrayConfigDumpTest {

    private val outputDir = File("build/xray-configs")

    /**
     * A key of the right shape, built rather than written down.
     *
     * WireGuard and Shadowsocks-2022 both want exactly thirty-two bytes of base64, and a literal of
     * that shape in a public repository is indistinguishable from a real key — to a reader, and to
     * the secret scanners watching the repository, which flagged the version of this file that
     * spelled them out. Generating them says plainly that there is nothing here to leak.
     */
    private fun fakeKey(seed: Int): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

    /** REALITY wants raw URL-safe base64 without padding; same reasoning as [fakeKey]. */
    private fun fakeRealityKey(): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { (it * 7).toByte() })


    private fun node(
        id: String,
        settings: ProxySettings,
        server: String = "example.com",
        port: Int = 443,
        tls: TlsOptions? = null,
        transport: TransportOptions? = null,
    ) = ProxyNode(
        id = id,
        name = id,
        server = server,
        port = port,
        settings = settings,
        tls = tls,
        transport = transport,
    )

    private fun dump(name: String, document: XrayConfigFactory.Document) {
        outputDir.mkdirs()
        File(outputDir, "$name.json").writeText(document.json)
    }

    @Test
    fun `dump every shape the factory can produce`() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val reality = TlsOptions(
            serverName = "decoy.example.com",
            fingerprint = "chrome",
            reality = RealityOptions(
                publicKey = fakeRealityKey(),
                shortId = "0123456789abcdef",
                spiderX = "/",
            ),
        )
        val tls = TlsOptions(serverName = "example.com", alpn = listOf("h2", "http/1.1"))

        val nodes = listOf(
            "vless-reality-vision" to node(
                "vless-reality-vision",
                ProxySettings.Vless(uuid = uuid, flow = "xtls-rprx-vision"),
                tls = reality,
            ),
            "vless-ws-tls" to node(
                "vless-ws-tls",
                ProxySettings.Vless(uuid = uuid),
                tls = tls,
                transport = TransportOptions.WebSocket(
                    path = "/ws",
                    headers = mapOf("Host" to "cdn.example.com"),
                ),
            ),
            "vless-xhttp" to node(
                "vless-xhttp",
                ProxySettings.Vless(uuid = uuid),
                tls = tls,
                transport = TransportOptions.Xhttp(path = "/x", host = "cdn.example.com", mode = "stream-up"),
            ),
            "vless-grpc" to node(
                "vless-grpc",
                ProxySettings.Vless(uuid = uuid),
                tls = tls,
                transport = TransportOptions.Grpc(serviceName = "grpc", permitWithoutStream = true),
            ),
            "vless-httpupgrade" to node(
                "vless-httpupgrade",
                ProxySettings.Vless(uuid = uuid),
                tls = tls,
                transport = TransportOptions.HttpUpgrade(host = "cdn.example.com", path = "/up"),
            ),
            "vmess" to node("vmess", ProxySettings.Vmess(uuid = uuid, alterId = 0, security = "auto"), tls = tls),
            "trojan" to node("trojan", ProxySettings.Trojan(password = "trojan-password"), tls = tls),
            "shadowsocks" to node(
                "shadowsocks",
                ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "ss-password"),
            ),
            "shadowsocks-2022" to node(
                "shadowsocks-2022",
                ProxySettings.Shadowsocks(
                    method = "2022-blake3-aes-256-gcm",
                    password = fakeKey(0),
                ),
            ),
            "hysteria2" to node(
                "hysteria2",
                ProxySettings.Hysteria2(
                    password = "hy2-password",
                    obfsType = "salamander",
                    obfsPassword = "obfs-password",
                    upMbps = 50,
                    downMbps = 200,
                ),
                port = 8443,
            ),
            "wireguard" to node(
                "wireguard",
                ProxySettings.WireGuard(
                    privateKey = fakeKey(1),
                    peerPublicKey = fakeKey(2),
                    localAddresses = listOf("10.2.0.2/32"),
                    mtu = 1408,
                    reserved = listOf(1, 2, 3),
                ),
                server = "wg.example.com",
                port = 51820,
            ),
            "socks" to node(
                "socks",
                ProxySettings.Socks(username = "user", password = "pass"),
                port = 1080,
            ),
            "http-proxy" to node(
                "http-proxy",
                ProxySettings.Http(username = "user", password = "pass"),
                port = 8080,
            ),
        )

        // One document per protocol, so a core that refuses one names it.
        nodes.forEach { (name, n) ->
            dump(
                name,
                XrayConfigFactory.build(
                    nodes = listOf(n),
                    selected = n,
                    settings = AppSettings(),
                    geoAvailable = false,
                ),
            )
        }

        // And one holding all of them at once, which is what a real subscription looks like and the
        // only shape that exercises the balancer having more than one candidate.
        val all = nodes.map { it.second }
        dump(
            "all-nodes-balanced",
            XrayConfigFactory.build(all, all.first(), AppSettings(), geoAvailable = false),
        )

        val settingsMatrix = listOf(
            "mode-global" to AppSettings(routingMode = RoutingMode.Global),
            "mode-direct" to AppSettings(routingMode = RoutingMode.Direct),
            "mode-rules-no-geo" to AppSettings(routingMode = RoutingMode.Rules),
            "dns-doh-by-name" to AppSettings(remoteDns = "https://dns.example.com/dns-query"),
            "dns-plain-udp" to AppSettings(remoteDns = "1.1.1.1", directDns = "8.8.8.8"),
            "dns-local-doh" to AppSettings(remoteDns = "https+local://1.1.1.1/dns-query"),
            "no-hijack-no-lan" to AppSettings(hijackDns = false, bypassLan = false),
            "block-quic" to AppSettings(blockQuic = true),
            "ipv6" to AppSettings(enableIpv6 = true),
            "log-debug" to AppSettings(logLevel = LogLevel.Debug),
            "mtu-1500" to AppSettings(mtu = 1500),
        )
        val reference = nodes.first().second
        settingsMatrix.forEach { (name, s) ->
            dump(
                name,
                XrayConfigFactory.build(
                    nodes = listOf(reference),
                    selected = reference,
                    settings = s,
                    geoAvailable = false,
                ),
            )
        }

        dump(
            "with-probe",
            XrayConfigFactory.build(
                nodes = listOf(reference),
                selected = reference,
                settings = AppSettings(),
                probe = ProbeEndpoint(port = 10808, username = "probe", password = "probe-password"),
                geoAvailable = false,
            ),
        )

        val written = outputDir.listFiles { f -> f.extension == "json" }?.size ?: 0
        assertTrue("nothing was dumped", written >= nodes.size + settingsMatrix.size)
    }
}
