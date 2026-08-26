package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a chosen server into a document Xray-core will accept.
 *
 * The counterpart to `SingBoxConfigFactory`, and deliberately not a translation of it. The two
 * cores disagree about more than field names, and the differences that matter are the ones that do
 * not fail loudly:
 *
 *  - **The tunnel is not described here.** sing-box is told its addresses, routes and per-app rules
 *    and asks the platform to open a matching interface. Xray is handed a descriptor and asks
 *    nothing, so addresses, routes, DNS servers and split tunnelling all move to
 *    `VpnService.Builder` on the Kotlin side, and the only thing left in this document is the MTU.
 *
 *  - **`autoOutboundsInterface` is never set,** and that is load-bearing rather than an omission.
 *    Setting it makes the TUN inbound register a second dialer controller which binds every socket
 *    with `SO_BINDTODEVICE` (`proxy/tun/handler.go:92-120`) — a call that needs `CAP_NET_RAW`, which
 *    an unprivileged Android app does not have. Left empty the whole block is skipped, and outbound
 *    sockets are protected by the controller the binding installs instead, which is what
 *    `VpnService.protect` is for.
 *
 *  - **`encryption` is mandatory on every VLESS user** (`infra/conf/vless.go:371-373`). sing-box has
 *    no such field at all, so a document ported field-for-field is rejected outright.
 *
 * Anything the core cannot speak throws here rather than quietly degrading. That rule was learned
 * the expensive way: a transport dropped instead of refused produced servers that connected, showed
 * a plausible latency and carried nothing.
 */
object XrayConfigFactory {

    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    const val DNS_TAG = "dns-out"
    const val DNS_IN_TAG = "dns-in"
    const val TUN_TAG = "tun-in"
    const val PROBE_TAG = "probe-in"

    private val json = Json { prettyPrint = true }

    fun build(
        node: ProxyNode,
        settings: AppSettings,
        probe: ProbeEndpoint? = null,
        dnsOverride: String? = null,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildConfig(node, settings.withDnsOverride(dnsOverride), probe),
    )

    /** See the sing-box factory for why the override lands on whichever server actually answers. */
    private fun AppSettings.withDnsOverride(override: String?): AppSettings = when {
        override.isNullOrBlank() -> this
        routingMode == RoutingMode.Direct -> copy(directDns = override)
        else -> copy(remoteDns = override)
    }

    private fun buildConfig(
        node: ProxyNode,
        settings: AppSettings,
        probe: ProbeEndpoint?,
    ): JsonObject = buildJsonObject {
        putJsonObject("log") { put("loglevel", settings.logLevel.toXrayLevel()) }
        put("dns", buildDns(settings))

        putJsonArray("inbounds") {
            add(buildTunInbound(settings))
            probe?.let { add(buildProbeInbound(it)) }
        }

        putJsonArray("outbounds") {
            // First is the default: an outbound tag that no rule names still has to go somewhere,
            // and in this app that somewhere is always the proxy.
            add(buildOutbound(node))
            addJsonObject {
                put("tag", DIRECT_TAG)
                put("protocol", "freedom")
                putJsonObject("settings") { put("domainStrategy", "UseIP") }
            }
            addJsonObject {
                put("tag", BLOCK_TAG)
                put("protocol", "blackhole")
            }
            addJsonObject {
                put("tag", DNS_TAG)
                put("protocol", "dns")
            }
        }

        put("routing", buildRouting(settings))
    }

    // ------------------------------------------------------------------ Inbounds

