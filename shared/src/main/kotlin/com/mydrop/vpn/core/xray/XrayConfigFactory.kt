package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import com.mydrop.vpn.core.net.isNumericAddress
import com.mydrop.vpn.core.net.splitHostPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns the user's servers into a document Xray-core will accept.
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
 *    with `SO_BINDTODEVICE` (`proxy/tun/handler.go`) — a call that needs `CAP_NET_RAW`, which an
 *    unprivileged Android app does not have. Left empty the whole block is skipped, and outbound
 *    sockets are protected by the controller the binding installs instead, which is what
 *    `VpnService.protect` is for.
 *
 *  - **`encryption` is mandatory on every VLESS user** (`infra/conf/vless.go`). sing-box has no
 *    such field at all, so a document ported field-for-field is rejected outright.
 *
 *  - **Every field belongs to its protocol.** Xray parses each outbound with unknown fields
 *    disallowed, so one field the protocol does not declare refuses the *whole document* rather
 *    than the one outbound. Nothing here is therefore emitted above the `when` that decides the
 *    protocol. The sing-box factory next door does the opposite — it writes `server` and
 *    `server_port` before it knows what it is building — and a `direct` node there stops that core
 *    from starting at all, which is where this rule was paid for.
 *
 * Anything the core cannot speak is refused rather than quietly degraded — see [unsupported].
 * That rule was learned the expensive way too: a transport dropped instead of refused produced
 * servers that connected, showed a plausible latency and carried nothing.
 */
object XrayConfigFactory {

    /**
     * The balancer every routing rule names, and the per-node outbound prefix it selects by.
     *
     * Both are spelled out identically in `core-xray/yumi.go` (`BalancerTag`, `NodeTagPrefix`).
     * The binding matches candidates with `strings.HasPrefix`
     * (`app/proxyman/outbound/outbound.go:177`) and reads the byte counters off the same prefix, so
     * a disagreement between the two files is silent in both directions: no candidates and no
     * counters, with nothing saying why.
     */
    const val BALANCER_TAG = "proxy"
    private const val NODE_TAG_PREFIX = "proxy-"

    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    const val DNS_TAG = "dns-out"
    const val DNS_IN_TAG = "dns-in"
    const val TUN_TAG = "tun-in"
    const val PROBE_TAG = "probe-in"

    /** The outbound tag for a node. Stable, because the node id is. */
    fun nodeTag(nodeId: String): String = NODE_TAG_PREFIX + nodeId

    private val json = Json { prettyPrint = true }

    /**
     * A built configuration and the things the caller cannot read back out of the JSON.
     *
     * [pinnedTag] goes straight to `yumi.Start`, which points the balancer at it before returning:
     * an unpinned balancer chooses for itself, and a core pinned a moment after starting routes
     * whatever it dispatched in between through a server nobody chose.
     */
    data class Document(
        val json: String,
        val pinnedTag: String,
        /** Every node in the document, in the order it appears. What the balancer may move onto. */
        val nodeTags: List<String>,
        /**
         * Names of nodes whose "do not verify the certificate" was dropped on the floor.
         *
         * Xray removed `allowInsecure` in v26.7.28 and the replacement it names is
         * `pinnedPeerCertSha256` / `verifyPeerCertByName` (`infra/conf/transport_security.go:361`)
         * — a pin on one specific certificate, which this app does not have and cannot obtain from
         * a share link. So the flag cannot be honoured, and a node carrying it will fail its
         * handshake if the certificate really was self-signed. Reported so the journal can say
         * that, rather than leaving the user with a server that stopped working after an update.
         */
        val verificationForced: List<String>,
    )

