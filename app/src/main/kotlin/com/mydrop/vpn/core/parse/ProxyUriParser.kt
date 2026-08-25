package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.MultiplexOptions
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns provider share-links into [ProxyNode]s.
 *
 * Every parser here is deliberately forgiving: providers emit the same protocol with different
 * parameter spellings (`sni`/`peer`/`host`, `insecure`/`allowInsecure`, `hy2`/`hysteria2`), and a
 * subscription with one malformed line must still yield all the other servers.
 */
object ProxyUriParser {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parses one link. Returns null when the line is not a proxy URI we understand. */
    fun parse(uri: String, subscriptionId: String? = null): ProxyNode? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        return runCatching {
            when (scheme) {
                "vmess" -> parseVmess(trimmed, subscriptionId)
                "vless" -> parseVless(trimmed, subscriptionId)
                "trojan" -> parseTrojan(trimmed, subscriptionId)
                "ss" -> parseShadowsocks(trimmed, subscriptionId)
                "hysteria2", "hy2" -> parseHysteria2(trimmed, subscriptionId)
                "hysteria", "hy" -> parseHysteria(trimmed, subscriptionId)
                "tuic" -> parseTuic(trimmed, subscriptionId)
                "anytls" -> parseAnyTls(trimmed, subscriptionId)
                "socks", "socks5", "socks4" -> parseSocks(trimmed, subscriptionId)
                "http", "https" -> parseHttp(trimmed, subscriptionId)
                "wireguard", "wg" -> parseWireGuard(trimmed, subscriptionId)
                "ssh" -> parseSsh(trimmed, subscriptionId)
                else -> null
            }
        }.getOrNull()
    }

    /** Parses every recognised link in a blob, skipping junk lines. */
    fun parseAll(text: String, subscriptionId: String? = null): List<ProxyNode> =
        text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { parse(it, subscriptionId) }
            .toList()

    // ---------------------------------------------------------------- VLESS

    private fun parseVless(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val uuid = urlDecode(p.userInfo).takeIf { it.isNotEmpty() } ?: return null

        val settings = ProxySettings.Vless(
            uuid = uuid,
            flow = p.q("flow").orEmpty(),
            packetEncoding = p.q("packetencoding") ?: "xudp",
        )
        return node(p, settings, subId, uri)
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(uri: String, subId: String?): ProxyNode? {
        val payload = uri.removePrefix("vmess://").substringBefore('#')
        // Canonical form is base64(JSON); some panels emit a plain URI instead.
        val json = base64DecodeOrNull(payload)?.let {
            runCatching { lenientJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        return if (json != null) parseVmessJson(json, uri, subId) else parseVmessUri(uri, subId)
    }

    private fun parseVmessJson(o: JsonObject, uri: String, subId: String?): ProxyNode? {
        val host = o.str("add") ?: return null
        val port = o.str("port")?.toIntOrNull() ?: return null
        val uuid = o.str("id") ?: return null

        val net = o.str("net")?.lowercase().orEmpty()
        val headerHost = o.str("host").orEmpty()
        val path = o.str("path").orEmpty().ifEmpty { "/" }

        val transport = when (net) {
            "ws" -> TransportOptions.WebSocket(
                path = path,
                headers = if (headerHost.isNotEmpty()) mapOf("Host" to headerHost) else emptyMap(),
            )
            "grpc" -> TransportOptions.Grpc(serviceName = o.str("path").orEmpty())
            "h2", "http" -> TransportOptions.Http(host = splitCsv(headerHost), path = path)
            "httpupgrade" -> TransportOptions.HttpUpgrade(host = headerHost, path = path)
            "quic" -> TransportOptions.Quic
            else -> null
        }

        val tlsMode = o.str("tls")?.lowercase().orEmpty()
        val tls = if (tlsMode == "tls" || tlsMode == "reality") {
            TlsOptions(
                enabled = true,
                serverName = o.str("sni")?.ifEmpty { null } ?: headerHost.ifEmpty { null } ?: host,
                alpn = splitCsv(o.str("alpn")),
                fingerprint = o.str("fp")?.ifEmpty { null },
                insecure = o.str("allowinsecure") == "1" || o.str("skip-cert-verify") == "true",
            )
        } else {
            null
        }

        val settings = ProxySettings.Vmess(
            uuid = uuid,
            alterId = o.str("aid")?.toIntOrNull() ?: 0,
            security = o.str("scy")?.ifEmpty { null } ?: "auto",
        )

        return ProxyNode(
            id = ProxyNode.stableId(host, port, settings, subId),
            name = o.str("ps")?.ifEmpty { null } ?: "$host:$port",
            server = host,
            port = port,
            settings = settings,
            tls = tls,
            transport = transport,
            subscriptionId = subId,
            sourceUri = uri,
        )
    }

    private fun parseVmessUri(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val uuid = urlDecode(p.userInfo).substringBefore(':').takeIf { it.isNotEmpty() } ?: return null
        val settings = ProxySettings.Vmess(
            uuid = uuid,
            alterId = p.qInt("alterid", "aid") ?: 0,
            security = p.q("security", "scy", "encryption") ?: "auto",
        )
        return node(p, settings, subId, uri)
    }

    // --------------------------------------------------------------- Trojan

    private fun parseTrojan(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val password = urlDecode(p.userInfo).takeIf { it.isNotEmpty() } ?: return null
        // Trojan is TLS-only by definition, so a link without `security=` still means TLS.
        return node(p, ProxySettings.Trojan(password), subId, uri, tlsDefaultOn = true)
    }

    // ---------------------------------------------------------- Shadowsocks

    private fun parseShadowsocks(uri: String, subId: String?): ProxyNode? {
        val withoutScheme = uri.removePrefix("ss://")
        val name = withoutScheme.substringAfter('#', "").let(::urlDecode)
        val body = withoutScheme.substringBefore('#')

        // Legacy form encodes the whole "method:password@host:port" as one base64 blob.
        if (!body.contains('@')) {
            val decoded = base64DecodeOrNull(body.substringBefore('?')) ?: return null
            val at = decoded.lastIndexOf('@')
            if (at < 0) return null
            val (method, password) = decoded.substring(0, at).split(':', limit = 2)
                .takeIf { it.size == 2 } ?: return null
            val (host, port) = splitHostAndPort(decoded.substring(at + 1)) ?: return null
            val settings = ProxySettings.Shadowsocks(method, password)
            return ProxyNode(
                id = ProxyNode.stableId(host, port, settings, subId),
                name = name.ifEmpty { "$host:$port" },
                server = host,
                port = port,
                settings = settings,
                subscriptionId = subId,
                sourceUri = uri,
            )
        }

        // SIP002: ss://base64(method:password)@host:port?plugin=...#name
        val p = splitUri(uri) ?: return null
        val (method, password) = credentialPair(p.userInfo).takeIf { it.size == 2 }
            ?: return null

        val plugin = p.q("plugin").orEmpty()
        val settings = ProxySettings.Shadowsocks(
            method = method,
            password = password,
            plugin = plugin.substringBefore(';'),
            pluginOptions = plugin.substringAfter(';', ""),
        )
        return ProxyNode(
            id = ProxyNode.stableId(p.host, p.port, settings, subId),
            name = p.fragment.ifEmpty { "${p.host}:${p.port}" },
            server = p.host,
            port = p.port,
            settings = settings,
            subscriptionId = subId,
            sourceUri = uri,
        )
    }

    // ------------------------------------------------------------ Hysteria2

    private fun parseHysteria2(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val password = urlDecode(p.userInfo).takeIf { it.isNotEmpty() } ?: return null
        val settings = ProxySettings.Hysteria2(
            password = password,
            obfsType = p.q("obfs").orEmpty(),
            obfsPassword = p.q("obfs-password", "obfspassword").orEmpty(),
            upMbps = p.qInt("upmbps", "up") ?: 0,
            downMbps = p.qInt("downmbps", "down") ?: 0,
        )
        return node(p, settings, subId, uri, tlsDefaultOn = true)
    }

    private fun parseHysteria(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val settings = ProxySettings.Hysteria(
            auth = p.q("auth", "auth_str", "authstr") ?: urlDecode(p.userInfo),
            obfs = p.q("obfs").orEmpty(),
            upMbps = p.qInt("upmbps", "up") ?: 0,
            downMbps = p.qInt("downmbps", "down") ?: 0,
        )
        return node(p, settings, subId, uri, tlsDefaultOn = true)
    }

    // ----------------------------------------------------------------- TUIC

    private fun parseTuic(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val creds = urlDecode(p.userInfo).split(':', limit = 2)
        // The uuid is what identifies the session; without it the link is a fragment, not a server.
        if (creds.getOrElse(0) { "" }.isEmpty()) return null
        val settings = ProxySettings.Tuic(
            uuid = creds[0],
            password = creds.getOrElse(1) { "" },
            congestionControl = p.q("congestion_control", "congestioncontrol") ?: "bbr",
            udpRelayMode = p.q("udp_relay_mode", "udprelaymode") ?: "native",
            zeroRttHandshake = p.qBool("zero_rtt_handshake", "reduce_rtt"),
        )
        return node(p, settings, subId, uri, tlsDefaultOn = true)
    }

    private fun parseAnyTls(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val password = urlDecode(p.userInfo).takeIf { it.isNotEmpty() } ?: return null
        val settings = ProxySettings.AnyTls(password = password)
        return node(p, settings, subId, uri, tlsDefaultOn = true)
    }

    /**
     * `wireguard://<private-key>@host:port?publickey=…&reserved=…&address=…&mtu=…#name`
     *
     * There is no standard behind this one — it is the shape Hiddify and its relatives emit, and
     * the field names vary by which of them wrote the link, so both spellings of each are read.
     */
    private fun parseWireGuard(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val settings = ProxySettings.WireGuard(
            privateKey = urlDecode(p.userInfo).ifEmpty { p.query["privatekey"].orEmpty() },
            peerPublicKey = p.query["publickey"] ?: p.query["public-key"] ?: return null,
            preSharedKey = p.query["presharedkey"] ?: p.query["pre-shared-key"].orEmpty(),
            localAddresses = (p.query["address"] ?: p.query["ip"]).orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
            mtu = p.query["mtu"]?.toIntOrNull() ?: 1408,
            // Cloudflare WARP hands out three bytes here and refuses the session without them.
            reserved = p.query["reserved"].orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() },
        )
        return node(p, settings, subId, uri)
    }

    /** `ssh://user:password@host:port#name`. */
    private fun parseSsh(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val creds = urlDecode(p.userInfo).split(':', limit = 2)
        val settings = ProxySettings.Ssh(
            user = creds.getOrElse(0) { "root" }.ifEmpty { "root" },
            password = creds.getOrElse(1) { "" },
        )
        return node(p, settings, subId, uri)
    }

    // --------------------------------------------------------- SOCKS / HTTP

    private fun parseSocks(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        // Some panels base64 the whole "user:pass" pair rather than percent-encoding it, so the
        // encoded form is tried first — but only accepted when it decodes to something that
        // actually looks like a credential pair. An ordinary login of letters and digits whose
        // length happens to be a multiple of four is valid base64: "user1234" decodes cleanly to
        // six bytes of binary, and the username reaching the core used to be that binary.
        val creds = credentialPair(p.userInfo)
        val settings = ProxySettings.Socks(
            version = if (p.scheme == "socks4") "4" else "5",
            username = creds.getOrElse(0) { "" },
            password = creds.getOrElse(1) { "" },
        )
        return node(p, settings, subId, uri)
    }

    private fun parseHttp(uri: String, subId: String?): ProxyNode? {
        val p = splitUri(uri) ?: return null
        val creds = urlDecode(p.userInfo).split(':', limit = 2)
        val settings = ProxySettings.Http(
            username = creds.getOrElse(0) { "" },
            password = creds.getOrElse(1) { "" },
        )
        return node(p, settings, subId, uri, tlsDefaultOn = p.scheme == "https")
    }

    // -------------------------------------------------------------- Shared

    private fun node(
        p: ParsedUri,
        settings: ProxySettings,
        subId: String?,
        sourceUri: String,
        tlsDefaultOn: Boolean = false,
    ): ProxyNode {
        val transport = p.transport()
        return ProxyNode(
            id = ProxyNode.stableId(p.host, p.port, settings, subId),
            name = p.fragment.ifEmpty { "${p.host}:${p.port}" },
            server = p.host,
            port = p.port,
            settings = settings,
            tls = p.tls(tlsDefaultOn, transport),
            transport = transport,
            multiplex = p.multiplex(),
            subscriptionId = subId,
            sourceUri = sourceUri,
        )
    }

    private fun ParsedUri.transport(): TransportOptions? {
        val hostHeader = q("host").orEmpty()
        val path = q("path")?.ifEmpty { null } ?: "/"
        return when (q("type", "net", "obfs")?.lowercase()) {
            "ws", "websocket" -> TransportOptions.WebSocket(
                path = path,
                headers = if (hostHeader.isNotEmpty()) mapOf("Host" to hostHeader) else emptyMap(),
                maxEarlyData = qInt("eds", "max_early_data") ?: 0,
                earlyDataHeaderName = q("ed", "early_data_header_name"),
            )
            "grpc" -> TransportOptions.Grpc(
                serviceName = q("servicename", "service_name", "path").orEmpty(),
            )
            "http", "h2" -> TransportOptions.Http(host = splitCsv(hostHeader), path = path)
            "httpupgrade" -> TransportOptions.HttpUpgrade(host = hostHeader, path = path)
            "quic" -> TransportOptions.Quic
            else -> null
        }
    }

    private fun ParsedUri.tls(defaultOn: Boolean, transport: TransportOptions?): TlsOptions? {
        val security = q("security")?.lowercase()
        val realityKey = q("pbk", "public-key", "publickey")

        val enabled = when {
            security == "reality" || realityKey != null -> true
            security == "tls" || security == "xtls" -> true
            security == "none" -> false
            else -> defaultOn
        }
        if (!enabled) return null

        val sniFallback = (transport as? TransportOptions.WebSocket)
            ?.headers?.get("Host")
            ?: q("host")
            ?: host

        return TlsOptions(
            enabled = true,
            serverName = q("sni", "peer", "servername") ?: sniFallback,
            insecure = qBool("insecure", "allowinsecure", "allow_insecure", "skip-cert-verify"),
            alpn = splitCsv(q("alpn")),
            fingerprint = q("fp", "fingerprint"),
            reality = realityKey?.let {
                RealityOptions(
                    publicKey = it,
                    shortId = q("sid", "short-id", "shortid").orEmpty(),
                    spiderX = q("spx", "spider-x", "spiderx") ?: "/",
                )
            },
        )
    }

    private fun ParsedUri.multiplex(): MultiplexOptions? {
        if (!qBool("mux", "multiplex")) return null
        return MultiplexOptions(
            enabled = true,
            protocol = q("mux-protocol") ?: "h2mux",
            maxConnections = qInt("mux-max-connections") ?: 4,
        )
    }

    /**
     * `user:password` from a link's userinfo, base64 or percent-encoded.
     *
     * A base64 decode is only believed when the result is printable and carries the colon that
     * makes it a pair; anything else falls back to plain percent-decoding. See [parseSocks].
     */
    private fun credentialPair(userInfo: String): List<String> {
        val decoded = base64DecodeOrNull(userInfo)
            ?.takeIf { it.contains(':') && it.all { ch -> ch == '	' || ch.code in 0x20..0x7e } }
        return (decoded ?: urlDecode(userInfo)).split(':', limit = 2)
    }

    /** vmess JSON mixes strings and numbers for the same field depending on the panel. */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else it.jsonPrimitive.content }
}