    /**
     * Everything that described the interface has moved to the Kotlin side; what is left is the one
     * number the core cannot derive from a descriptor it was simply handed.
     *
     * The descriptor itself is not in this document either. Xray reads it from the environment
     * variable `xray.tun.fd` while the inbound is being constructed
     * (`proxy/tun/tun_android.go:26-28`), which the binding sets before the config is parsed.
     */
    private fun buildTunInbound(settings: AppSettings): JsonObject = buildJsonObject {
        put("tag", TUN_TAG)
        put("protocol", "tun")
        putJsonObject("settings") {
            put("mtu", settings.mtu)
            // Deliberately absent: autoOutboundsInterface. See the class comment — leaving it empty
            // is what keeps the core off SO_BINDTODEVICE and on VpnService.protect.
        }
        putJsonObject("sniffing") {
            put("enabled", true)
            // Without a sniffed domain the routing rules below can only match addresses, and every
            // `geosite:` rule silently stops applying to anything that arrives as an IP.
            putJsonArray("destOverride") {
                add("http")
                add("tls")
                add("quic")
            }
            // Sniff in order to route, but leave the destination alone. Rewriting it to the sniffed
            // name breaks connections to any host whose certificate does not match the name it was
            // dialled by, which is most of what sits behind a CDN.
            put("routeOnly", true)
        }
    }

    /**
     * The loopback inbound the app's own speed test dials; see `ProbeEndpoint` for why it exists.
     *
     * `listen` is deliberately `127.0.0.1` rather than `0.0.0.0`: bound to every interface this
     * would be an open proxy for the whole network the phone is on. The credentials close the same
     * hole for other applications on the device itself.
     */
    private fun buildProbeInbound(probe: ProbeEndpoint): JsonObject = buildJsonObject {
        put("tag", PROBE_TAG)
        put("protocol", "socks")
        put("listen", "127.0.0.1")
        put("port", probe.port)
        putJsonObject("settings") {
            put("auth", "password")
            putJsonArray("accounts") {
                addJsonObject {
                    put("user", probe.username)
                    put("pass", probe.password)
                }
            }
            put("udp", true)
        }
    }

    // ------------------------------------------------------------------ Outbounds

    private fun buildOutbound(node: ProxyNode): JsonObject = buildJsonObject {
        put("tag", PROXY_TAG)
        put("protocol", node.settings.xrayProtocol())
        put("settings", node.settings.xraySettings(node))
        buildStream(node)?.let { put("streamSettings", it) }
    }

    private fun ProxySettings.xrayProtocol(): String = when (this) {
        is ProxySettings.Vless -> "vless"
        is ProxySettings.Vmess -> "vmess"
        is ProxySettings.Trojan -> "trojan"
        is ProxySettings.Shadowsocks -> "shadowsocks"
        is ProxySettings.Hysteria2 -> "hysteria"
        is ProxySettings.Http -> "http"
        is ProxySettings.Socks -> "socks"
        is ProxySettings.Direct -> "freedom"
        // Not a gap to be filled in later: these are protocols Xray does not implement at all, and
        // a node using one has to disappear at import rather than become a broken outbound here.
        else -> throw IllegalArgumentException("Xray не умеет " + protocol.name.lowercase())
    }