    /**
     * Why this node cannot be part of an Xray configuration, or null when it can.
     *
     * Returns the offending feature rather than a sentence, because the sentence is a string
     * resource and this file has no business knowing the user's language.
     *
     * Called at import as well as here. A node refused at import never reaches the list, which is
     * the only honest outcome: the alternative is a row that looks like the others, pings like the
     * others and carries nothing.
     */
    fun unsupported(node: ProxyNode): String? {
        when (node.settings) {
            is ProxySettings.Vless,
            is ProxySettings.Vmess,
            is ProxySettings.Trojan,
            is ProxySettings.Shadowsocks,
            is ProxySettings.Hysteria2,
            is ProxySettings.WireGuard,
            is ProxySettings.Http,
            is ProxySettings.Socks,
            ProxySettings.Direct,
            -> Unit
            // Present in sing-box, absent from Xray v26.7.28. Checked against the core's own
            // outbound registry (`infra/conf/xray.go`) rather than from memory: `tuic` and
            // `anytls` do not appear anywhere in the tree, and there is no `proxy/ssh`.
            else -> return node.protocol.label
        }
        // Shadowsocks plugins — obfs, v2ray-plugin and the rest — have no client-side
        // implementation in Xray at all, and the difference is not cosmetic: a server expecting an
        // obfuscated stream and a client speaking plain Shadowsocks do not talk. Emitting the node
        // without its plugin would produce exactly the kind of server that dials, measures well and
        // carries nothing, so it is refused instead.
        (node.settings as? ProxySettings.Shadowsocks)
            ?.plugin
            ?.takeIf { it.isNotBlank() }
            ?.let { return "Shadowsocks + $it" }

        return when (node.transport) {
            // Both removed from the core outright, with an explicit "removed feature" error
            // (`infra/conf/transport_internet.go`). A node carrying one would take the whole
            // document down with it.
            is TransportOptions.Http -> "HTTP/2"
            TransportOptions.Quic -> "QUIC"
            else -> null
        }
    }

    /**
     * @param nodes every server the balancer may move onto without the core being rebuilt. The
     *   caller decides what belongs here — the failover group, the mobile list — and anything
     *   [unsupported] rejects is skipped rather than allowed to sink the document.
     * @param selected the server the tunnel starts on. Must be supported; without that there is
     *   nothing to build.
     * @param geoAvailable whether `geoip.dat` and `geosite.dat` are on disk. False keeps every
     *   `geoip:`/`geosite:` reference out of the document, because Xray resolves them while parsing
     *   and one it cannot resolve rejects the whole configuration rather than the single rule
     *   (`common/geodata/geodat_loader.go`). A tunnel that routes everything through the proxy
     *   beats one that will not start.
     */
    fun build(
        nodes: List<ProxyNode>,
        selected: ProxyNode,
        settings: AppSettings,
        probe: ProbeEndpoint? = null,
        dnsOverride: String? = null,
        geoAvailable: Boolean = true,
    ): Document {
        unsupported(selected)?.let {
            throw IllegalArgumentException("Xray cannot carry $it, which ${selected.name} needs")
        }

        // The selected node first, and the rest behind it. Order matters twice: the first outbound
        // is the core's default for anything no rule matched, and while the catch-all rule below
        // means that should never happen, "should never happen" is a poor thing to have pointing at
        // an arbitrary server.
        val usable = buildList {
            add(selected)
            addAll(nodes.filter { it.id != selected.id && unsupported(it) == null })
        }
        val resolved = settings.withDnsOverride(dnsOverride)

        return Document(
            json = json.encodeToString(
                JsonObject.serializer(),
                buildConfig(usable, resolved, probe, geoAvailable),
            ),
            pinnedTag = nodeTag(selected.id),
            nodeTags = usable.map { nodeTag(it.id) },
            verificationForced = usable
                .filter { node ->
                    val tls = node.tls
                    // A node that pins its certificate is not one that asked to skip the check —
                    // it asked to check against a specific certificate, which this core does.
                    tls != null && tls.enabled && tls.insecure && tls.reality == null &&
                        tls.pinnedCertSha256.isBlank() && tls.verifyPeerCertByName.isBlank()
                }
                .map { it.name },
        )
    }

    /** See the sing-box factory for why the override lands on whichever server actually answers. */
    private fun AppSettings.withDnsOverride(override: String?): AppSettings = when {
        override.isNullOrBlank() -> this
        routingMode == RoutingMode.Direct -> copy(directDns = override)
        else -> copy(remoteDns = override)
    }

