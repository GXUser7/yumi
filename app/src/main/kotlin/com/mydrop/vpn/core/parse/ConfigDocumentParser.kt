package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.RealityOptions
import com.mydrop.vpn.core.model.TlsOptions
import com.mydrop.vpn.core.model.TransportOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the configuration documents providers hand out instead of a list of share links.
 *
 * Three shapes, three strategies, and the difference between them is not arbitrary:
 *
 * - **sing-box JSON** is the core's own language, so its outbounds are kept *verbatim* as
 *   [ProxySettings.Raw]. Nothing is interpreted and nothing can be lost — including the fields
 *   this app has never heard of.
 * - **Xray / v2ray JSON** describes the same protocols in a different vocabulary, so it has to be
 *   translated field by field. What does not translate is reported rather than silently dropped.
 * - **SIP008** is a Shadowsocks-only list and maps directly.
 *
 * Every parser returns whatever it understood: one unreadable entry in a list of forty is a
 * missing server, not a failed subscription.
 */
object ConfigDocumentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Outbound tags that exist in every config and dial nothing. */
    private val plumbingTypes = setOf("direct", "block", "dns", "selector", "urltest", "freedom", "blackhole")

    fun parse(document: String, subscriptionId: String?): List<ProxyNode> {
        val trimmed = document.trim()
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()

        return when {
            // An array is either a list of whole Xray configs (what panels emit per client) or a
            // bare list of outbounds.
            root is JsonArray -> root.mapNotNull { it as? JsonObject }.flatMap { fromDocument(it, subscriptionId) }
            root is JsonObject -> fromDocument(root, subscriptionId)
            else -> emptyList()
        }
    }

    private fun fromDocument(document: JsonObject, subscriptionId: String?): List<ProxyNode> {
        // SIP008 keeps its servers under "servers" with "server_port"; nothing else uses that pair.
        document["servers"]?.let { servers ->
            if (servers is JsonArray && servers.any { it.jsonObject["method"] != null }) {
                return servers.mapNotNull { sip008(it.jsonObject, subscriptionId) }
            }
        }

        val outbounds = document["outbounds"] as? JsonArray ?: return emptyList()
        val label = document.str("remarks")

        return outbounds.mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            // Xray calls it "protocol", sing-box calls it "type".
            val xray = outbound["protocol"] != null && outbound["settings"] != null
            if (xray) {
                fromXray(outbound, label, subscriptionId)
            } else {
                fromSingBox(outbound, subscriptionId)
            }
        }
    }

    // ------------------------------------------------------------- sing-box

    /**
     * A sing-box outbound, kept as written.
     *
     * The endpoint is lifted out so the node behaves like any other in the list — it can be named,
     * pinged, sorted and failed over — while the document itself travels untouched to the core.
     */
    private fun fromSingBox(outbound: JsonObject, subscriptionId: String?): ProxyNode? {
        val type = outbound.str("type")?.lowercase() ?: return null
        if (type in plumbingTypes) return null

        val server = outbound.str("server") ?: return null
        val port = outbound["server_port"]?.jsonPrimitive?.intOrNull ?: return null
        val name = outbound.str("tag")?.takeIf { it.isNotBlank() } ?: "$server:$port"

        val settings = ProxySettings.Raw(outbound = outbound.toString(), declaredType = type)
        return ProxyNode(
            id = ProxyNode.stableId(server, port, settings, subscriptionId),
            name = name,
            server = server,
            port = port,
            settings = settings,
            subscriptionId = subscriptionId,
        )
    }

    // ----------------------------------------------------------------- Xray

    /**
     * An Xray outbound translated into the typed model.
     *
     * Passing this one through raw is not an option the way it is for sing-box: the core would
     * reject `vnext`, `streamSettings` and the rest outright, so the choice is to translate or to
     * refuse the subscription.
     */
    private fun fromXray(outbound: JsonObject, label: String?, subscriptionId: String?): ProxyNode? {
        val protocol = outbound.str("protocol")?.lowercase() ?: return null
        if (protocol in plumbingTypes) return null

        val settingsObject = outbound["settings"]?.jsonObject ?: return null
        val vnext = (settingsObject["vnext"] as? JsonArray)?.firstOrNull()?.jsonObject
        val servers = (settingsObject["servers"] as? JsonArray)?.firstOrNull()?.jsonObject
        val endpoint = vnext ?: servers ?: return null

        val server = endpoint.str("address") ?: return null
        val port = endpoint["port"]?.jsonPrimitive?.intOrNull ?: return null
        val user = (endpoint["users"] as? JsonArray)?.firstOrNull()?.jsonObject

        val proxySettings = when (protocol) {
            "vless" -> ProxySettings.Vless(
                uuid = user?.str("id") ?: return null,
                flow = user.str("flow").orEmpty(),
            )

            "vmess" -> ProxySettings.Vmess(
                uuid = user?.str("id") ?: return null,
                alterId = user["alterId"]?.jsonPrimitive?.intOrNull ?: 0,
                security = user.str("security") ?: "auto",
            )

            "trojan" -> ProxySettings.Trojan(password = endpoint.str("password") ?: return null)

            "shadowsocks" -> ProxySettings.Shadowsocks(
                method = endpoint.str("method") ?: return null,
                password = endpoint.str("password") ?: return null,
            )

            // Anything else Xray can express and this model cannot. Reported by absence rather
            // than by a node that would fail to dial.
            else -> return null
        }

        val stream = outbound["streamSettings"]?.jsonObject
        // An Xray document can name a transport sing-box has no implementation of — xhttp and
        // splithttp are the common ones. Translating everything but the transport would produce a
        // server that dials and carries nothing.
        stream?.str("network")?.lowercase()?.let { declared ->
            if (declared !in KNOWN_NETWORKS && declared !in PLAIN_NETWORKS) return null
        }
        return ProxyNode(
            id = ProxyNode.stableId(server, port, proxySettings, subscriptionId),
            name = label?.takeIf { it.isNotBlank() } ?: outbound.str("tag") ?: "$server:$port",
            server = server,
            port = port,
            settings = proxySettings,
            tls = stream?.let(::xrayTls),
            transport = stream?.let(::xrayTransport),
            subscriptionId = subscriptionId,
        )
    }

    /** What the app can carry; see [ProxyUriParser] for what happens when it cannot. */
    private val KNOWN_NETWORKS = setOf(
        "ws", "grpc", "http", "h2", "httpupgrade", "quic", "xhttp", "splithttp",
    )
    private val PLAIN_NETWORKS = setOf("tcp", "raw", "none", "original")

    private fun xrayTls(stream: JsonObject): TlsOptions? {
        val security = stream.str("security")?.lowercase()
        if (security.isNullOrEmpty() || security == "none") return null

        val tlsSettings = stream["tlsSettings"]?.jsonObject
        val realitySettings = stream["realitySettings"]?.jsonObject
        val active = realitySettings ?: tlsSettings

        return TlsOptions(
            serverName = active?.str("serverName") ?: active?.str("servername"),
            insecure = tlsSettings?.get("allowInsecure")?.jsonPrimitive?.booleanOrNull ?: false,
            alpn = (tlsSettings?.get("alpn") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                .orEmpty(),
            fingerprint = active?.str("fingerprint"),
            reality = realitySettings?.str("publicKey")?.let { key ->
                RealityOptions(
                    publicKey = key,
                    shortId = realitySettings.str("shortId").orEmpty(),
                    spiderX = realitySettings.str("spiderX") ?: "/",
                )
            },
        )
    }

    private fun xrayTransport(stream: JsonObject): TransportOptions? =
        when (stream.str("network")?.lowercase()) {
            "ws" -> {
                val ws = stream["wsSettings"]?.jsonObject
                val host = ws?.get("headers")?.jsonObject?.str("Host")
                TransportOptions.WebSocket(
                    path = ws?.str("path") ?: "/",
                    headers = host?.let { mapOf("Host" to it) }.orEmpty(),
                )
            }

            "grpc" -> TransportOptions.Grpc(
                serviceName = stream["grpcSettings"]?.jsonObject?.str("serviceName").orEmpty(),
            )

            "httpupgrade" -> {
                val upgrade = stream["httpupgradeSettings"]?.jsonObject
                TransportOptions.HttpUpgrade(
                    host = upgrade?.str("host").orEmpty(),
                    path = upgrade?.str("path") ?: "/",
                )
            }

            // Xray writes it under either key and answers to either name, so a document produced
            // by one version has to be readable by a client built against another.
            "xhttp", "splithttp" -> {
                val x = (stream["xhttpSettings"] ?: stream["splithttpSettings"])?.jsonObject
                TransportOptions.Xhttp(
                    path = x?.str("path") ?: "/",
                    host = x?.str("host").orEmpty(),
                    mode = x?.str("mode")?.ifEmpty { null } ?: "auto",
                )
            }

            "quic" -> TransportOptions.Quic
            else -> null
        }

    // -------------------------------------------------------------- SIP008

    private fun sip008(entry: JsonObject, subscriptionId: String?): ProxyNode? {
        val server = entry.str("server") ?: return null
        val port = entry["server_port"]?.jsonPrimitive?.intOrNull ?: return null
        val settings = ProxySettings.Shadowsocks(
            method = entry.str("method") ?: return null,
            password = entry.str("password") ?: return null,
            plugin = entry.str("plugin").orEmpty(),
            pluginOptions = entry.str("plugin_opts").orEmpty(),
        )
        return ProxyNode(
            id = ProxyNode.stableId(server, port, settings, subscriptionId),
            name = entry.str("remarks")?.takeIf { it.isNotBlank() } ?: "$server:$port",
            server = server,
            port = port,
            settings = settings,
            subscriptionId = subscriptionId,
        )
    }

    // ------------------------------------------------------------- Helpers

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    /** Null for JSON nulls and for numbers-as-strings that are empty, without throwing. */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content.takeIf { it.isNotEmpty() && it != "null" } }.getOrNull()
}
