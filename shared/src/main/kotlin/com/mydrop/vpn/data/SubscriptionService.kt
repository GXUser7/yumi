package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.parse.SubscriptionBody
import com.mydrop.vpn.core.parse.ProxyUriParser
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.core.parse.SubscriptionParser
import com.mydrop.vpn.core.model.ProbeEndpoint
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * How to reach the running tunnel from inside this process, or null when there is none.
     *
     * A lambda rather than the value: the endpoint changes with every tunnel and this class
     * outlives all of them.
     */
    private val probeEndpoint: () -> ProbeEndpoint? = { null },
) {

    private companion object {
        /** The tunnel publishes its inbound here and nowhere else. */
        private const val LOOPBACK = "127.0.0.1"

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
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
                    android.util.Log.w(
                        "YumiSub",
                        "panel refused: hwidHeaders=${response.hwidRequired} — " +
                            body.nodes.joinToString(" | ") { it.name },
                    )
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

                // Refused here rather than left to fail at connect time. A protocol the core
                // cannot speak makes a row that looks like every other row, pings like every other
                // row and carries nothing — and the user is left comparing servers to work out
                // which of them is the broken one.
                val refused = body.nodes.mapNotNull(XrayConfigFactory::unsupported)
                val carried = body.nodes.filter { XrayConfigFactory.unsupported(it) == null }

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
        get(url, subscription, through = null)
    } catch (direct: java.io.IOException) {
        val probe = probeEndpoint()
        if (probe == null) throw direct
        logs.debug(R.string.log_subscription_via_tunnel, subscription.name)
        try {
            get(url, subscription, through = probe)
        } catch (viaTunnel: java.io.IOException) {
            // The direct failure is the one worth reporting: it names the provider rather than a
            // loopback port the user has never heard of.
            direct.addSuppressed(viaTunnel)
            throw direct
        }
    }

    /**
     * Hands the loopback inbound its credentials, and nothing else.
     *
     * `Authenticator` is process-wide, which is why this checks the requestor before answering: it
     * must never offer these to a real proxy the user has configured, and it has nothing to say
     * about server authentication — the link's own credentials travel as a header, under the
     * same-origin rule above.
     */
    private fun installProbeAuthenticator() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val probe = probeEndpoint() ?: return null
                // Not filtered by requestor type: an HTTP proxy asks as PROXY and Java's SOCKS
                // client asks as SERVER, and the inbound below answers both. The address is what
                // makes this safe — these credentials are only ever offered to our own loopback.
                if (requestingHost != LOOPBACK || requestingPort != probe.port) return null
                return PasswordAuthentication(probe.username, probe.password.toCharArray())
            }
        })
    }

    private fun get(url: String, subscription: Subscription, through: ProbeEndpoint?): Response {
        val origin = URL(url)
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val trusted = sameOrigin(origin, target)
            val opened = if (through == null) {
                target.openConnection()
            } else {
                installProbeAuthenticator()
                // SOCKS rather than HTTP, and the difference is where the proxy sits. Java
                // implements SOCKS underneath the socket, so TLS and everything above it are
                // carried without knowing about it; the HTTP form has to be understood by the
                // connection itself, and Android's implementation quietly declined to use it —
                // the request went straight out and failed exactly as it had without a proxy.
                // The inbound speaks both, so this costs nothing.
                target.openConnection(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, through.port)),
                )
            }
            val connection = (opened as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                // Cross-protocol redirects (http→https) are not followed automatically, so
                // redirects are walked by hand below.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", subscription.userAgentOverride ?: userAgent)

                // Credentials written into the link. HttpURLConnection parses them out of the URL
                // and then never sends them, which reads as a subscription that returns 401 for
                // no reason at all.
                target.userInfo?.takeIf { it.isNotEmpty() && trusted }?.let { credentials ->
                    // The shared decoder, not URLDecoder: a password with a `+` in it is a
                    // password with a `+` in it, not one with a space.
                    val decoded = com.mydrop.vpn.core.parse.urlDecode(credentials)
                    val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray())
                    setRequestProperty("Authorization", "Basic $encoded")
                }

                // Last, so a provider's own header wins over anything assumed above. These are
                // where a panel behind a gateway keeps its token, so they travel under the same
                // rule as the rest of the credentials.
                if (trusted) {
                    subscription.headers.forEach { (name, value) ->
                        runCatching { setRequestProperty(name, value) }
                    }
                }
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                // Subscriptions sit behind caching front-ends that key on the URL and the agent
                // and ignore everything else. One refusal — a device that was not yet bound, a
                // limit that has since been raised — then keeps being replayed to a client whose
                // request has long since become correct.
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                // Panels that do not do device binding ignore these outright; the ones that do
                // refuse to serve a list without them.
                identity()?.takeIf { trusted }?.let {
                    setRequestProperty("x-hwid", it.hwid)
                    setRequestProperty("x-device-os", it.os)
                    setRequestProperty("x-ver-os", it.osVersion)
                    setRequestProperty("x-device-model", it.model)
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return Response(code, "", null, null, null)
                    current = URL(URL(current), location).toString()
                    return@repeat
                }

                val stream = (connection.errorStream ?: connection.inputStream)
                    .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
                // One byte past the ceiling, so a body that is merely at the limit still reads
                // whole while an oversized one is caught. Silently keeping the first 8 MB turned a
                // truncated document into a valid-looking short list, and every server past the
                // cut looked like one the provider had removed.
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