    private fun buildConfig(
        nodes: List<ProxyNode>,
        settings: AppSettings,
        probe: ProbeEndpoint?,
        geoAvailable: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("log") { put("loglevel", settings.logLevel.toXrayLevel()) }

        // Byte counters, which the core keeps only when asked. Without both of these the outbound
        // handler registers nothing and the speed shown on the connect screen stays at zero with
        // nothing to explain why.
        putJsonObject("stats") { }
        putJsonObject("policy") {
            putJsonObject("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }

        put("dns", buildDns(settings))

        putJsonArray("inbounds") {
            add(buildTunInbound(settings))
            probe?.let { add(buildProbeInbound(it)) }
        }

        putJsonArray("outbounds") {
            nodes.forEach { add(buildOutbound(it)) }
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

        put("routing", buildRouting(settings, geoAvailable))
    }

    // ------------------------------------------------------------------ Inbounds

    /**
     * Everything that described the interface has moved to the Kotlin side; what is left is the one
     * number the core cannot derive from a descriptor it was simply handed.
     *
     * The descriptor itself is not in this document either. Xray reads it from the environment
     * variable `xray.tun.fd` while the inbound is being constructed
     * (`proxy/tun/tun_android.go:27`), which the binding sets before the config is parsed.
     */
    private fun buildTunInbound(settings: AppSettings): JsonObject = buildJsonObject {
        put("tag", TUN_TAG)
        put("protocol", "tun")
        putJsonObject("settings") {
            // Present only to stop the core going looking for a free one. An empty name sends
            // `TunConfig.Build` into `GetAvailableTunName`, which enumerates interfaces through
            // netlink — and Android has refused apps that since 11, so the whole configuration is
            // rejected with "permission denied" before anything else is even looked at.
            //
            // The value itself does nothing here. The interface already exists: it was created by
            // `VpnService.Builder` and handed over as a descriptor, and `AndroidTun` never consults
            // this field. It matters only on the platforms where the core creates the device.
            put("name", "tun0")
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

    /**
     * Every host here is `node.server`, never `node.address`.
     *
     * `ProxyNode.address` is a display string — `"$server:$port"` — and Xray's `Address` accepts
     * any string as a domain name. So `"example.com:443"` in an `address` field is a configuration
     * the core loads without complaint and then cannot resolve: the outbound exists, the tunnel
     * comes up, and nothing crosses it. The check for this is in `XrayConfigFactoryTest`.
     */
    private fun buildOutbound(node: ProxyNode): JsonObject = buildJsonObject {
        put("tag", nodeTag(node.id))
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
        is ProxySettings.WireGuard -> "wireguard"
        is ProxySettings.Http -> "http"
        is ProxySettings.Socks -> "socks"
        is ProxySettings.Direct -> "freedom"
        // Unreachable: [unsupported] is consulted before anything gets this far, and the branch
        // exists so a new settings type cannot be added without the compiler pointing here.
        else -> throw IllegalArgumentException(
            "Xray has no outbound for " + protocol.name.lowercase(),
        )
    }

    private fun ProxySettings.xraySettings(node: ProxyNode): JsonObject = when (this) {
        is ProxySettings.Vless -> buildJsonObject {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", node.server)
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
                    put("address", node.server)
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
                    put("address", node.server)
                    put("port", node.port)
                    put("password", password)
                }
            }
        }

        is ProxySettings.Shadowsocks -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.server)
                    put("port", node.port)
                    put("method", method)
                    put("password", password)
                }
            }
        }

        // Hysteria2 is split across two objects in Xray where sing-box has one, and the split is
        // not where it looks: the credential does NOT live here. The client-side settings are
        // exactly version, address and port (`infra/conf/hysteria.go`) — `users` belongs to the
        // server config, and putting the password there produces an outbound that dials, completes
        // nothing, and reports no configuration error at all. The password goes into
        // `streamSettings.hysteriaSettings.auth`; see [buildStream].
        is ProxySettings.Hysteria2 -> buildJsonObject {
            put("version", 2)
            put("address", node.server)
            put("port", node.port)
        }