    private fun ProxySettings.xraySettings(node: ProxyNode): JsonObject = when (this) {
        is ProxySettings.Vless -> buildJsonObject {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", uuid)
                            if (flow.isNotBlank()) put("flow", flow)
                            // Mandatory, and the one field with no sing-box counterpart at all.
                            put("encryption", "none")
                        }
                    }
                }
            }
        }

        is ProxySettings.Vmess -> buildJsonObject {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", uuid)
                            put("security", security)
                            if (alterId != 0) put("alterId", alterId)
                        }
                    }
                }
            }
        }

        is ProxySettings.Trojan -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    put("password", password)
                }
            }
        }

        is ProxySettings.Shadowsocks -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    put("method", method)
                    put("password", password)
                }
            }
        }

        // Hysteria2 is split across two objects in Xray where sing-box has one: the credential is
        // repeated in the transport below, and `version` has to say 2 in both places or the core
        // refuses the whole document (`infra/conf/transport_method.go:776-778`).
        is ProxySettings.Hysteria2 -> buildJsonObject {
            put("version", 2)
            put("address", node.address)
            put("port", node.port)
            putJsonArray("users") {
                addJsonObject { put("auth", password) }
            }
        }

        is ProxySettings.Http -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    if (username.isNotBlank()) {
                        putJsonArray("users") {
                            addJsonObject {
                                put("user", username)
                                put("pass", password)
                            }
                        }
                    }
                }
            }
        }

        is ProxySettings.Socks -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.address)
                    put("port", node.port)
                    if (username.isNotBlank()) {
                        putJsonArray("users") {
                            addJsonObject {
                                put("user", username)
                                put("pass", password)
                            }
                        }
                    }
                }
            }
        }

        is ProxySettings.Direct -> buildJsonObject { }

        else -> throw IllegalArgumentException("Xray не умеет " + protocol.name.lowercase())
    }

    // ------------------------------------------------------------------ Stream

    private fun buildStream(node: ProxyNode): JsonObject? {
        val network = node.xrayNetwork()
        val tls = node.tls
        val secured = tls != null && tls.enabled
        if (network == null && !secured) return null

        return buildJsonObject {
            network?.let { put("network", it) }
            when {
                tls == null || !tls.enabled -> put("security", "none")
                tls.isReality -> {
                    put("security", "reality")
                    put("realitySettings", buildReality(tls, node))
                }
                else -> {
                    put("security", "tls")
                    put("tlsSettings", buildTls(tls, node))
                }
            }
            node.transport?.let { transport ->
                transportSettings(transport)?.let { put(transport.xraySettingsKey(), it) }
            }
            val settings = node.settings
            if (settings is ProxySettings.Hysteria2) {
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    put("auth", settings.password)
                }
            }
        }
    }

    /**
     * The `network` value, or null when the node rides plain TCP and the field can be left out.
     *
     * Hysteria2 is a transport here as well as a protocol, which is the shape Xray chose rather
     * than a quirk of this code.
     */
    private fun ProxyNode.xrayNetwork(): String? = when (settings) {
        is ProxySettings.Hysteria2 -> "hysteria"
        else -> when (val t = transport) {
            null -> null
            is TransportOptions.WebSocket -> "ws"
            is TransportOptions.Grpc -> "grpc"
            is TransportOptions.HttpUpgrade -> "httpupgrade"
            is TransportOptions.Xhttp -> "xhttp"
            // Removed from Xray outright, both of them, with an explicit "removed feature" error
            // from the core (`infra/conf/transport_internet.go:33-36`). Refused here so the node
            // never reaches a core that would reject the entire document because of it.
            is TransportOptions.Http -> throw IllegalArgumentException("Xray убрал транспорт http/h2")
            TransportOptions.Quic -> throw IllegalArgumentException("Xray убрал транспорт quic")
            else -> throw IllegalArgumentException("неизвестный транспорт " + t.label)
        }
    }

    private fun TransportOptions.xraySettingsKey(): String = when (this) {
        is TransportOptions.WebSocket -> "wsSettings"
        is TransportOptions.Grpc -> "grpcSettings"
        is TransportOptions.HttpUpgrade -> "httpupgradeSettings"
        is TransportOptions.Xhttp -> "xhttpSettings"
        else -> throw IllegalArgumentException("нет настроек транспорта для " + label)
    }

    private fun transportSettings(transport: TransportOptions): JsonObject? = when (transport) {
        is TransportOptions.WebSocket -> buildJsonObject {
            put("path", transport.path)
            transport.headers["Host"]?.let { put("host", it) }
            if (transport.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    transport.headers.forEach { (name, value) -> put(name, value) }
                }
            }
        }

        is TransportOptions.Grpc -> buildJsonObject {
            put("serviceName", transport.serviceName)
            if (transport.permitWithoutStream) put("permit_without_stream", true)
        }

        is TransportOptions.HttpUpgrade -> buildJsonObject {
            put("path", transport.path)
            if (transport.host.isNotBlank()) put("host", transport.host)
            if (transport.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    transport.headers.forEach { (name, value) -> put(name, value) }
                }
            }
        }

        is TransportOptions.Xhttp -> buildJsonObject {
            put("path", transport.path)
            if (transport.host.isNotBlank()) put("host", transport.host)
            // Left out when the link did not name one: the core picks between packet-up, stream-up
            // and stream-one itself, and guessing here would override a server that knows better.
            if (transport.mode.isNotBlank() && transport.mode != "auto") put("mode", transport.mode)
            if (transport.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    transport.headers.forEach { (name, value) -> put(name, value) }
                }
            }
        }

        else -> null
    }

    private fun buildTls(tls: TlsOptions, node: ProxyNode): JsonObject = buildJsonObject {
        put("serverName", tls.serverName ?: node.address)
        if (tls.insecure) put("allowInsecure", true)
        if (tls.alpn.isNotEmpty()) {
            putJsonArray("alpn") { tls.alpn.forEach { add(it) } }
        }
        tls.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
    }

    private fun buildReality(tls: TlsOptions, node: ProxyNode): JsonObject = buildJsonObject {
        val reality = requireNotNull(tls.reality)
        put("serverName", tls.serverName ?: node.address)
        put("publicKey", reality.publicKey)
        if (reality.shortId.isNotBlank()) put("shortId", reality.shortId)
        if (reality.spiderX.isNotBlank()) put("spiderX", reality.spiderX)
        // REALITY without a fingerprint presents Go's own ClientHello, which is precisely the
        // signature the whole exercise exists to avoid.
        put("fingerprint", tls.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
    }

    // ------------------------------------------------------------------ DNS and routing

    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            when (settings.routingMode) {
                RoutingMode.Direct -> add(settings.directDns)
                else -> {
                    add(settings.remoteDns)
                    add(settings.directDns)
                }
            }
        }
        put("queryStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
        put("disableCache", false)
        put("tag", DNS_IN_TAG)
    }

    private fun buildRouting(settings: AppSettings): JsonObject = buildJsonObject {
        put("domainStrategy", "IPIfNonMatch")
        putJsonArray("rules") {
            // Queries the core makes on its own behalf go out directly. Sending them through a
            // proxy that is not up yet is how a tunnel deadlocks on its own name resolution.
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(DNS_IN_TAG) }
                put("outboundTag", DIRECT_TAG)
            }

            if (settings.hijackDns) {
                addJsonObject {
                    put("type", "field")
                    put("port", "53")
                    put("outboundTag", DNS_TAG)
                }
            }

            // The probe measures the tunnel, so it has to ride it whatever the routing mode says.
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(PROBE_TAG) }
                put("outboundTag", PROXY_TAG)
            }

            if (settings.blockQuic) {
                addJsonObject {
                    put("type", "field")
                    put("port", "443")
                    put("network", "udp")
                    put("outboundTag", BLOCK_TAG)
                }
            }

            if (settings.bypassLan) {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("ip") { add("geoip:private") }
                    put("outboundTag", DIRECT_TAG)
                }
            }

            if (settings.blockAds) {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("domain") { add("geosite:category-ads-all") }
                    put("outboundTag", BLOCK_TAG)
                }
            }

            when (settings.routingMode) {
                RoutingMode.Global -> Unit
                RoutingMode.Direct -> addJsonObject {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", DIRECT_TAG)
                }
                RoutingMode.Rules -> {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { add("geosite:category-ru") }
                        put("outboundTag", DIRECT_TAG)
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { add("geoip:ru") }
                        put("outboundTag", DIRECT_TAG)
                    }
                }
            }
        }
    }

    private fun LogLevel.toXrayLevel(): String = when (this) {
        LogLevel.Trace -> "debug"
        LogLevel.Debug -> "debug"
        LogLevel.Info -> "info"
        LogLevel.Warn -> "warning"
        LogLevel.Error -> "error"
    }
}
