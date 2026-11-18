package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProviderProfile
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.net.resolveSafeDirectRedirect
import com.mydrop.vpn.core.parse.ProviderProfileParser
import com.mydrop.vpn.core.parse.SubscriptionBody
import com.mydrop.vpn.core.parse.ProxyUriParser
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.core.parse.SubscriptionParser
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.TunnelResponseInputStream
import com.mydrop.vpn.vpn.XrayCore
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What a panel with device binding wants to know about the client asking for the list.
 *
 * [hwid] is generated once per installation and random. Panels of the Remnawave family count
 * devices by this value, so it has to be stable — but deriving it from anything real (the Android
 * ID, the advertising ID, the IMEI) would hand every panel the user ever subscribes to a handle
 * that follows them across apps and reinstalls. A random identifier counts devices just as well
 * and identifies nothing else.
 */
data class DeviceIdentity(
    val hwid: String,
    val os: String,
    val osVersion: String,
    val model: String,
)

/**
 * Fetches and parses subscription documents.
 *
 * Panels key their behaviour off the User-Agent: several only emit the base64 share-link format
 * (rather than a Clash config) when they recognise a proxy client, so a browser-looking agent
 * would get a document this app cannot use.
 *
 * Some go further and refuse to serve anything until the client identifies its device. They do not
 * fail the request when it does not — they answer 200 with a handful of unusable placeholder
 * servers whose *names* carry the instructions, which is how "🚫 Отсутствует передача HWID" ended
 * up in the server list looking like six real servers. Hence both halves of the handling here: the
 * headers go out on every request, and a reply made of placeholders is reported as the refusal it
 * is instead of being imported.
 */
