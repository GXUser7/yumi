package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.SplitTunnelMode
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.mydrop.vpn.core.net.splitHostPort
import com.mydrop.vpn.core.net.isNumericAddress

/**
 * Builds a sing-box configuration document for one selected server.
 *
 * The schema targets the current sing-box option structs (rule *actions* rather than the
 * removed `block`/`dns` outbounds, and typed DNS servers rather than URL strings). Fields are
 * emitted only when they carry meaning, because sing-box rejects several of them when empty.
 */
object SingBoxConfigFactory {

    private const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"
    private const val PROBE_TAG = "probe-in"
    private const val DNS_REMOTE_TAG = "dns-remote"
    private const val DNS_DIRECT_TAG = "dns-direct"
    private const val DNS_BOOTSTRAP_TAG = "dns-bootstrap"

    /** Fallbacks for a DNS field the user has emptied; they match [AppSettings]' own defaults. */
    const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"
    const val DEFAULT_DIRECT_DNS = "8.8.8.8"

    private const val RULE_SET_GEOSITE_RU = "geosite-ru"
    private const val RULE_SET_GEOIP_RU = "geoip-ru"
    private const val RULE_SET_ADS = "geosite-ads"

    /**
     * Compiled rule-set files, shipped in `assets/rule-sets` and copied to app storage before the
     * core starts. Sourced from SagerNet's `rule-set` branches — note the geosite tag really is
     * `category-ru`; a plain `geosite-ru.srs` does not exist and 404s.
     */
    const val GEOSITE_RU_FILE = "geosite-category-ru.srs"
    const val GEOIP_RU_FILE = "geoip-ru.srs"
    const val ADS_FILE = "geosite-category-ads-all.srs"

    val bundledRuleSets = listOf(GEOSITE_RU_FILE, GEOIP_RU_FILE, ADS_FILE)

    private val json = Json { prettyPrint = true }

