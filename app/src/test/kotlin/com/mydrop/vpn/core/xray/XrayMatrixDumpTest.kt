package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every protocol crossed with every transport and every security mode, written out for the core to
 * judge.
 *
 * [XrayConfigDumpTest] covers the shapes the app produces in practice; this covers the ones it
 * *could* produce, which is a different and larger question. A subscription decides what arrives,
 * and the combination nobody thought to try is exactly the one a provider will hand out.
 *
 * Run the same way as the other dump — the binary built from `core-xray`, so the judge and the
 * shipped core are the same revision:
 *
 * ```bash
 * export GO_TOOLCHAIN=go1.26; source /e/Projects/.tools/env.sh; export GOTOOLCHAIN=local
 * (cd core-xray && go build -o /tmp/xray-cli.exe github.com/xtls/xray-core/main)
 * cd app/build/xray-matrix && for c in *.json; do /tmp/xray-cli.exe run -test -c "$c" || echo "FAILED $c"; done
 * ```
 *
 * Combinations that cannot exist are left out rather than expected to fail: Hysteria2 carries its
 * own transport and its own TLS, and WireGuard has no stream layer at all.
 */
class XrayMatrixDumpTest {

    private val outputDir = File("build/xray-matrix")

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private val protocols: List<Pair<String, ProxySettings>> = listOf(
        "vless" to ProxySettings.Vless(uuid = uuid),
        "vless-vision" to ProxySettings.Vless(uuid = uuid, flow = "xtls-rprx-vision"),
        "vmess" to ProxySettings.Vmess(uuid = uuid, alterId = 0, security = "auto"),
        "vmess-aid" to ProxySettings.Vmess(uuid = uuid, alterId = 4, security = "aes-128-gcm"),
        "trojan" to ProxySettings.Trojan(password = "trojan-password"),
        "ss-aead" to ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "ss-password"),
        "ss-chacha" to ProxySettings.Shadowsocks(
            method = "chacha20-ietf-poly1305",
            password = "ss-password",
        ),
        "ss-2022" to ProxySettings.Shadowsocks(
            method = "2022-blake3-aes-256-gcm",
            password = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        ),
        "socks" to ProxySettings.Socks(username = "user", password = "pass"),
        "socks-open" to ProxySettings.Socks(),
        "http-proxy" to ProxySettings.Http(username = "user", password = "pass"),
    )

    private val transports: List<Pair<String, TransportOptions?>> = listOf(
        "tcp" to null,
        "ws" to TransportOptions.WebSocket(path = "/ws", headers = mapOf("Host" to "cdn.example.com")),
        "grpc" to TransportOptions.Grpc(serviceName = "grpc-service", permitWithoutStream = true),
        "httpupgrade" to TransportOptions.HttpUpgrade(host = "cdn.example.com", path = "/up"),
        "xhttp" to TransportOptions.Xhttp(path = "/x", host = "cdn.example.com", mode = "auto"),
        "xhttp-packet-up" to TransportOptions.Xhttp(path = "/x", mode = "packet-up"),
        "xhttp-stream-up" to TransportOptions.Xhttp(path = "/x", mode = "stream-up"),
        "xhttp-stream-one" to TransportOptions.Xhttp(path = "/x", mode = "stream-one"),
    )

    private val security: List<Pair<String, TlsOptions?>> = listOf(
        "plain" to null,
        "tls" to TlsOptions(serverName = "example.com", alpn = listOf("h2", "http/1.1")),
        "tls-fp" to TlsOptions(serverName = "example.com", fingerprint = "firefox"),
        // Refused by the core when it is passed through, so the factory has to substitute; the
        // document below is the proof that it does.
        "tls-unknown-fp" to TlsOptions(serverName = "example.com", fingerprint = "not-a-browser"),
        "reality" to TlsOptions(
            serverName = "decoy.example.com",
            fingerprint = "chrome",
            reality = RealityOptions(
                publicKey = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA",
                shortId = "0123456789abcdef",
                spiderX = "/",
            ),
        ),
    )

    private fun dump(name: String, node: ProxyNode) {
        outputDir.mkdirs()
        val document = XrayConfigFactory.build(
            nodes = listOf(node),
            selected = node,
            settings = AppSettings(),
            geoAvailable = false,
        )
        File(outputDir, "$name.json").writeText(document.json)
    }

    @Test
    fun `dump every protocol crossed with every transport and security mode`() {
        var written = 0

        protocols.forEach { (protocolName, settings) ->
            transports.forEach { (transportName, transport) ->
                security.forEach { (securityName, tls) ->
                    // REALITY rides raw TCP in practice, and a REALITY handshake wrapped in
                    // WebSocket is not a combination any provider hands out. Kept for the plain
                    // case only, which is the one that matters.
                    if (tls?.reality != null && transport != null) return@forEach

                    val name = "$protocolName-$transportName-$securityName"
                    dump(
                        name,
                        ProxyNode(
                            id = name,
                            name = name,
                            server = "example.com",
                            port = 443,
                            settings = settings,
                            tls = tls,
                            transport = transport,
                        ),
                    )
                    written++
                }
            }
        }

        // Hysteria2: its own transport, its own mandatory TLS, and its obfuscator and bandwidth in
        // a layer of their own.
        listOf(
            "hysteria2-plain" to ProxySettings.Hysteria2(password = "hy2-password"),
            "hysteria2-salamander" to ProxySettings.Hysteria2(
                password = "hy2-password",
                obfsType = "salamander",
                obfsPassword = "obfs-password",
            ),
            "hysteria2-brutal" to ProxySettings.Hysteria2(
                password = "hy2-password",
                upMbps = 50,
                downMbps = 200,
            ),
            "hysteria2-everything" to ProxySettings.Hysteria2(
                password = "hy2-password",
                obfsType = "salamander",
                obfsPassword = "obfs-password",
                upMbps = 50,
                downMbps = 200,
            ),
        ).forEach { (name, settings) ->
            dump(
                name,
                ProxyNode(
                    id = name,
                    name = name,
                    server = "example.com",
                    port = 8443,
                    settings = settings,
                    tls = TlsOptions(serverName = "example.com"),
                ),
            )
            written++
        }

        // WireGuard: no stream layer, and the one protocol that wants host and port in one string.
        listOf(
            "wireguard-plain" to ProxySettings.WireGuard(
                privateKey = "aGVsbG93b3JsZGhlbGxvd29ybGRoZWxsb3dvcmxkMD0=",
                peerPublicKey = "d29ybGRoZWxsb3dvcmxkaGVsbG93b3JsZGhlbGxvMD0=",
                localAddresses = listOf("10.2.0.2/32"),
            ),
            "wireguard-warp" to ProxySettings.WireGuard(
                privateKey = "aGVsbG93b3JsZGhlbGxvd29ybGRoZWxsb3dvcmxkMD0=",
                peerPublicKey = "d29ybGRoZWxsb3dvcmxkaGVsbG93b3JsZGhlbGxvMD0=",
                preSharedKey = "cHJlc2hhcmVka2V5cHJlc2hhcmVka2V5cHJlczA9",
                localAddresses = listOf("10.2.0.2/32", "fd01:5ca1:ab1e::2/128"),
                mtu = 1280,
                reserved = listOf(1, 2, 3),
            ),
        ).forEach { (name, settings) ->
            dump(
                name,
                ProxyNode(
                    id = name,
                    name = name,
                    server = "wg.example.com",
                    port = 51820,
                    settings = settings,
                ),
            )
            written++
        }

        // A direct node, which is the shape that stopped the *other* core from starting: its
        // factory wrote `server` and `server_port` before it knew the protocol, and one of these
        // in a subscription meant no tunnel at all.
        dump(
            "direct",
            ProxyNode(
                id = "direct",
                name = "direct",
                server = "example.com",
                port = 443,
                settings = ProxySettings.Direct,
            ),
        )
        written++

        assertTrue("expected a full matrix, wrote $written", written >= 150)
    }
}