class SubscriptionService(
    private val logs: LogRepository,
    private val strings: Strings,
    private val userAgent: String = "Yumi/0.2.0 (Android)",
    private val identity: () -> DeviceIdentity? = { null },
    /** In-process tunnel bridge used when direct refresh fails while the core is running. */
    private val bridge: TunnelBridge = XrayCore,
) {

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** A panel's subscription link: one opaque id as the whole path, as Remnawave writes them. */
        val PANEL_LINK_PATH = Regex("^/[A-Za-z0-9_-]{8,64}/?$")

        /**
         * Appended to this app's own agent when asking for Xray documents; see [autoSelectVariant].
         * The name of a client panels already hand documents to, said the way browsers have long
         * said `Mozilla/5.0 (compatible; …)`: this app still names itself first.
         */
        const val DOCUMENTS_AGENT_SUFFIX = " (compatible; Happ)"
    }

    suspend fun fetch(subscription: Subscription): SubscriptionUpdate = withContext(Dispatchers.IO) {
        runCatching { fetchInternal(subscription) }
            .getOrElse { error ->
                val message = error.message
                    ?: error::class.simpleName
                    ?: strings.get(R.string.error_network)
                logs.warn(R.string.log_subscription_message, subscription.name, message)
                SubscriptionUpdate.Failure(subscription.id, message)
            }
    }

    private fun fetchInternal(subscription: Subscription): SubscriptionUpdate {
        // Direct first, through the tunnel second, and the order is not arbitrary.
        //
        // The app excludes itself from its own tunnel, which is what makes its probes describe the
        // phone's real network rather than the proxy's. The cost of that shows up under an
        // allow-list regime, where only approved domains resolve and connect: the tunnel is up and
        // carrying the user's traffic while the app itself cannot reach its own provider, so the
        // subscription silently stops updating exactly when it matters most. Observed on a
        // Russian carrier: `Failed to connect to sub.example.com` with a healthy tunnel alongside.
        //
        // So a refusal is retried through the loopback inbound the tunnel already publishes. Only
        // on failure — the direct road is faster, needs no tunnel, and keeps working when the
        // provider is reachable and the proxy is not.
        val response = fetch(subscription.url, subscription)

        if (response.code !in 200..299) {
            return SubscriptionUpdate.Failure(
                subscription.id,
                strings.get(R.string.error_http, response.code),
            )
        }

        return when (val body = SubscriptionParser.parse(response.body, subscription.id)) {
            is SubscriptionBody.Nodes -> {
                // A list made entirely of placeholders is a refusal dressed as a list. Importing it
                // fills the server list with six unusable entries whose names are an instruction.
                if (body.nodes.isNotEmpty() && body.nodes.all { it.isPlaceholder() }) {
                    // The panel writes its own explanation into the names — device limit reached,
                    // binding not enabled, subscription expired — so it is quoted rather than
                    // replaced by a guess at which of those it was. The rest of the names carry
                    // the instructions and go to the journal.
                    val notice = body.nodes.first().name.trim()
                    body.nodes.forEach {
                        logs.warn(R.string.log_subscription_message, subscription.name, it.name)
                    }
                    runCatching {
                        android.util.Log.w(
                            "YumiSub",
                            "panel refused: hwidHeaders=${response.hwidRequired} — " +
                                body.nodes.joinToString(" | ") { it.name },
                        )
                    }
                    // The panel is explicit about this one in a header, and it is the case a user
                    // can actually do something about — so it is named rather than quoted.
                    if (response.deviceLimitReached) {
                        return SubscriptionUpdate.Failure(
                            subscription.id,
                            strings.get(R.string.error_device_limit),
                        )
                    }
                    return SubscriptionUpdate.Failure(
                        subscription.id,
                        strings.get(R.string.error_panel_notice, notice),
                    )
                }

                // The same subscription as Xray documents, when this list flattened the provider's
                // auto-select servers into their last resort; see autoSelectVariant.
                val documents = autoSelectVariant(subscription, body.nodes)
                val nodes = documents?.nodes ?: body.nodes
                // The provider's routing, from whichever answer was documents: this one, when the
                // panel already sent them, or the second request above.
                val profiles = ProviderProfileParser.parse(response.body) ?: documents?.profiles

                // Refused here rather than left to fail at connect time. A protocol the core
                // cannot speak makes a row that looks like every other row, pings like every other
                // row and carries nothing — and the user is left comparing servers to work out
                // which of them is the broken one. A group keeps whichever members it can carry.
                val carried = nodes.mapNotNull(XrayConfigFactory::carriable)
                val refused = nodes
                    .filter { XrayConfigFactory.carriable(it) == null }
                    .mapNotNull(XrayConfigFactory::unsupported)

                logs.info(
                    R.string.log_subscription_received,
                    subscription.name,
                    strings.plural(R.plurals.servers, carried.size),
                )

                if (refused.isNotEmpty()) {
                    logs.warn(
                        R.string.log_skipped_unsupported_protocol,
                        refused.size,
                        refused.distinct().sorted().joinToString(", "),
                    )
                }

                // Named rather than hidden. A server the provider lists and the app refuses is
                // worth a line: without one the count simply comes up short, which reads as a
                // parsing failure rather than as the deliberate refusal it is.
                ProxyUriParser.unsupportedTransports(response.body)
                    .takeIf { it.isNotEmpty() }
                    ?.let { skipped ->
                        logs.warn(
                            R.string.log_skipped_unsupported_transport,
                            skipped.values.sum(),
                            skipped.keys.joinToString(", "),
                        )
                    }

                SubscriptionUpdate.Success(
                    subscription = subscription.copy(
                        userInfo = SubscriptionParser.parseUserInfo(response.userInfoHeader)
                            ?: subscription.userInfo,
                        remoteTitle = response.profileTitle ?: subscription.remoteTitle,
                        webPageUrl = response.webPageUrl ?: subscription.webPageUrl,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        lastError = null,
                    ),
                    nodes = carried,
                    addedCount = 0,
                    removedCount = 0,
                    providerRouting = profiles?.let { byName ->
                        ProviderProfileParser.routingFor(carried, byName).also { routing ->
                            logs.trace(
                                "YumiSub",
                                "${subscription.name}: provider routing for " +
                                    "${routing.byNode.size}/${carried.size} servers, " +
                                    "${routing.profiles.size} distinct",
                            )
                        }
                    },
                )
            }

            is SubscriptionBody.UnsupportedFormat ->
                SubscriptionUpdate.Failure(
                    subscription.id,
                    strings.get(R.string.error_unsupported_format, body.format),
                )

            is SubscriptionBody.Empty ->
                SubscriptionUpdate.Failure(subscription.id, strings.get(body.reason.messageRes))
        }
    }

    /**
     * The subscription again, asked for as Xray documents, when the list it answered with cannot
     * hold what the provider actually offers — and the auto-select servers from that answer put in
     * the places their flattened links held.
     *
     * A share link is one server. A provider's auto-select server is several — a balancer over
     * candidates, see [com.mydrop.vpn.core.model.NodeGroup] — and a panel asked for links flattens
     * it into one of them, the balancer's last resort. Which format a panel answers with is decided
     * by the User-Agent, and this app's is not one a panel knows; the provider's own client is
     * handed the documents and this app was handed the flattened list. The journal showed the
     * result: "LTE Авто" servers that carried nothing here and worked there.
     *
     * Two ways of asking are tried, in order. Remnawave serves a named format under a suffix of a
     * link shaped like `https://host/<id>`, and `json` is Xray's. Not every panel behind such a link
     * does: Quattro's answered the suffix with a 404 and chose by the agent alone, handing documents
     * to any agent that mentions Happ, Streisand, INCY or v2raytun — Remnawave's own default
     * response rules — and links to everyone else, this app included. So the second request is the
     * link itself with [DOCUMENTS_AGENT_SUFFIX] after this app's name, unless the user set an agent
     * of their own, which is theirs to choose.
     *
     * Only the groups are taken from the documents, never the list. The same answer wrote Quattro's
     * Hysteria2 servers as Xray `hysteria` outbounds, which this parser does not read; taking the
     * documents whole would have quietly removed the servers a friend's phone was riding. A group
     * replaces the link of the same name, which keeps its row, its place and — through the name —
     * the selection; a group no link is named after is added at the end. A document without a
     * group, a request that fails, or an answer that is not documents changes nothing.
     *
     * The routing and DNS in those documents are read on the way past — see [ProviderProfileParser]
     * — from the first answer that was documents, groups or not. A provider whose documents carry
     * rules but no auto-select server still has rules worth keeping.
     */
    private fun autoSelectVariant(subscription: Subscription, links: List<ProxyNode>): DocumentsAnswer? {
        if (links.any { it.group != null }) return null
        val url = runCatching { URL(subscription.url) }.getOrNull() ?: return null

        val variants = buildList {
            if (url.query == null && PANEL_LINK_PATH.matches(url.path.orEmpty())) {
                add(Triple("suffix", subscription.url.trimEnd('/') + "/json", subscription))
            }
            if (subscription.userAgentOverride == null) {
                val asking = subscription.copy(userAgentOverride = userAgent + DOCUMENTS_AGENT_SUFFIX)
                add(Triple("agent", subscription.url, asking))
            }
        }
        var profiles: Map<String, ProviderProfile>? = null
        for ((how, variantUrl, asking) in variants) {
            val response = runCatching { fetch(variantUrl, asking) }.getOrNull()
            val answered = response?.takeIf { it.code in 200..299 }
            val body = answered?.let { SubscriptionParser.parse(it.body, subscription.id) }
            if (profiles == null) profiles = answered?.let { ProviderProfileParser.parse(it.body) }
            val groups = (body as? SubscriptionBody.Nodes)?.nodes.orEmpty().filter { it.group != null }
            logs.trace(
                "YumiSub",
                "${subscription.name}: documents by $how: " + when {
                    response == null -> "no answer"
                    response.code !in 200..299 -> "HTTP ${response.code}"
                    body !is SubscriptionBody.Nodes -> "not a server list"
                    else -> "${body.nodes.size} servers, ${groups.size} auto-select"
                },
            )
            if (groups.isNotEmpty()) return DocumentsAnswer(withGroups(links, groups), profiles)
        }
        return profiles?.let { DocumentsAnswer(nodes = null, profiles = it) }
    }

    /** What asking for documents produced; see [autoSelectVariant]. */
    private class DocumentsAnswer(
        /** The list with its auto-select servers restored, or null when there were none to restore. */
        val nodes: List<ProxyNode>?,
        val profiles: Map<String, ProviderProfile>?,
    )

    /** [links] with each of [groups] in the place of the link named after it; see [autoSelectVariant]. */
    private fun withGroups(links: List<ProxyNode>, groups: List<ProxyNode>): List<ProxyNode> {
        val byName = groups.associateBy { it.name }
        val names = links.mapTo(HashSet()) { it.name }
        return links.map { link -> byName[link.name] ?: link } + groups.filter { it.name !in names }
    }

    private data class Response(
        val code: Int,
        val body: String,
        val userInfoHeader: String?,
        val profileTitle: String?,
        val webPageUrl: String?,
        /** The panel announced device binding through one of its `x-hwid-*` headers. */
        val hwidRequired: Boolean = false,
        /** It also said the account has no free device slots left. */
        val deviceLimitReached: Boolean = false,
    )

    /**
     * A server that cannot be dialled: the all-zero UUID at `0.0.0.0:1` that panels use to carry a
     * message in the name field. Matched on the address rather than the text, which is written by
     * whoever runs the panel and comes in any language.
     */
    private fun ProxyNode.isPlaceholder(): Boolean =
        server == "0.0.0.0" || server.isBlank() || port <= 1

    /**
     * Same host, same scheme, same port — the test for whether this request may still carry the
     * subscription's credentials.
     *
     * Redirects are walked by hand here, and every hop used to be given the `Authorization` header
     * built from the link's userinfo along with the `x-hwid` identity, whatever host it pointed
     * at. One `302` from a panel — compromised, or simply hostile — was enough to hand a login and
     * a device identifier to a third party. A downgrade from https to http counts as a different
     * origin for the same reason: the credentials would go out in the clear.
     */
    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b) &&
            notDowngraded(a, b)

    /**
     * The scheme test, which used to be plain equality and cost more than it defended.
     *
     * Refusing a downgrade is the whole point: https → http would put the login and the device id
     * on the wire in the clear. The reverse is the opposite of a risk, and equality refused it
     * anyway — so a subscription written `http://` that redirects to `https://`, which is what a
     * panel that has since fixed its certificate does, silently lost its credentials and started
     * answering 401.
     */
    private fun notDowngraded(from: URL, to: URL): Boolean =
        to.protocol.equals("https", ignoreCase = true) ||
            from.protocol.equals(to.protocol, ignoreCase = true)

    private fun effectivePort(url: URL): Int =
        if (url.port != -1) url.port else url.defaultPort

    /**
     * The subscription body, from wherever it can be had.
     *
     * @throws java.io.IOException when neither road works; the caller turns that into the message
     *   the user sees.
     */
    private fun fetch(url: String, subscription: Subscription): Response = try {
        getDirect(url, subscription)
    } catch (direct: java.io.IOException) {
        if (!bridge.isRunning) throw direct
        logs.debug(R.string.log_subscription_via_tunnel, subscription.name)
        try {
            getViaTunnel(url, subscription)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (viaTunnel: Exception) {
            // The direct failure is the one worth reporting: it names the provider rather than an
            // internal tunnel dial detail.
            direct.addSuppressed(viaTunnel)
            throw direct
        }
    }

    private fun buildRequestHeaders(
        origin: URL,
        target: URL,
        subscription: Subscription,
    ): Map<String, String> {
        val trusted = sameOrigin(origin, target)
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = subscription.userAgentOverride ?: userAgent
        target.userInfo?.takeIf { it.isNotEmpty() && trusted }?.let { credentials ->
            val decoded = com.mydrop.vpn.core.parse.urlDecode(credentials)
            val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray())
            headers["Authorization"] = "Basic $encoded"
        }
        if (trusted) {
            subscription.headers.forEach { (name, value) ->
                headers[name] = value
            }
        }
        headers["Accept"] = "*/*"
        headers["Accept-Encoding"] = "gzip"
        headers["Cache-Control"] = "no-cache"
        headers["Pragma"] = "no-cache"
        identity()?.takeIf { trusted }?.let {
            headers["x-hwid"] = it.hwid
            headers["x-device-os"] = it.os
            headers["x-ver-os"] = it.osVersion
            headers["x-device-model"] = it.model
        }
        return headers
    }

    private fun getViaTunnel(url: String, subscription: Subscription): Response {
        val origin = URL(url)
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val headers = buildRequestHeaders(origin, target, subscription)
            val headersJson = buildJsonObject {
                headers.forEach { (name, value) -> put(name, value) }
            }.toString()

            val response = bridge.openHTTP(
                method = "GET",
                targetURL = current,
                headersJSON = headersJson,
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = READ_TIMEOUT_MILLIS,
                resolveDNS = false,
            )
            try {
                val code = response.statusCode
                if (code in 300..399) {
                    val location = response.location.takeIf { it.isNotBlank() }
                        ?: return Response(code, "", null, null, null)
                    current = URL(URL(current), location).toString()
                    return@repeat
                }

                val rawStream: InputStream = TunnelResponseInputStream(response)
                val stream = if (response.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val raw = stream.use { input -> input.readAtMost(MAX_BODY_BYTES + 1) }
                if (raw.size > MAX_BODY_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                val body = String(raw, Charsets.UTF_8)

                val headersObj = response.headersJSON
                    .takeIf { it.isNotBlank() }
                    ?.let { rawJson ->
                        runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                    }
                fun headerValue(name: String): String? =
                    headersObj?.get(name.lowercase())?.jsonPrimitive?.contentOrNull
                        ?: response.header(name).takeIf { it.isNotEmpty() }
                fun hasHeader(name: String): Boolean =
                    headersObj?.containsKey(name.lowercase()) == true ||
                        response.header(name).isNotEmpty()

                return Response(
                    code = code,
                    body = body,
                    userInfoHeader = headerValue("subscription-userinfo"),
                    profileTitle = headerValue("profile-title")?.let(::decodeProfileTitle),
                    webPageUrl = headerValue("profile-web-page-url"),
                    hwidRequired = hasHeader("x-hwid-active") || hasHeader("x-hwid-not-supported"),
                    deviceLimitReached = hasHeader("x-hwid-max-devices-reached"),
                )
            } finally {
                runCatching { response.close() }
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun getDirect(url: String, subscription: Subscription): Response {
        val origin = URL(url)
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val headers = buildRequestHeaders(origin, target, subscription)
            val connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                headers.forEach { (name, value) ->
                    runCatching { setRequestProperty(name, value) }
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return Response(code, "", null, null, null)
                    current = resolveSafeDirectRedirect(target, location).toString()
                    return@repeat
                }

                val stream = (connection.errorStream ?: connection.inputStream)
                    .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
                val raw = stream.use { input -> input.readAtMost(MAX_BODY_BYTES + 1) }
                if (raw.size > MAX_BODY_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                val body = String(raw, Charsets.UTF_8)

                return Response(
                    code = code,
                    body = body,
                    userInfoHeader = connection.getHeaderField("subscription-userinfo"),
                    profileTitle = connection.getHeaderField("profile-title")
                        ?.let(::decodeProfileTitle),
                    webPageUrl = connection.getHeaderField("profile-web-page-url"),
                    hwidRequired = connection.getHeaderField("x-hwid-active") != null ||
                        connection.getHeaderField("x-hwid-not-supported") != null,
                    deviceLimitReached =
                        connection.getHeaderField("x-hwid-max-devices-reached") != null,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    /**
     * Reads up to [limit] bytes, by hand.
     *
     * `InputStream.readNBytes` is the obvious way to write this and it is an API 33 method, while
     * the app runs from API 26 — so on Android 8 through 12 every subscription refresh ended in a
     * `NoSuchMethodError` rather than a server list. It went unnoticed because nothing built or
     * ran the app below 33; Android lint is what named it.
     */
    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** `profile-title` is often sent as `base64:<...>` to survive non-ASCII provider names. */
    private fun decodeProfileTitle(raw: String): String {
        val prefix = "base64:"
        if (!raw.startsWith(prefix)) return raw
        return com.mydrop.vpn.core.parse.base64DecodeOrNull(raw.removePrefix(prefix)) ?: raw
    }
}