        // Works here, and does not on the sing-box side of this app at all: that factory still
        // emits the pre-1.11 shape with `peer_public_key`, a field the pinned core dropped when
        // WireGuard became an `endpoint` rather than an outbound. In Xray it stayed an outbound, so
        // porting the app is also what fixes it.
        is ProxySettings.WireGuard -> buildJsonObject {
            put("secretKey", privateKey)
            if (localAddresses.isNotEmpty()) {
                putJsonArray("address") { localAddresses.forEach { add(it) } }
            }
            if (mtu > 0) put("mtu", mtu)
            // Cloudflare WARP hands out three bytes that have to be echoed back; anything else
            // leaves this empty and the field out.
            if (reserved.isNotEmpty()) {
                putJsonArray("reserved") { reserved.forEach { add(it) } }
            }
            putJsonArray("peers") {
                addJsonObject {
                    put("publicKey", peerPublicKey)
                    if (preSharedKey.isNotBlank()) put("preSharedKey", preSharedKey)
                    put("endpoint", endpointOf(node))
                    // Everything, because this is a full-tunnel client and the routing decision is
                    // made by the rules above rather than by the peer's allowed range.
                    putJsonArray("allowedIPs") {
                        add("0.0.0.0/0")
                        add("::/0")
                    }
                }
            }
        }

        is ProxySettings.Http -> buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", node.server)
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
                    put("address", node.server)
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

        ProxySettings.Direct -> buildJsonObject { }

