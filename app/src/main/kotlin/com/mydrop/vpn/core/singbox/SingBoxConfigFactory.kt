package com.mydrop.vpn.core.singbox

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
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

    const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"

    /**
     * The tag one server carries inside the selector group.
     *
     * Prefixed rather than the bare id, because a tag is a name in a namespace shared with
     * `proxy`, `direct` and the DNS servers, and a hash that happened to collide with one of those
     * would be a very quiet disaster.
     */
    fun nodeTag(nodeId: String) = "node-$nodeId"
    private const val PROBE_TAG = "probe-in"
    private const val DNS_REMOTE_TAG = "dns-remote"
    private const val DNS_DIRECT_TAG = "dns-direct"
    private const val DNS_BOOTSTRAP_TAG = "dns-bootstrap"
    private const val DNS_BOOTSTRAP_PROXY_TAG = "dns-bootstrap-proxy"

    /** Fallbacks for a DNS field the user has emptied; they match [AppSettings]' own defaults. */
    const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"

    /**
     * Not `"local"`, and that is load-bearing rather than a style choice.
     *
     * `"local"` asks sing-box's `local` DNS transport to use whatever the platform already knows
     * — and on this platform that means [MyDropVpnService][com.mydrop.vpn.vpn.MyDropVpnService]'s
     * `localDNSTransport()`, which returns `null`. Nothing wires libbox's Android bridge, so the
     * upstream Go implementation falls through to its own generic one, which reads
     * `/etc/resolv.conf` for the servers to ask. Checked on a real device: that file does not
     * exist. Its own fallback for that case is `127.0.0.1:53` and `[::1]:53` — nothing answers on
     * either, because nothing runs a resolver on this device's loopback. So every `dns-direct`
     * lookup fails outright, every time, on every install: not a leak, an outright dead resolver.
     *
     * The one query this default reaches from every routing mode is
     * [route.default_domain_resolver][buildRoute] — the proxy server's own hostname, resolved
     * before there is a tunnel to resolve it through. A subscription whose servers are named by
     * IP never asks this question and never notices. One named by domain — common for
     * CDN-fronted or REALITY endpoints — cannot connect at all on a fresh install, and the
     * failure is silent: no exception a user would recognise, just a resolver that answers
     * nothing.
     *
     * `"local"` stays reachable for anyone who types it by hand — see [isDirectLocal] — because on
     * a platform where `localDNSTransport()` is implemented it is exactly the right answer. It
     * must not be *offered* until this app is that platform.
     */
    const val DEFAULT_DIRECT_DNS = "8.8.8.8"

    /**
     * The literal value that means "resolve through the platform", independent of whatever
     * [DEFAULT_DIRECT_DNS] happens to be.
     *
     * [isDirectLocal] used to test equality against [DEFAULT_DIRECT_DNS] itself, which was a
     * shortcut that only worked while that default *was* the marker — the moment it stopped
     * being one, the same comparison would have quietly treated an ordinary `8.8.8.8` as a
     * request to route through the broken platform resolver. Named separately so the two can
     * disagree, which is now the normal case.
     */
    private const val LOCAL_DNS_MARKER = "local"

    /**
     * Whether [resolver] is the one the app ships with, as opposed to one somebody chose.
     *
     * The distinction decides whether [FailoverWatchdog] polices the resolver at all. A resolver
     * the user typed or picked is a statement about how they want names looked up, and a watchdog
     * that quietly swaps it is undoing that statement — so those keep the check. The shipped
     * default carries no such statement, and policing it turned out to cost more than it saved: a
     * courier's journal has three full tunnel rebuilds in fifteen hours, each after 1.1.1.1 missed
     * two DoH queries in a row on a moving LTE connection while the tunnel probe beside it passed.
     * Two eight-second timeouts inside forty seconds on a bad signal is the signal, not a dead
     * resolver, and the price was every open connection on the phone.
     *
     * Matched on the string the tunnel actually queries, so it holds whether the value arrived as
     * the untouched default, as the same URL typed into the field by hand, or as a DNS profile
     * that happens to name it.
     */
    fun isShippedResolver(resolver: String, mode: RoutingMode): Boolean =
        resolver == if (mode == RoutingMode.Direct) DEFAULT_DIRECT_DNS else DEFAULT_REMOTE_DNS

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
     * @param dnsFallback builds the document with [FALLBACK_REMOTE_DNS] in place of the chosen
     *   resolver, for when the watchdog has found the chosen one dead.
     * @param group every server the tunnel may move onto without being restarted, [node] included.
     *   Fewer than two means no group at all and the old single-outbound shape.
     */
    fun build(
        node: ProxyNode,
        settings: AppSettings,
        ruleSetDir: String,
        probe: ProbeEndpoint? = null,
        dnsOverride: String? = null,
        dnsFallback: Boolean = false,
        group: List<ProxyNode> = emptyList(),
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildConfig(
            node = node,
            settings = settings.withDnsOverride(dnsOverride).withDnsFallback(dnsFallback),
            ruleSetDir = ruleSetDir,
            probe = probe,
            group = group,
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

    /**
     * Swaps in the fallback resolver, on the same server the override went to.
     *
     * Applied after [withDnsOverride] on purpose: the resolver being replaced is whichever one the
     * tunnel actually queries, and in direct mode that is not the remote one.
     */
    private fun AppSettings.withDnsFallback(active: Boolean): AppSettings = when {
        !active -> this
        routingMode == RoutingMode.Direct -> copy(directDns = fallbackFor(directDns, DEFAULT_DIRECT_DNS))
        else -> copy(remoteDns = fallbackFor(remoteDns, DEFAULT_REMOTE_DNS))
    }

    /**
     * The resolver [build] would put in place of [primary]. Exposed so the journal can name it
     * before the switch happens, rather than leaving the user to infer it from a working tunnel.
     */
    fun fallbackResolver(primary: String): String = fallbackFor(primary, DEFAULT_REMOTE_DNS)

    private fun fallbackFor(primary: String, default: String): String =
        if (addressOf(primary, default) == addressOf(FALLBACK_REMOTE_DNS, default)) {
            FALLBACK_REMOTE_DNS_ALT
        } else {
            FALLBACK_REMOTE_DNS
        }

    private fun buildConfig(
        node: ProxyNode,
        settings: AppSettings,
        ruleSetDir: String,
        probe: ProbeEndpoint?,
        group: List<ProxyNode> = emptyList(),
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
            // One outbound per server the tunnel may end up on, with a selector on top wearing the
            // tag everything else already points at. Nothing downstream changes: `route.final`,
            // the probe rule and the DNS detours all still say "proxy", and the selector decides
            // what that means today.
            //
            // The point of the arrangement is that moving between these servers stops being a
            // restart. `selectOutbound` swaps a pointer inside the core — the TUN survives, the
            // DNS cache survives, open connections survive, and the watchdog's fifteen-second
            // grace never happens. Before this, every switch — a failover, a tap in the list, a
            // resolver swap — tore down the core and took every connection in the tunnel with it.
            //
            // Falls back to the single outbound when there is nobody to switch to: a group of one
            // is a pointer that can only point at itself.
            // Serialised before anything is written, because what the group may name and what
            // the document actually contains have to be the same set. A member that cannot be
            // expressed is dropped rather than fatal — it would have been one destination among
            // several — but its tag has to go with it: naming an outbound the document does not
            // define makes sing-box reject the whole configuration with "outbound not found", and
            // then no server works rather than one. The selected server is the exception; if that
            // one cannot be built there is nothing to connect to at all, and the failure belongs
            // upstream where it is already handled.
            val members = group
                .filter { it.settings != ProxySettings.Direct }
                .mapNotNull { member ->
                    runCatching { member to buildOutbound(member, nodeTag(member.id)) }.getOrNull()
                }
            // The selected node has to be inside the group it is the default of. It drops out
            // when it is a direct outbound, and when its own serialisation failed.
            val selectable = members.takeIf { list -> list.any { it.first.id == node.id } }.orEmpty()

            if (selectable.size > 1) {
                selectable.forEach { (_, outbound) -> add(outbound) }
                addJsonObject {
                    put("type", "selector")
                    put("tag", PROXY_TAG)
                    putJsonArray("outbounds") {
                        selectable.forEach { (member, _) -> add(nodeTag(member.id)) }
                    }
                    // Where the group points when the core starts. Without `experimental.cache_file`
                    // sing-box has no memory of a previous choice, so this is the whole of it —
                    // and it has to name the server the app believes is current, or a restart
                    // would silently move the tunnel somewhere the user never chose.
                    put("default", nodeTag(node.id))
                    // The default, spelled out because it is the reason for all of this: a switch
                    // must not reach into connections the user already has open.
                    put("interrupt_exist_connections", false)
                }
            } else {
                add(buildOutbound(node, PROXY_TAG))
            }
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
    private fun isDirectLocal(directDns: String): Boolean {
        val trimmed = directDns.trim()
        // Empty is deliberately not included. An emptied field means "use the default", and
        // dnsServer/addressOf already resolve that to DEFAULT_DIRECT_DNS — which is numeric, not
        // the platform marker — so counting it here would call a plain IP address "local".
        return trimmed.equals(LOCAL_DNS_MARKER, ignoreCase = true) ||
            trimmed.equals("$LOCAL_DNS_MARKER://", ignoreCase = true)
    }

    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        val remoteNamed = !isNumericAddress(addressOf(settings.remoteDns, DEFAULT_REMOTE_DNS))
        val directLocal = isDirectLocal(settings.directDns)
        val directNamed = !directLocal && !isNumericAddress(addressOf(settings.directDns, DEFAULT_DIRECT_DNS))

        putJsonArray("servers") {
            add(
                dnsServer(
                    DNS_REMOTE_TAG,
                    settings.remoteDns,
                    PROXY_TAG,
                    DNS_BOOTSTRAP_PROXY_TAG.takeIf { remoteNamed },
                ),
            )
            // A typed DNS server without a detour already uses the direct dialer. Pointing it
            // at our intentionally empty `direct` outbound is redundant and newer sing-box
            // versions reject that combination while starting the DNS service.
            add(
                dnsServer(
                    DNS_DIRECT_TAG,
                    settings.directDns,
                    null,
                    DNS_BOOTSTRAP_TAG.takeIf { directNamed },
                ),
            )
            // Each bootstrap goes the way the resolver it bootstraps goes. Sending the lookup for
            // a *direct* resolver through the proxy would resolve it from another country, and
            // sending the lookup for a *proxied* one out directly is the leak below.
            if (remoteNamed) {
                addJsonObject {
                    put("type", "https")
                    put("tag", DNS_BOOTSTRAP_PROXY_TAG)
                    put("server", addressOf(FALLBACK_REMOTE_DNS, DEFAULT_REMOTE_DNS))
                    put("path", "/dns-query")
                    // Through the tunnel, and this is the whole point of the tag existing.
                    //
                    // A resolver written as a name — `https://xbox-dns.ru/dns-query` — has to be
                    // resolved before it can resolve anything, and that one lookup used to leave
                    // as plain UDP on port 53, straight off the phone. In Russia that is the exact
                    // traffic DPI rewrites and drops. The result was a single point of failure
                    // nobody would think to suspect: the resolver could be perfectly healthy and
                    // still unreachable, because the question "what is its address" never got an
                    // honest answer, and every name on the phone stopped resolving at once.
                    //
                    // No circle: the address here is numeric, and the proxy's own hostname is
                    // resolved by `default_domain_resolver`, which stays direct.
                    put("detour", PROXY_TAG)
                }
            }
            if (directNamed) {
                addJsonObject {
                    // Not "local" — see DEFAULT_DIRECT_DNS for why that type has no working
                    // Android transport behind it right now. A user who names their own direct
                    // resolver ("dns.adguard.com" with no scheme, say) is rare, but the one
                    // lookup that bootstraps it still has to go somewhere real.
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
        // Opt-in only — see DEFAULT_DIRECT_DNS on why this type is not offered by default. A
        // field left blank falls back to DEFAULT_REMOTE_DNS/DEFAULT_DIRECT_DNS above and never
        // reaches this branch; only someone who typed the marker themselves does.
        val isLocal = trimmed.equals(LOCAL_DNS_MARKER, ignoreCase = true) ||
            trimmed.startsWith("$LOCAL_DNS_MARKER://", ignoreCase = true)
        if (isLocal) {
            return buildJsonObject {
                put("type", "local")
                put("tag", tag)
            }
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
                    else -> "udp"
                },
            )
            put("tag", tag)
            put("server", host)
            port?.let { put("server_port", it) }
            if (path.isNotEmpty() && (scheme == "https" || scheme == "h3")) put("path", path)
            detour?.let { put("detour", it) }
            // Only when the address is a name. On a numeric one the field is meaningless, and
            // the core is strict about fields that cannot apply.
            domainResolver?.let { put("domain_resolver", it) }
        }
    }

    // -------------------------------------------------------------- Inbound

    private fun buildTunInbound(settings: AppSettings): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        // The IPv6 address is unconditional, and that is the whole fix for a leak.
        //
        // A VpnService captures only the routes it is given, and a route can only be given for a
        // family the interface has an address in. With IPv6 switched off the tunnel had no v6
        // address, so no `::/0` route was installed, so Android left the *physical* interface as
        // the default gateway for every IPv6 packet — on any dual-stack Wi-Fi or carrier network
        // that is the user's real address and their traffic in the clear, while the app showed a
        // connected tunnel. "IPv6 off" has to mean "no IPv6 leaves this phone", not "IPv6 is
        // somebody else's problem".
        //
        // So the address is always there, the route is always installed, and what happens to the
        // captured traffic is decided in the routing rules below: carried when the user wants
        // IPv6, refused immediately when they do not.
        putJsonArray("address") {
            add("172.19.0.1/30")
            add("fdfe:dcba:9876::1/126")
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
                // Before the rule below, which routes and therefore stops matching.
                //
                // This is the one connection in the whole configuration whose destination is
                // resolved here rather than by the server at the far end, and that is the entire
                // point: everything else hands the hostname to the outbound, so a dead resolver
                // is invisible from outside. Asking for one name through our own DNS pipeline
                // turns "the resolver stopped working" into a probe that fails, next to a tunnel
                // probe that still passes — which is what tells the two faults apart.
                addJsonObject {
                    putJsonArray("inbound") { add(PROBE_TAG) }
                    putJsonArray("domain") { add(ProbeTargets.DNS) }
                    put("action", "resolve")
                    // Without these the check answers itself. The name's TTL is four minutes and
                    // the probe runs every twenty seconds, so the core serves its own cache and
                    // the resolver is never asked: measured over an hour and a half on the phone,
                    // 76 lookups came from cache against 9 that reached the resolver. A resolver
                    // that died would keep testing healthy until the entry expired — which is
                    // precisely the outage this probe exists to catch.
                    put("disable_cache", true)
                    // The optimistic cache serves a stale answer while it refreshes in the
                    // background. That is the right behaviour for browsing and the wrong one
                    // here, for the same reason.
                    put("disable_optimistic_cache", true)
                }
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

            // Refused rather than carried, and refused rather than dropped.
            //
            // The tunnel now captures IPv6 so that none of it escapes; this is what it does with
            // it. `reject` answers the application at once, so a browser doing Happy Eyeballs
            // gives up on the AAAA and uses the A record in milliseconds. Silently dropping the
            // packets instead would make every dual-stack connection wait out a timeout first,
            // which is the "internet is slow" that switching IPv6 off is meant to avoid.
            if (!settings.enableIpv6) {
                addJsonObject {
                    putJsonArray("ip_cidr") { add("::/0") }
                    put("action", "reject")
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
        val directLocal = isDirectLocal(settings.directDns)
        put(
            "default_domain_resolver",
            if (directLocal || isNumericAddress(addressOf(settings.directDns, DEFAULT_DIRECT_DNS))) {
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

    private fun buildOutbound(node: ProxyNode, tag: String): JsonObject {
        val raw = node.settings as? ProxySettings.Raw
        return if (raw != null) rawOutbound(raw, tag) else typedOutbound(node, tag)
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
    private fun rawOutbound(raw: ProxySettings.Raw, tag: String): JsonObject {
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
            put("tag", tag)
        }
    }

    private fun typedOutbound(node: ProxyNode, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
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