    /** uTLS fingerprints sing-box accepts; anything else is coerced to a safe default. */
    private val knownFingerprints = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android",
        "random", "randomized", "chrome_psk", "chrome_pq",
    )

    /**
     * @param ruleSetDir absolute directory holding the extracted `.srs` files from
     *   [bundledRuleSets]. The caller owns extraction, because the core reads these paths at
     *   startup and a missing file fails the whole tunnel rather than one rule.
     */
    /**
     * @param dnsOverride resolver chosen on the DNS screen, which wins over the address typed in
     *   settings. Null leaves the settings value in charge.
     */
    fun build(
        node: ProxyNode,
        settings: AppSettings,
        ruleSetDir: String,
        probe: ProbeEndpoint? = null,
        dnsOverride: String? = null,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildConfig(
            node = node,
            settings = settings.withDnsOverride(dnsOverride),
            ruleSetDir = ruleSetDir,
            probe = probe,
        ),
    )

    /**
     * Puts the chosen resolver where the queries actually go.
     *
     * Which of the two servers that is depends on the routing mode: direct routing resolves through
     * `dns-direct` and never touches `dns-remote`, so overriding only the remote one left a
     * resolver selected in the list and a different one answering — the exact case of wanting a
     * DNS service *without* routing through a proxy, which is what these services are for.
     */
    private fun AppSettings.withDnsOverride(override: String?): AppSettings = when {
        override.isNullOrBlank() -> this
        routingMode == RoutingMode.Direct -> copy(directDns = override)
        else -> copy(remoteDns = override)
    }

    private fun buildConfig(
        node: ProxyNode,
        settings: AppSettings,
        ruleSetDir: String,
        probe: ProbeEndpoint?,
    ): JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("level", settings.logLevel.toSingBoxLevel())
            put("timestamp", true)
        }

        put("dns", buildDns(settings))
        putJsonArray("inbounds") {
            add(buildTunInbound(settings))
            probe?.let { add(buildProbeInbound(it)) }
        }
        putJsonArray("outbounds") {
            add(buildOutbound(node))
            addJsonObject {
                put("type", "direct")
                put("tag", DIRECT_TAG)
            }
        }
        put("route", buildRoute(settings, ruleSetDir, probe))
        put("experimental", buildExperimental())
    }

    /**
     * Turns on the core's traffic accounting.
     *
     * Without a `clash_api` block sing-box never creates its traffic tracker, so every status
     * message comes back with `trafficAvailable = false` and zeroed counters — the speed readout
     * and the flow figure sit still while the tunnel is in fact busy.
     *
     * `external_controller` is deliberately empty: that starts the tracker without opening an HTTP
     * control port. A listening socket that can reconfigure the tunnel is not something to expose
     * on a phone for the sake of a number on screen.
     */
    private fun buildExperimental(): JsonObject = buildJsonObject {
        putJsonObject("clash_api") { put("external_controller", "") }
    }

    // ------------------------------------------------------------------ DNS

    /**
     * The resolvers, plus a bootstrap for whichever of them is named rather than numbered.
     *
     * A resolver written as a hostname — `https://xbox-dns.ru/dns-query`, `tls://dns.adguard.com` —
     * has to be resolved before it can resolve anything, and the core refuses a configuration that
     * does not say how: *"missing domain resolver for domain server address"*. Pointing it at
     * itself is the circle it is refusing. So a plain numeric server is added alongside, used for
     * that one lookup and nothing else.
     *
     * Nothing needed it while both addresses were IPs, which is why the default configuration
     * never hit it — and why choosing a resolver by name did, immediately.
     */
    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        val remoteNamed = !isNumericAddress(addressOf(settings.remoteDns, DEFAULT_REMOTE_DNS))
        val directNamed = !isNumericAddress(addressOf(settings.directDns, DEFAULT_DIRECT_DNS))
        val bootstrap = if (remoteNamed || directNamed) DNS_BOOTSTRAP_TAG else null

        putJsonArray("servers") {
            add(dnsServer(DNS_REMOTE_TAG, settings.remoteDns, PROXY_TAG, bootstrap.takeIf { remoteNamed }))
            // A typed DNS server without a detour already uses the direct dialer. Pointing it
            // at our intentionally empty `direct` outbound is redundant and newer sing-box
            // versions reject that combination while starting the DNS service.
            add(dnsServer(DNS_DIRECT_TAG, settings.directDns, null, bootstrap.takeIf { directNamed }))
            if (bootstrap != null) {
                addJsonObject {
                    put("type", "udp")
                    put("tag", DNS_BOOTSTRAP_TAG)
                    // The user's own direct resolver when it is numeric, so the one lookup that
                    // bootstraps the rest still goes where they pointed it.
                    put(
                        "server",
                        addressOf(settings.directDns, DEFAULT_DIRECT_DNS)
                            .takeIf(::isNumericAddress) ?: DEFAULT_DIRECT_DNS,
                    )
                }
            }
        }

        // No DNS rules here, and that is a decision rather than an omission.
        //
        // Sending geosite-ru names to `dns-direct` looks obviously right: those domains route
        // around the proxy, so resolving them through it hands back a CDN edge chosen for wherever
        // the remote resolver sits. It was tried, and it broke browsing.
        //
        // `dns-direct` is plain UDP on port 53 with no detour, so it leaves through the phone's own
        // connection — which is exactly the traffic Russian DPI throttles and hijacks. The rule
        // therefore moved the majority of everyday lookups off a working DoH-through-the-tunnel
        // path onto one that fails intermittently, and an intermittently failing resolver reads to
        // the user as the internet cutting out.
        //
        // Anything that revisits this has to answer the question that version did not: what
        // resolves these names when the direct resolver is unreachable. Until there is an answer,
        // everything goes through `final` below, which works.

        // No `{"outbound":"any"}` rule here: resolving the proxy's own hostname directly is now
        // expressed as route.default_domain_resolver, and the old rule item is removed in
        // sing-box 1.14.
        put("final", if (settings.routingMode == RoutingMode.Direct) DNS_DIRECT_TAG else DNS_REMOTE_TAG)
        put("strategy", if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only")
    }

    /** Host part of a DNS field, with the scheme, port and path stripped. */
    private fun addressOf(value: String, fallback: String): String {
        val trimmed = value.trim().ifEmpty { fallback }
        val rest = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
        return splitHostPort(rest.substringBefore('/'))?.host.orEmpty()
    }

    /**
     * Accepts the DNS field either as a bare address (`8.8.8.8`) or as a URL
     * (`https://1.1.1.1/dns-query`, `tls://…`, `quic://…`), and emits the typed server object
     * the current schema expects.
     */
    private fun dnsServer(
        tag: String,
        value: String,
        detour: String?,
        domainResolver: String? = null,
    ): JsonObject {
        // A blank field must never reach the core. sing-box rejects an empty address outright
        // ("invalid server address: :53") and the whole tunnel fails to start, which reads to the
        // user as the connection instantly dropping — with the real reason buried in the log.
        val trimmed = value.trim().ifEmpty {
            if (tag == DNS_REMOTE_TAG) DEFAULT_REMOTE_DNS else DEFAULT_DIRECT_DNS
        }
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = if (scheme.isEmpty()) trimmed else trimmed.substringAfter("://")
        val hostPart = rest.substringBefore('/')
        val path = rest.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }

        // Through the shared splitter, which knows an unbracketed IPv6 literal when it sees one.
        // Cutting on the last colon here turned `2606:4700:4700::1111` into server
        // "2606:4700:4700:" on port 1111 — a document the core rejects, leaving the user with a
        // tunnel that refuses to start and no hint that the DNS field was the reason.
        val parsed = splitHostPort(hostPart)
        val host = parsed?.host.orEmpty()
        val port = parsed?.port

        return buildJsonObject {
            put(
                "type",
                when (scheme) {
                    "https" -> "https"
                    "tls" -> "tls"
                    "quic" -> "quic"
                    "h3" -> "h3"
                    "tcp" -> "tcp"
                    "local" -> "local"
                    else -> "udp"
                },
            )
            put("tag", tag)
            if (scheme != "local") {
                put("server", host)
                port?.let { put("server_port", it) }
                if (path.isNotEmpty() && (scheme == "https" || scheme == "h3")) put("path", path)
                detour?.let { put("detour", it) }
                // Only when the address is a name. On a numeric one the field is meaningless, and
                // the core is strict about fields that cannot apply.
                domainResolver?.let { put("domain_resolver", it) }
            }
        }
    }

    // -------------------------------------------------------------- Inbound

    private fun buildTunInbound(settings: AppSettings): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") {
            add("172.19.0.1/30")
            if (settings.enableIpv6) add("fdfe:dcba:9876::1/126")
        }
        put("mtu", settings.mtu)
        // auto_route is meaningless on Android: routes come from VpnService.Builder, which the
        // platform interface drives.
        put("auto_route", true)
        put("strict_route", false)
        // gVisor, not "mixed". "mixed" means the system stack for TCP and gVisor for UDP, and the
        // system stack needs to manipulate real routes — which an unrooted Android app cannot do.
        // The symptom was exact: DNS (UDP, gVisor) resolved through the proxy while every TCP
        // connection stopped at sniffing and never produced an outbound, the failure falling
        // precisely along the boundary between the two halves of the mixed stack.
        put("stack", "gvisor")
        if (settings.hijackDns) put("dns_mode", "hijack")

        when (settings.splitTunnelMode) {
            SplitTunnelMode.Off -> Unit
            SplitTunnelMode.AllowList -> if (settings.splitTunnelPackages.isNotEmpty()) {
                putJsonArray("include_package") {
                    settings.splitTunnelPackages.sorted().forEach { add(it) }
                }
            }
            SplitTunnelMode.BlockList -> if (settings.splitTunnelPackages.isNotEmpty()) {
                putJsonArray("exclude_package") {
                    settings.splitTunnelPackages.sorted().forEach { add(it) }
                }
            }
        }
    }

    /**
     * The loopback inbound the app's own speed test dials; see [ProbeEndpoint] for why it exists.
     *
     * `listen` is deliberately `127.0.0.1` rather than `0.0.0.0`: bound to every interface this
     * would be an open proxy for the whole network the phone is on. The credentials close the same
     * hole for other applications on the device itself.
     */
    private fun buildProbeInbound(probe: ProbeEndpoint): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", PROBE_TAG)
        put("listen", "127.0.0.1")
        put("listen_port", probe.port)
        putJsonArray("users") {
            addJsonObject {
                put("username", probe.username)
                put("password", probe.password)
            }
        }
    }

    // ---------------------------------------------------------------- Route

    private fun buildRoute(
        settings: AppSettings,
        ruleSetDir: String,
        probe: ProbeEndpoint?,
    ): JsonObject = buildJsonObject {
        val useGeoBypass = settings.routingMode == RoutingMode.Rules

        putJsonArray("rules") {
            // Sniffing has to run first so later rules can match on domain.
            addJsonObject { put("action", "sniff") }

            // The speed test goes to the server whatever the routing mode says. Under «по
            // правилам» a measurement host that happens to sit behind a bypass rule would be
            // measured over the phone's own connection and reported as the server's throughput,
            // which is worse than no number at all.
            if (probe != null) {
                addJsonObject {
                    putJsonArray("inbound") { add(PROBE_TAG) }
                    put("outbound", PROXY_TAG)
                }
            }

            if (settings.hijackDns) {
                addJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                }
            }


            if (settings.blockAds) {
                addJsonObject {
                    putJsonArray("rule_set") { add(RULE_SET_ADS) }
                    put("action", "reject")
                }
            }

            // Rejected rather than routed anywhere, and only on 443. `reject` answers the client
            // immediately, which is the entire benefit: a browser that is refused QUIC retries
            // over TLS at once, where one that is silently dropped waits out its own timeout.
            // Restricting to 443 leaves other UDP alone — DNS over QUIC, WireGuard, games, and
            // the app's own probes to QUIC-only nodes all live on other ports.
            //
            // This sits after the ad rule and before the bypass rules deliberately: matching is
            // first-wins, and a blocked ad host should not need a second rule to also be QUIC.
            if (settings.blockQuic) {
                addJsonObject {
                    putJsonArray("protocol") { add("quic") }
                    putJsonArray("port") { add(443) }
                    put("action", "reject")
                }
            }

            if (settings.bypassLan) {
                addJsonObject {
                    put("ip_is_private", true)
                    put("outbound", DIRECT_TAG)
                }
            }

            if (useGeoBypass) {
                addJsonObject {
                    putJsonArray("rule_set") {
                        add(RULE_SET_GEOSITE_RU)
                        add(RULE_SET_GEOIP_RU)
                    }
                    put("outbound", DIRECT_TAG)
                }
            }
        }

        // Only referenced sets are declared — sing-box loads every declared set on start, so
        // listing unused ones would cost startup time for nothing.
        val ruleSets = buildList {
            if (settings.blockAds) add(RULE_SET_ADS to ADS_FILE)
            if (useGeoBypass) {
                add(RULE_SET_GEOSITE_RU to GEOSITE_RU_FILE)
                add(RULE_SET_GEOIP_RU to GEOIP_RU_FILE)
            }
        }
        if (ruleSets.isNotEmpty()) {
            putJsonArray("rule_set") {
                ruleSets.forEach { (tag, file) -> add(localRuleSet(tag, "$ruleSetDir/$file")) }
            }
        }

        put(
            "final",
            if (settings.routingMode == RoutingMode.Direct) DIRECT_TAG else PROXY_TAG,
        )
        // Server hostnames are resolved without going through the proxy — resolving them
        // through the tunnel they are needed to establish would be circular. When the direct
        // resolver is itself named rather than numbered, the bootstrap takes this over: a
        // resolver that needs resolving cannot be the thing that resolves.
        put(
            "default_domain_resolver",
            if (isNumericAddress(addressOf(settings.directDns, DEFAULT_DIRECT_DNS))) {
                DNS_DIRECT_TAG
            } else {
                DNS_BOOTSTRAP_TAG
            },
        )
        put("auto_detect_interface", true)
    }

    /**
     * Rule sets are read from disk, never fetched during startup.
     *
     * As remote sets they were downloaded synchronously inside `startOrReloadService`, which made
     * the very first connection depend on DNS and GitHub's CDN being reachable *before* the
     * tunnel exists — and on the platform having already reported a usable interface to the core.
     * When that race lost, startup died with "no available network interface". Shipping the
     * compiled sets as assets removes the dependency outright; all three together are under 70 KB.
     */
    private fun localRuleSet(tag: String, path: String): JsonObject = buildJsonObject {
        put("type", "local")
        put("tag", tag)
        put("format", "binary")
        put("path", path)
    }

    // ------------------------------------------------------------- Outbound

    private fun buildOutbound(node: ProxyNode): JsonObject {
        val raw = node.settings as? ProxySettings.Raw
        return if (raw != null) rawOutbound(raw) else typedOutbound(node)
    }

    /**
     * The provider's own outbound, with two things forced and nothing else touched.
     *
     * `tag` becomes ours because the route rules name it; anything else the document says is its
     * author's business and survives untouched. `detour` is dropped because it points at a tag
     * from a configuration we did not import — the core would refuse the whole document over a
     * name it cannot resolve, and a chain is a feature to carry deliberately rather than by
     * accident.
     *
     * An unreadable document throws rather than falling back to anything. The fallback used to be
     * `{"type": "direct"}`, which is the worst failure this file can produce: the core starts,
     * `route.final` still names this tag, and every packet leaves the phone unencrypted while the
     * connect screen says the tunnel is up. [com.mydrop.vpn.data.TunnelConfigBuilder] catches
     * this, writes the reason to the journal and returns null, so the tunnel simply does not come
     * up — which is the honest answer.
     */
    private fun rawOutbound(raw: ProxySettings.Raw): JsonObject {
        val parsed = runCatching {
            Json.parseToJsonElement(raw.outbound).jsonObject
        }.getOrElse { error ->
            // The message stays technical and untranslated: this file has no resources, and the
            // sentence the user reads is assembled one layer up, where the journal already wraps
            // whatever went wrong in its own localized line.
            throw IllegalStateException(error.message ?: "unparsable outbound", error)
        }

        return buildJsonObject {
            parsed.forEach { (key, value) ->
                if (key != "tag" && key != "detour") put(key, value)
            }
            put("tag", PROXY_TAG)
        }
    }

    private fun typedOutbound(node: ProxyNode): JsonObject = buildJsonObject {
        put("tag", PROXY_TAG)
        put("server", node.server)
        put("server_port", node.port)

        when (val s = node.settings) {
            is ProxySettings.Vless -> {
                put("type", "vless")
                put("uuid", s.uuid)
                if (s.flow.isNotEmpty()) put("flow", s.flow)
                put("packet_encoding", s.packetEncoding)
            }

            is ProxySettings.Vmess -> {
                put("type", "vmess")
                put("uuid", s.uuid)
                put("alter_id", s.alterId)
                put("security", s.security)
                put("packet_encoding", s.packetEncoding)
            }

            is ProxySettings.Trojan -> {
                put("type", "trojan")
                put("password", s.password)
            }

            is ProxySettings.Shadowsocks -> {
                put("type", "shadowsocks")
                put("method", s.method)
                put("password", s.password)
                if (s.plugin.isNotEmpty()) {
                    put("plugin", s.plugin)
                    if (s.pluginOptions.isNotEmpty()) put("plugin_opts", s.pluginOptions)
                }
            }

            is ProxySettings.Hysteria2 -> {
                put("type", "hysteria2")
                put("password", s.password)
                if (s.upMbps > 0) put("up_mbps", s.upMbps)
                if (s.downMbps > 0) put("down_mbps", s.downMbps)
                if (s.obfsType.isNotEmpty()) {
                    putJsonObject("obfs") {
                        put("type", s.obfsType)
                        put("password", s.obfsPassword)
                    }
                }
            }

            is ProxySettings.Hysteria -> {
                put("type", "hysteria")
                if (s.auth.isNotEmpty()) put("auth_str", s.auth)
                if (s.obfs.isNotEmpty()) put("obfs", s.obfs)
                if (s.upMbps > 0) put("up_mbps", s.upMbps)
                if (s.downMbps > 0) put("down_mbps", s.downMbps)
            }

            is ProxySettings.Tuic -> {
                put("type", "tuic")
                put("uuid", s.uuid)
                put("password", s.password)
                put("congestion_control", s.congestionControl)
                put("udp_relay_mode", s.udpRelayMode)
                if (s.zeroRttHandshake) put("zero_rtt_handshake", true)
            }

            is ProxySettings.AnyTls -> {
                put("type", "anytls")
                put("password", s.password)
            }

            is ProxySettings.ShadowTls -> {
                put("type", "shadowtls")
                put("version", s.version)
                put("password", s.password)
            }

            is ProxySettings.WireGuard -> {
                put("type", "wireguard")
                put("private_key", s.privateKey)
                put("peer_public_key", s.peerPublicKey)
                if (s.preSharedKey.isNotEmpty()) put("pre_shared_key", s.preSharedKey)
                if (s.localAddresses.isNotEmpty()) {
                    putJsonArray("local_address") { s.localAddresses.forEach { add(it) } }
                }
                put("mtu", s.mtu)
            }

            is ProxySettings.Ssh -> {
                put("type", "ssh")
                put("user", s.user)
                if (s.password.isNotEmpty()) put("password", s.password)
                if (s.privateKey.isNotEmpty()) put("private_key", s.privateKey)
            }

            is ProxySettings.Http -> {
                put("type", "http")
                if (s.username.isNotEmpty()) put("username", s.username)
                if (s.password.isNotEmpty()) put("password", s.password)
            }

            is ProxySettings.Socks -> {
                put("type", "socks")
                put("version", s.version)
                if (s.username.isNotEmpty()) put("username", s.username)
                if (s.password.isNotEmpty()) put("password", s.password)
            }

            ProxySettings.Direct -> put("type", "direct")

            // Handled before this function is reached; the branch exists so a new settings type
            // cannot be added without the compiler pointing here. It throws rather than emitting
            // `direct` for the same reason [rawOutbound] does: a proxy tag that is not a proxy is
            // a tunnel that lies about carrying traffic.
            is ProxySettings.Raw -> error("Raw outbound must be built by rawOutbound()")
        }

        node.tls?.let { put("tls", buildTls(it, node.server)) }
        node.transport?.let { put("transport", buildTransport(it)) }
        node.multiplex?.takeIf { it.enabled }?.let { mux ->
            putJsonObject("multiplex") {
                put("enabled", true)
                put("protocol", mux.protocol)
                put("max_connections", mux.maxConnections)
                if (mux.padding) put("padding", true)
            }
        }
    }

    private fun buildTls(tls: TlsOptions, server: String): JsonObject = buildJsonObject {
        put("enabled", true)
        put("server_name", tls.serverName?.takeIf(String::isNotEmpty) ?: server)
        if (tls.insecure) put("insecure", true)
        if (tls.alpn.isNotEmpty()) putJsonArray("alpn") { tls.alpn.forEach { add(it) } }

        tls.fingerprint?.takeIf(String::isNotEmpty)?.let { fingerprint ->
            putJsonObject("utls") {
                put("enabled", true)
                put(
                    "fingerprint",
                    if (fingerprint in knownFingerprints) fingerprint else "chrome",
                )
            }
        }

        tls.reality?.let { reality ->
            // REALITY requires uTLS; without a client hello to mimic there is nothing to relay.
            if (tls.fingerprint.isNullOrEmpty()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", "chrome")
                }
            }
            putJsonObject("reality") {
                put("enabled", true)
                put("public_key", reality.publicKey)
                if (reality.shortId.isNotEmpty()) put("short_id", reality.shortId)
            }
        }
    }

    private fun buildTransport(transport: TransportOptions): JsonObject = buildJsonObject {
        when (transport) {
            is TransportOptions.WebSocket -> {
                put("type", "ws")
                put("path", transport.path)
                if (transport.headers.isNotEmpty()) {
                    putJsonObject("headers") {
                        transport.headers.forEach { (k, v) -> put(k, v) }
                    }
                }
                if (transport.maxEarlyData > 0) {
                    put("max_early_data", transport.maxEarlyData)
                    transport.earlyDataHeaderName?.let { put("early_data_header_name", it) }
                }
            }

            is TransportOptions.Grpc -> {
                put("type", "grpc")
                put("service_name", transport.serviceName)
                if (transport.permitWithoutStream) put("permit_without_stream", true)
            }

            is TransportOptions.Http -> {
                put("type", "http")
                if (transport.host.isNotEmpty()) {
                    putJsonArray("host") { transport.host.forEach { add(it) } }
                }
                put("path", transport.path)
                put("method", transport.method)
            }

            is TransportOptions.HttpUpgrade -> {
                put("type", "httpupgrade")
                if (transport.host.isNotEmpty()) put("host", transport.host)
                put("path", transport.path)
            }

            TransportOptions.Quic -> put("type", "quic")

            // sing-box implements five stream transports and XHTTP is not among them, so there is
            // nothing to emit here and nothing that could be emitted. Refusing loudly is the whole
            // point: the alternative, dropping the transport, is what produced servers that
            // connected and carried nothing.
            is TransportOptions.Xhttp -> throw IllegalArgumentException(
                "sing-box не умеет XHTTP",
            )
        }
    }

    private fun LogLevel.toSingBoxLevel(): String = when (this) {
        LogLevel.Trace -> "trace"
        LogLevel.Debug -> "debug"
        LogLevel.Info -> "info"
        LogLevel.Warn -> "warn"
        LogLevel.Error -> "error"
    }
}