        else -> throw IllegalArgumentException(
            "Xray has no settings for " + protocol.name.lowercase(),
        )
    }

    /**
     * WireGuard is the one protocol that wants host and port in one string.
     *
     * Bracketed when the host is an IPv6 literal, because the core cannot tell the address's own
     * colons from the separator otherwise — and the failure is a peer that never comes up rather
     * than a parse error that says so.
     */
    private fun endpointOf(node: ProxyNode): String {
        val bare = splitHostPort(node.server)?.host ?: node.server
        return if (bare.contains(':')) "[$bare]:${node.port}" else "$bare:${node.port}"
    }

    // ------------------------------------------------------------------ Stream

    private fun buildStream(node: ProxyNode): JsonObject? {
        // WireGuard carries its own crypto and has no stream layer at all; giving it one is exactly
        // the unknown-field mistake the class comment is about.
        if (node.settings is ProxySettings.WireGuard) return null

        val network = node.xrayNetwork()
        val tls = node.tls
        val hysteria = node.settings as? ProxySettings.Hysteria2
        val secured = tls != null && tls.enabled
        if (network == null && !secured && hysteria == null) return null

        return buildJsonObject {
            network?.let { put("network", it) }
            when {
                // Hysteria2 runs on QUIC and there is no unencrypted form of it. The dialer reads
                // the TLS config out of the stream settings and fails the connection outright with
                // "tls config is nil" when `security` says anything else
                // (`transport/internet/hysteria/dialer.go`), so a link that named no TLS gets it
                // anyway rather than producing a node that cannot dial.
                hysteria != null -> {
                    put("security", "tls")
                    put("tlsSettings", buildTls(tls, node))
                }
                tls == null || !tls.enabled -> put("security", "none")
                tls.reality != null -> {
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
            if (hysteria != null) {
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    // The credential, and the only place the core looks for it.
                    put("auth", hysteria.password)
                }
                buildFinalMask(hysteria)?.let { put("finalmask", it) }
            }
        }
    }

    /**
     * The obfuscator and the congestion controller, which Xray keeps outside the protocol.
     *
     * In a share link and in sing-box, Salamander is a field on the Hysteria2 outbound. Xray has no
     * `obfs` field anywhere: obfuscation is a general packet-masking layer wrapping the raw
     * `net.PacketConn` before QUIC sees it, and Salamander is one of the maskers registered in it
     * (`infra/conf/transport_finalmask.go`). Emitting it as `obfs` would be silently ignored, and
     * the client would speak plain QUIC at a server expecting masked packets — a node that connects
     * and carries nothing.
     *
     * Bandwidth lives here too. The obvious `up`/`down` inside `hysteriaSettings` are read, warned
     * about and discarded; the values the core actually uses are `brutalUp` and `brutalDown`, and
     * they are strings with a unit rather than numbers.
     */
    private fun buildFinalMask(hysteria: ProxySettings.Hysteria2): JsonObject? {
        val obfuscated = hysteria.obfsType.equals("salamander", ignoreCase = true) &&
            hysteria.obfsPassword.isNotBlank()
        val shaped = hysteria.upMbps > 0 || hysteria.downMbps > 0
        if (!obfuscated && !shaped) return null

        return buildJsonObject {
            if (obfuscated) {
                putJsonArray("udp") {
                    addJsonObject {
                        put("type", "salamander")
                        putJsonObject("settings") { put("password", hysteria.obfsPassword) }
                    }
                }
            }
            if (shaped) {
                putJsonObject("quicParams") {
                    // Brutal is the point of declaring a bandwidth at all: it paces to the stated
                    // rate instead of discovering one. Naming a rate and leaving the controller on
                    // its default would make the numbers decorative.
                    put("congestion", "brutal")
                    if (hysteria.upMbps > 0) put("brutalUp", "${hysteria.upMbps}mbps")
                    if (hysteria.downMbps > 0) put("brutalDown", "${hysteria.downMbps}mbps")
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
            // Refused by [unsupported] long before this; the branches exist so the compiler keeps
            // this exhaustive as the model grows.
            is TransportOptions.Http ->
                throw IllegalArgumentException("Xray removed the http/h2 transport")
            TransportOptions.Quic ->
                throw IllegalArgumentException("Xray removed the quic transport")
            else -> throw IllegalArgumentException("unknown transport " + t.label)
        }
    }

    private fun TransportOptions.xraySettingsKey(): String = when (this) {
        is TransportOptions.WebSocket -> "wsSettings"
        is TransportOptions.Grpc -> "grpcSettings"
        is TransportOptions.HttpUpgrade -> "httpupgradeSettings"
        is TransportOptions.Xhttp -> "xhttpSettings"
        else -> throw IllegalArgumentException("no transport settings for " + label)
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
            // Note the two spellings, which are the core's and not a slip here: `serviceName` is
            // camelCase and `permit_without_stream` is snake_case in the same struct
            // (`infra/conf/transport_method.go:578-586`). Writing either the other way round is
            // accepted silently and then ignored.
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

    /**
     * @param tls null when the link named no TLS at all, which Hysteria2 still requires.
     *
     * `allowInsecure` is deliberately absent and cannot be brought back: v26.7.28 answers it with a
     * hard `PrintRemovedFeatureError` (`infra/conf/transport_security.go:361`), so emitting it for
     * one node stops the core from starting for all of them. See [Document.verificationForced] for
     * what happens to a node that asked for it.
     */
    private fun buildTls(tls: TlsOptions?, node: ProxyNode): JsonObject = buildJsonObject {
        put("serverName", tls?.serverName?.takeIf { it.isNotBlank() } ?: node.server)
        if (tls != null && tls.alpn.isNotEmpty()) {
            putJsonArray("alpn") { tls.alpn.forEach { add(it) } }
        }
        tls?.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", safeFingerprint(it)) }
        // The pin, which is what makes a self-signed certificate usable at all now that
        // `allowInsecure` is gone. Emitted verbatim: the core accepts the OpenSSL spelling with
        // colons and checks the length itself, so normalising here could only break a value it
        // would have accepted.
        tls?.pinnedCertSha256?.takeIf { it.isNotBlank() }?.let { put("pinnedPeerCertSha256", it) }
        tls?.verifyPeerCertByName?.takeIf { it.isNotBlank() }?.let { put("verifyPeerCertByName", it) }
    }

    private fun buildReality(tls: TlsOptions, node: ProxyNode): JsonObject = buildJsonObject {
        val reality = requireNotNull(tls.reality)
        put("serverName", tls.serverName?.takeIf { it.isNotBlank() } ?: node.server)
        put("publicKey", reality.publicKey)
        if (reality.shortId.isNotBlank()) put("shortId", reality.shortId)
        if (reality.spiderX.isNotBlank()) put("spiderX", reality.spiderX)
        // REALITY without a fingerprint presents Go's own ClientHello, which is precisely the
        // signature the whole exercise exists to avoid.
        put("fingerprint", safeFingerprint(tls.fingerprint))
    }

    /**
     * A fingerprint the core will accept, substituting `chrome` for anything else.
     *
     * Not tidiness — the alternative is no tunnel. An unknown value is a hard error while the
     * document is being parsed (`infra/conf/transport_security.go:184` for REALITY, `:355` for
     * TLS), and a rejected document is the whole configuration, not the one node that carried the
     * odd spelling. A subscription with forty servers and one typo would otherwise refuse to
     * connect at all, and the message would name a field rather than a server.
     *
     * `unsafe` and `hellogolang` are known to the core and still refused here. The first turns the
     * disguise off, and the second *is* the Go ClientHello that REALITY exists to hide; a share
     * link should not be able to ask for either.
     */
    private fun safeFingerprint(requested: String?): String {
        val wanted = requested?.trim()?.lowercase().orEmpty()
        return if (wanted.isNotEmpty() && wanted in KNOWN_FINGERPRINTS) wanted else "chrome"
    }

    /**
     * What `tls.GetFingerprint` answers to, minus the two that are refused above.
     *
     * Read off the core rather than from documentation, because the list grows with every browser
     * release the maintainers add and a stale copy here would reject fingerprints that work.
     */
    private val KNOWN_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn",
        "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131",
        "hellochrome_133", "helloios_13", "helloios_14", "helloedge_106", "hellosafari_26_3",
        "hello360_11_0", "helloqq_11_1", "hellorandomized", "hellorandomizedalpn",
        "hellorandomizednoalpn", "hellofirefox_auto", "hellofirefox_55", "hellofirefox_56",
        "hellofirefox_63",
    )

    // ------------------------------------------------------------------ DNS

    /**
     * The resolver as Xray will read it, or null when Xray has no transport for that scheme.
     *
     * Read off `app/dns/nameserver.go:47-88`, which is the only list that matters — the schemes are
     * matched there by name and anything unrecognised falls through to being treated as a plain UDP
     * address, so `tls://1.1.1.1` becomes a *hostname* called "tls://1.1.1.1" and the resolver
     * silently answers nothing.
     *
     * Notably absent: `tls://`. DNS-over-TLS has no implementation in this core at all. Present but
     * only in local form: `quic+local://`, which bypasses routing and therefore cannot be sent
     * through the tunnel.
     */
    internal fun xrayResolver(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            // Dispatched through the routing rules, which is what lets [dnsRules] send it through
            // the proxy.
            "" -> trimmed
            "udp" -> trimmed.removePrefix("udp://").takeIf { it.isNotBlank() }
            "https", "h2c", "tcp" -> trimmed
            "https+local", "h2c+local", "tcp+local", "quic+local" -> trimmed
            // No DoT anywhere in the core, and `quic://` exists only as `quic+local://`. Returning
            // null rather than downgrading: quietly turning the user's encrypted resolver into
            // plaintext UDP is a worse answer than saying it cannot be done.
            else -> null
        }
    }

    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        val remote = xrayResolver(settings.remoteDns)
        val direct = xrayResolver(settings.directDns)

        putJsonArray("servers") {
            when (settings.routingMode) {
                RoutingMode.Direct -> direct?.let { add(it) }
                else -> {
                    remote?.let { add(it) }
                    direct?.let { add(it) }
                }
            }
            // A document with no resolver at all leaves the core on its own built-in localhost
            // server, which on Android reads an `/etc/resolv.conf` that does not exist — the same
            // dead end the sing-box factory records at length around DEFAULT_DIRECT_DNS. Better a
            // resolver the user did not pick than a resolver that answers nothing.
            if (remote == null && direct == null) add(FALLBACK_DNS)
        }
        put("queryStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
        put("disableCache", false)
        put("tag", DNS_IN_TAG)
    }

    /**
     * Where DNS goes when the chosen resolver stops answering, and why it is a constant.
     *
     * It has to be numeric, or the fallback would need the very thing that just failed in order to
     * be reached. It has to be somewhere with no relationship to whoever went down. And it has to
     * be the same on every install, because the point of a fallback is that it is already known to
     * work — a second resolver the user picked is a second thing that can be misconfigured.
     *
     * [FALLBACK_REMOTE_DNS_ALT] exists only so that falling back from 1.1.1.1 goes somewhere else.
     */
    const val FALLBACK_REMOTE_DNS = "https://1.1.1.1/dns-query"
    const val FALLBACK_REMOTE_DNS_ALT = "https://8.8.8.8/dns-query"

    /** Used when everything the user chose turned out to be unexpressible; see [buildDns]. */
    private const val FALLBACK_DNS = FALLBACK_REMOTE_DNS

    /** Matches `AppSettings.remoteDns`, and is what [isShippedResolver] compares against. */
    const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"

    /**
     * Not the platform resolver, and that is load-bearing rather than a style choice.
     *
     * The temptation on both cores is to name the system resolver and let Android answer. On
     * sing-box that meant `"local"`, which fell through to a generic Go implementation reading
     * `/etc/resolv.conf`; that file does not exist on Android, its own fallback is `127.0.0.1:53`,
     * and nothing runs a resolver on this device's loopback — so every direct lookup failed
     * outright, on every install. Not a leak: a dead resolver.
     *
     * Xray offers the same trap under a different name. `localhost` in `dns.servers` builds a
     * server backed by Go's `net.DefaultResolver` (`app/dns/nameserver_local.go`), which lands in
     * exactly the same place for exactly the same reason, and additionally cannot follow the DNS of
     * the network the phone moves onto. So the default is a plain address, and the platform
     * resolver stays out of the list until something on this side actually bridges it.
     */
    const val DEFAULT_DIRECT_DNS = "8.8.8.8"

    /**
     * Whether the resolver in force is the one the app ships with rather than one the user chose.
     *
     * The distinction decides whether the watchdog may replace it on its own. Replacing a resolver
     * somebody deliberately configured is not a repair, it is a surprise — and the price of getting
     * it wrong was every open connection on the phone.
     *
     * Matched on the string the tunnel actually queries, so it holds whether the value arrived as
     * the untouched default, as the same URL typed into the field by hand, or as a DNS profile that
     * happens to name it.
     */
    fun isShippedResolver(resolver: String, mode: RoutingMode): Boolean =
        resolver == if (mode == RoutingMode.Direct) DEFAULT_DIRECT_DNS else DEFAULT_REMOTE_DNS

    /**
     * The resolver [build] would put in place of [primary]. Exposed so the journal can name it
     * before the switch happens, rather than leaving the user to infer it from a working tunnel.
     */
    fun fallbackResolver(primary: String): String =
        if (addressOf(primary) == addressOf(FALLBACK_REMOTE_DNS)) {
            FALLBACK_REMOTE_DNS_ALT
        } else {
            FALLBACK_REMOTE_DNS
        }

    /** The bare host of a resolver written either as an address or as a URL. */
    private fun addressOf(value: String): String =
        hostOf(value.trim().ifEmpty { DEFAULT_REMOTE_DNS }).orEmpty()

    // ------------------------------------------------------------------ Routing

    private fun buildRouting(settings: AppSettings, geoAvailable: Boolean): JsonObject =
        buildJsonObject {
            put("domainStrategy", "IPIfNonMatch")

            // The balancer is what makes a server switch a pointer move rather than a rebuilt
            // tunnel. Every rule below names it instead of an outbound, and `yumi.SelectOutbound`
            // pins it — see the tag constants at the top for why both sides spell it out.
            putJsonArray("balancers") {
                addJsonObject {
                    put("tag", BALANCER_TAG)
                    putJsonArray("selector") { add(NODE_TAG_PREFIX) }
                }
            }

            putJsonArray("rules") {
                dnsRules(settings)

                if (settings.hijackDns) {
                    addJsonObject {
                        put("type", "field")
                        put("port", "53")
                        put("outboundTag", DNS_TAG)
                    }
                }

                // The probe measures the tunnel, so it has to ride it whatever the routing mode
                // says.
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add(PROBE_TAG) }
                    put("balancerTag", BALANCER_TAG)
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
                        // Spelled out rather than written as `geoip:private`, so that reaching the
                        // router, the printer or a phone on the same Wi-Fi does not depend on a
                        // twenty-three megabyte database having finished downloading. These ranges
                        // are fixed by the RFCs that reserved them and will not change.
                        putJsonArray("ip") { PRIVATE_RANGES.forEach { add(it) } }
                        put("outboundTag", DIRECT_TAG)
                    }
                }

                if (settings.blockAds && geoAvailable) {
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
                    // Without the databases this is the same as Global: everything goes through the
                    // proxy. Saying so by omission rather than by an unresolvable rule is the whole
                    // point — the alternative is a configuration the core refuses to load.
                    RoutingMode.Rules -> if (geoAvailable) {
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

                // Last, and not optional. The core's default for unmatched traffic is the *first
                // outbound*, which is one specific server — so without this the balancer would be
                // bypassed by everything no earlier rule happened to name.
                addJsonObject {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("balancerTag", BALANCER_TAG)
                }
            }
        }

    /**
     * Where the core's own DNS traffic goes, split three ways rather than sent one way.
     *
     * The prototype sent everything the DNS app dialled out directly, reasoning that a lookup
     * through a tunnel that is not up yet deadlocks on its own name resolution. That is true of
     * exactly one query — the bootstrap for a resolver named by domain — and false of the rest:
     * sending the *encrypted* query out directly hands the ISP the list of names being asked, which
     * under a censor is the thing DoH was chosen to prevent.
     *
     * So:
     *
     *  1. the direct resolver's own address goes direct, because that is what "direct" means;
     *  2. a remote resolver named by domain gets its bootstrap lookup sent direct, because there is
     *     no tunnel to resolve it through yet;
     *  3. everything else the DNS app dials — which is the encrypted query itself — rides the proxy.
     *
     * `https://` is dispatched through the routing rules and `https+local://` is not
     * (`app/dns/nameserver.go:56-62`), so a user who picks the local form opts out of 3 by choosing
     * it, and no rule here can or should override that.
     */
    private fun JsonArrayBuilder.dnsRules(settings: AppSettings) {
        val directHost = xrayResolver(settings.directDns)?.let(::hostOf)
        if (directHost != null) {
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(DNS_IN_TAG) }
                if (isNumericAddress(directHost)) {
                    putJsonArray("ip") { add(directHost) }
                } else {
                    putJsonArray("domain") { add("full:$directHost") }
                }
                put("outboundTag", DIRECT_TAG)
            }
        }

        if (settings.routingMode == RoutingMode.Direct) {
            // Nothing is proxied in this mode, so there is no third case and no bootstrap: every
            // remaining query follows the same road as the traffic it is resolving for.
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(DNS_IN_TAG) }
                put("outboundTag", DIRECT_TAG)
            }
            return
        }

        val remoteHost = xrayResolver(settings.remoteDns)?.let(::hostOf)
        if (remoteHost != null && !isNumericAddress(remoteHost)) {
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(DNS_IN_TAG) }
                putJsonArray("domain") { add("full:$remoteHost") }
                put("outboundTag", DIRECT_TAG)
            }
        }

        addJsonObject {
            put("type", "field")
            putJsonArray("inboundTag") { add(DNS_IN_TAG) }
            put("balancerTag", BALANCER_TAG)
        }
    }

    /** The host a resolver address points at, with scheme, path and port removed. */
    private fun hostOf(resolver: String): String? {
        val withoutScheme = resolver.substringAfter("://", missingDelimiterValue = resolver)
        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        return splitHostPort(authority)?.host?.takeIf { it.isNotBlank() }
    }

    private val PRIVATE_RANGES = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    private fun LogLevel.toXrayLevel(): String = when (this) {
        LogLevel.Trace -> "debug"
        LogLevel.Debug -> "debug"
        LogLevel.Info -> "info"
        LogLevel.Warn -> "warning"
        LogLevel.Error -> "error"
    }
}
