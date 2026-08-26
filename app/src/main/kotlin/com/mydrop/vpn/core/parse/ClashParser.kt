package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
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
        val port = fields["port"]?.toIntOrNull() ?: return null

        // Same reasoning as in ProxyUriParser: a stream transport the core cannot speak makes the
        // node unusable, and keeping it minus the transport produces a server that connects and
        // then carries nothing.
        fields["network"]?.lowercase()?.let { declared ->
            if (declared !in KNOWN_NETWORKS && declared !in PLAIN_NETWORKS) return null
        }

        val settings = when (fields["type"]?.lowercase()) {
            "vless" -> ProxySettings.Vless(
                uuid = fields["uuid"] ?: return null,
                flow = fields["flow"].orEmpty(),
            )

            "vmess" -> ProxySettings.Vmess(
                uuid = fields["uuid"] ?: return null,
                alterId = fields["alterId"]?.toIntOrNull() ?: fields["alterid"]?.toIntOrNull() ?: 0,
                security = fields["cipher"] ?: "auto",
            )

            "trojan" -> ProxySettings.Trojan(password = fields["password"] ?: return null)

            "ss" -> ProxySettings.Shadowsocks(
                method = fields["cipher"] ?: return null,
                password = fields["password"] ?: return null,
                plugin = fields["plugin"].orEmpty(),
            )

            "hysteria2", "hy2" -> ProxySettings.Hysteria2(
                password = fields["password"] ?: fields["auth"] ?: return null,
            )

            "tuic" -> ProxySettings.Tuic(
                uuid = fields["uuid"] ?: return null,
                password = fields["password"].orEmpty(),
            )

            "anytls" -> ProxySettings.AnyTls(password = fields["password"] ?: return null)

            "socks5" -> ProxySettings.Socks(
                username = fields["username"].orEmpty(),
                password = fields["password"].orEmpty(),
            )

            "http" -> ProxySettings.Http(
                username = fields["username"].orEmpty(),
                password = fields["password"].orEmpty(),
            )

            else -> return null
        }

        return ProxyNode(
            id = ProxyNode.stableId(server, port, settings, subscriptionId),
            name = fields["name"]?.takeIf { it.isNotBlank() } ?: "$server:$port",
            server = server,
            port = port,
            settings = settings,
            tls = tls(fields, settings),
            transport = transport(fields),
            subscriptionId = subscriptionId,
        )
    }

    private fun tls(fields: Map<String, String>, settings: ProxySettings): TlsOptions? {
        val explicit = fields["tls"]?.toBooleanStrictOrNull() ?: false
        val reality = fields["public-key"]?.takeIf { it.isNotBlank() }
        // QUIC protocols are always encrypted; Clash omits `tls: true` for them because there is
        // nothing to switch off.
        val implied = settings.protocol.isQuicBased || settings.protocol.name == "ANYTLS"
        if (!explicit && reality == null && !implied) return null

        return TlsOptions(
            serverName = fields["servername"] ?: fields["sni"] ?: fields["peer"],
            insecure = fields["skip-cert-verify"]?.toBooleanStrictOrNull() ?: false,
            alpn = fields["alpn"]?.let(::inlineList).orEmpty(),
            fingerprint = fields["client-fingerprint"],
            reality = reality?.let {
                RealityOptions(publicKey = it, shortId = fields["short-id"].orEmpty())
            },
        )
    }

    /** What sing-box can carry; see [ProxyUriParser] for what happens when it cannot. */
    private val KNOWN_NETWORKS = setOf("ws", "grpc", "http", "h2", "httpupgrade", "quic")
    private val PLAIN_NETWORKS = setOf("tcp", "raw", "none", "original")

    private fun transport(fields: Map<String, String>): TransportOptions? =
        when (fields["network"]?.lowercase()) {
            "ws" -> TransportOptions.WebSocket(
                path = fields["path"] ?: "/",
                headers = fields["Host"]?.let { mapOf("Host" to it) }
                    ?: fields["host"]?.let { mapOf("Host" to it) }.orEmpty(),
            )

            "grpc" -> TransportOptions.Grpc(serviceName = fields["grpc-service-name"].orEmpty())

            "http" -> TransportOptions.Http(
                host = fields["host"]?.let(::listOf).orEmpty(),
                path = fields["path"] ?: "/",
            )

            "httpupgrade" -> TransportOptions.HttpUpgrade(
                host = fields["host"].orEmpty(),
                path = fields["path"] ?: "/",
            )

            else -> null
        }

    // ------------------------------------------------------------- Helpers

    private fun String.indentation(): Int = indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

    /** `key: value` with quotes and trailing comments stripped; null when the line is not a pair. */
    private fun String.toPair(): Pair<String, String>? {
        val separator = indexOf(':').takeIf { it > 0 } ?: return null
        val key = substring(0, separator).trim().trim('"', '\'')
        val value = substring(separator + 1).trim().cleanValue()
        return if (key.isEmpty()) null else key to value
    }

    private fun String.cleanValue(): String = trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()

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
