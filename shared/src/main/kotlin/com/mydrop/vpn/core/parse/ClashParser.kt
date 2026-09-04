package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.identified
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions

/**
 * Reads the `proxies:` list out of a Clash / Clash.Meta configuration.
 *
 * This is a reader for one section of one machine-generated document, not a YAML implementation.
 * That is a deliberate limit: a real YAML parser is a dependency and a surface area of its own —
 * anchors, multi-document streams, block scalars, tags — and none of it appears in what panels
 * emit. What does appear is a list of flat mappings, sometimes with a nested `*-opts` block or an
 * inline `{a: 1, b: 2}`, and that is exactly what is handled here.
 *
 * Anything outside that shape is skipped rather than guessed at: a server built from a
 * misunderstood line would fail to dial with no explanation, which is worse than a server that
 * never appeared.
 */
object ClashParser {

    fun parse(document: String, subscriptionId: String?): List<ProxyNode> =
        proxyBlocks(document).mapNotNull { node(it, subscriptionId) }

    /**
     * Splits the `proxies:` section into one key/value map per entry.
     *
     * Entries start with `- ` at their own indentation; everything indented deeper belongs to the
     * entry above. The section ends at the first line that is neither blank nor indented past the
     * `proxies:` key itself — `proxy-groups:` and `rules:` are not ours to read.
     */
    private fun proxyBlocks(document: String): List<Map<String, String>> {
        val lines = document.lines()
        val start = lines.indexOfFirst { it.trimEnd().let { l -> l == "proxies:" || l.startsWith("proxies:") } }
        if (start < 0) return emptyList()

        val sectionIndent = lines[start].indentation()
        val blocks = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null

        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            val indent = line.indentation()
            if (indent <= sectionIndent && !line.trimStart().startsWith("-")) break

            val trimmed = line.trim()
            if (trimmed.startsWith("- ")) {
                current?.let(blocks::add)
                current = mutableMapOf()
                // `- {name: a, type: vless}` — the whole entry inline.
                val head = trimmed.removePrefix("- ").trim()
                if (head.startsWith("{")) {
                    current.putAll(inlineMap(head))
                } else {
                    head.toPair()?.let { (key, value) -> current[key] = value }
                }
            } else {
                val target = current ?: continue
                // Nested blocks (`ws-opts:` and friends) are flattened: their keys — `path`,
                // `headers`, `public-key` — do not collide with the entry's own.
                if (trimmed.endsWith(":")) continue
                trimmed.toPair()?.let { (key, value) ->
                    if (value.startsWith("{")) target.putAll(inlineMap(value)) else target[key] = value
                }
            }
        }
        current?.let(blocks::add)
        return blocks
    }

    private fun node(fields: Map<String, String>, subscriptionId: String?): ProxyNode? {
        val server = fields["server"] ?: return null
        // The range, not just "is a number". A `port: 0` or `port: 99999` from a mis-rendered
        // template made a node that looked ordinary in the list and could never connect.
        val port = fields["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

        // Same reasoning as in ProxyUriParser: a stream transport the core cannot speak makes the
        // node unusable, and keeping it minus the transport produces a server that connects and
        // then carries nothing.
        fields["network"]?.lowercase()?.let { declared ->
            if (declared !in KNOWN_NETWORKS && declared !in PLAIN_NETWORKS) return null
        }

        val settings = when (fields["type"]?.lowercase()) {
            "vless" -> ProxySettings.Vless(
                uuid = fields.getAny("uuid") ?: return null,
                flow = fields.getAny("flow").orEmpty(),
                packetEncoding = fields.getAny("packet-encoding", "packet_encoding", "packetencoding") ?: "xudp",
            )

            "vmess" -> ProxySettings.Vmess(
                uuid = fields.getAny("uuid") ?: return null,
                alterId = fields.getAny("alterId", "alterid", "alter_id", "aid")?.toIntOrNull() ?: 0,
                security = fields.getAny("cipher", "security", "scy") ?: "auto",
            )

            "trojan" -> ProxySettings.Trojan(password = fields.getAny("password") ?: return null)

            "ss" -> ProxySettings.Shadowsocks(
                method = fields.getAny("cipher") ?: return null,
                password = fields.getAny("password") ?: return null,
                plugin = fields.getAny("plugin").orEmpty(),
                // The options were dropped on the floor here while the SIP008 path read them, which
                // is how the asymmetry gave itself away. A plugin without its options is a plugin
                // with no mode and no host — it cannot work, and the node has to be refused rather
                // than emitted half-configured.
                pluginOptions = pluginOptions(fields),
            )

            "hysteria2", "hy2" -> ProxySettings.Hysteria2(
                password = fields.getAny("password", "auth") ?: return null,
                obfsType = fields.getAny("obfs", "obfs-type", "obfs_type").orEmpty(),
                obfsPassword = fields.getAny("obfs-password", "obfspassword", "obfs_password").orEmpty(),
                upMbps = fields.getAny("up", "up-mbps", "up_mbps", "upmbps")?.toIntOrNull() ?: 0,
                downMbps = fields.getAny("down", "down-mbps", "down_mbps", "downmbps")?.toIntOrNull() ?: 0,
            )

            "tuic" -> ProxySettings.Tuic(
                uuid = fields.getAny("uuid") ?: return null,
                password = fields.getAny("password").orEmpty(),
                congestionControl = fields.getAny("congestion-controller", "congestion_controller", "congestion-control", "congestion_control", "congestioncontrol") ?: "bbr",
                udpRelayMode = fields.getAny("udp-relay-mode", "udp_relay_mode", "udprelaymode") ?: "native",
                zeroRttHandshake = fields.getAnyBool("reduce-rtt", "reduce_rtt", "zero-rtt-handshake", "zero_rtt_handshake"),
            )

            "anytls" -> ProxySettings.AnyTls(password = fields.getAny("password") ?: return null)

            "socks5" -> ProxySettings.Socks(
                username = fields.getAny("username").orEmpty(),
                password = fields.getAny("password").orEmpty(),
            )

            "http" -> ProxySettings.Http(
                username = fields.getAny("username").orEmpty(),
                password = fields.getAny("password").orEmpty(),
            )

            else -> return null
        }

        return ProxyNode(
            id = "",
            name = fields["name"]?.takeIf { it.isNotBlank() } ?: "$server:$port",
            server = server,
            port = port,
            settings = settings,
            tls = tls(fields, settings),
            transport = transport(fields),
            subscriptionId = subscriptionId,
        ).identified()
    }

    private fun tls(fields: Map<String, String>, settings: ProxySettings): TlsOptions? {
        val explicit = fields.getAnyBool("tls")
        val realityKey = fields.getAny("public-key", "public_key", "publicKey", "pbk", "pubkey", "key")
        // QUIC protocols are always encrypted; Clash omits `tls: true` for them because there is
        // nothing to switch off.
        // Trojan is TLS by definition — there is no plaintext form of it — so Clash configs
        // routinely leave `tls: true` off. Reading that omission as "no TLS" produced an outbound
        // with no security at all, which cannot complete a handshake with any trojan server.
        val implied = settings.protocol.isQuicBased ||
            settings.protocol.name == "ANYTLS" ||
            settings.protocol.name == "TROJAN"
        if (!explicit && realityKey == null && !implied) return null

        return TlsOptions(
            serverName = fields.getAny("servername", "server-name", "server_name", "serverName", "sni", "peer"),
            insecure = fields.getAnyBool("skip-cert-verify", "skip_cert_verify", "insecure", "allow-insecure", "allow_insecure"),
            alpn = fields.getAny("alpn")?.let(::inlineList).orEmpty(),
            fingerprint = fields.getAny("client-fingerprint", "client_fingerprint", "clientFingerprint", "fingerprint", "fp"),
            pinnedCertSha256 = fields
                .getAny("pinned-peer-cert-sha256", "pinnedPeerCertSha256", "pcs")
                .orEmpty(),
            verifyPeerCertByName = fields
                .getAny("verify-peer-cert-by-name", "verifyPeerCertByName", "vcn")
                .orEmpty(),
            reality = realityKey?.let {
                RealityOptions(
                    publicKey = it,
                    shortId = fields.getAny("short-id", "short_id", "shortId", "sid").orEmpty(),
                    spiderX = fields.getAny("spider-x", "spider_x", "spiderX", "spx", "path") ?: "/",
                )
            },
        )
    }

    /** What sing-box can carry; see [ProxyUriParser] for what happens when it cannot. */
    private val KNOWN_NETWORKS =
        setOf("ws", "grpc", "httpupgrade", "xhttp", "splithttp")
    private val PLAIN_NETWORKS = setOf("tcp", "raw", "none", "original")

    private fun transport(fields: Map<String, String>): TransportOptions? =
        when (fields.getAny("network", "type")?.lowercase()) {
            "ws" -> TransportOptions.WebSocket(
                path = fields.getAny("path") ?: "/",
                headers = fields.getAny("Host", "host")?.let { mapOf("Host" to it) }.orEmpty(),
                maxEarlyData = fields.getAny("max-early-data", "max_early_data", "early-data-length")?.toIntOrNull() ?: 0,
                earlyDataHeaderName = fields.getAny("early-data-header-name", "early_data_header_name"),
            )

            "grpc" -> TransportOptions.Grpc(
                serviceName = fields.getAny("grpc-service-name", "grpc_service_name", "serviceName", "service_name", "path").orEmpty(),
            )

            "httpupgrade" -> TransportOptions.HttpUpgrade(
                host = fields.getAny("host").orEmpty(),
                path = fields.getAny("path") ?: "/",
            )

            // Two names for one transport: `splithttp` is what it was called before the rename,
            // and subscriptions still hand out both.
            "xhttp", "splithttp" -> TransportOptions.Xhttp(
                path = fields.getAny("path") ?: "/",
                host = fields.getAny("host").orEmpty(),
                mode = fields.getAny("mode") ?: "auto",
            )

            // `http`/`h2` and `quic` are absent on purpose. The core removed both transports and
            // answers a document naming either with a removed-feature error, so an entry asking for
            // one is refused above rather than accepted here and quietly downgraded to plain TCP.
            else -> null
        }

    /**
     * `plugin-opts` flattened into the `k=v;k=v` string the cores expect.
     *
     * Clash writes them as a nested map, which [inlineMap] has already flattened into the same
     * field map as everything else — so the options are recognised by name rather than by position.
     */
    private fun pluginOptions(fields: Map<String, String>): String =
        listOfNotNull(
            fields.getAny("mode")?.let { "mode=$it" },
            fields.getAny("host")?.let { "host=$it" },
            fields.getAny("path")?.let { "path=$it" },
            fields.getAny("tls")?.takeIf { it == "true" }?.let { "tls" },
        ).joinToString(";")

    private fun Map<String, String>.getAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.takeIf(String::isNotBlank)
        }

    private fun Map<String, String>.getAnyBool(vararg keys: String): Boolean =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.let { v ->
                v.equals("true", ignoreCase = true) || v == "1"
            }
        } ?: false

    // ------------------------------------------------------------- Helpers

    private fun String.indentation(): Int = indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

    /** `key: value` with quotes and trailing comments stripped; null when the line is not a pair. */
    private fun String.toPair(): Pair<String, String>? {
        val separator = indexOf(':').takeIf { it > 0 } ?: return null
        val key = substring(0, separator).trim().trim('"', '\'')
        val value = substring(separator + 1).trim().cleanValue()
        return if (key.isEmpty()) null else key to value
    }

    private fun String.cleanValue(): String = stripComment()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()

    /**
     * Drops a trailing `# comment`, which [toPair] has always claimed to do and never did.
     *
     * An entry written `server: example.com  # main node` produced the host
     * `example.com  # main node`, which nothing can dial, so the server disappeared from the
     * subscription without a word anywhere.
     *
     * Only outside quotes, and only where the `#` follows whitespace — YAML's own rule, and the one
     * that keeps a password like `p#ss` intact.
     */
    private fun String.stripComment(): String {
        var quote: Char? = null
        forEachIndexed { index, ch ->
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' && (index == 0 || this[index - 1].isWhitespace()) ->
                    return substring(0, index).trim()
            }
        }
        return trim()
    }

    /** `{a: 1, b: {c: 2}}` — flattened, because nested keys do not collide with the outer ones. */
    private fun inlineMap(text: String): Map<String, String> {
        val body = text.trim().removePrefix("{").removeSuffix("}")
        val result = mutableMapOf<String, String>()
        var depth = 0
        val piece = StringBuilder()

        fun flush() {
            val part = piece.toString().trim()
            piece.clear()
            if (part.isEmpty()) return
            if (part.contains('{')) {
                result.putAll(inlineMap(part.substringAfter('{').let { "{$it" }))
                part.substringBefore('{').toPair()?.let { (key, value) ->
                    if (value.isNotEmpty()) result[key] = value
                }
            } else {
                part.toPair()?.let { (key, value) -> result[key] = value }
            }
        }

        for (symbol in body) {
            when {
                symbol == '{' -> { depth++; piece.append(symbol) }
                symbol == '}' -> { depth--; piece.append(symbol) }
                symbol == ',' && depth == 0 -> flush()
                else -> piece.append(symbol)
            }
        }
        flush()
        return result
    }

    private fun inlineList(text: String): List<String> = text
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(',')
        .map { it.cleanValue() }
        .filter { it.isNotEmpty() }
}
