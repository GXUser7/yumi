package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.parse.SubscriptionBody
import com.mydrop.vpn.core.parse.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream

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
    private val userAgent: String = "Yumi/0.2.0 (Android)",
    private val identity: () -> DeviceIdentity? = { null },
) {

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
    }

    suspend fun fetch(subscription: Subscription): SubscriptionUpdate = withContext(Dispatchers.IO) {
        runCatching { fetchInternal(subscription) }
            .getOrElse { error ->
                val message = error.message ?: error::class.simpleName ?: "Ошибка сети"
                logs.error("Подписка «${subscription.name}»: $message")
                SubscriptionUpdate.Failure(subscription.id, message)
            }
    }

    private fun fetchInternal(subscription: Subscription): SubscriptionUpdate {
        val response = get(subscription.url, subscription)

        if (response.code !in 200..299) {
            return SubscriptionUpdate.Failure(subscription.id, "HTTP ${response.code}")
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
                    body.nodes.forEach { logs.warn("Подписка «${subscription.name}»: ${it.name}") }
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
                            "Лимит устройств исчерпан — отвяжите лишние у провайдера подписки",
                        )
                    }
                    return SubscriptionUpdate.Failure(subscription.id, "Панель: $notice")
                }

                logs.info("Подписка «${subscription.name}»: получено ${body.nodes.size} серверов")
                SubscriptionUpdate.Success(
                    subscription = subscription.copy(
                        userInfo = SubscriptionParser.parseUserInfo(response.userInfoHeader)
                            ?: subscription.userInfo,
                        remoteTitle = response.profileTitle ?: subscription.remoteTitle,
                        webPageUrl = response.webPageUrl ?: subscription.webPageUrl,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        lastError = null,
                    ),
                    nodes = body.nodes,
                    addedCount = 0,
                    removedCount = 0,
                )
            }

            is SubscriptionBody.UnsupportedFormat ->
                SubscriptionUpdate.Failure(
                    subscription.id,
                    "Формат ${body.format} пока не поддерживается",
                )

            is SubscriptionBody.Empty ->
                SubscriptionUpdate.Failure(subscription.id, body.reason)
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

    private fun get(url: String, subscription: Subscription): Response {
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val connection = (target.openConnection() as HttpURLConnection).apply {
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
                target.userInfo?.takeIf { it.isNotEmpty() }?.let { credentials ->
                    val decoded = java.net.URLDecoder.decode(credentials, "UTF-8")
                    val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray())
                    setRequestProperty("Authorization", "Basic $encoded")
                }

                // Last, so a provider's own header wins over anything assumed above.
                subscription.headers.forEach { (name, value) ->
                    runCatching { setRequestProperty(name, value) }
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
                identity()?.let {
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
                val body = stream.use { input ->
                    String(input.readNBytes(MAX_BODY_BYTES.toInt()), Charsets.UTF_8)
                }

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
        throw IllegalStateException("Слишком много перенаправлений")
    }

    /** `profile-title` is often sent as `base64:<...>` to survive non-ASCII provider names. */
    private fun decodeProfileTitle(raw: String): String {
        val prefix = "base64:"
        if (!raw.startsWith(prefix)) return raw
        return com.mydrop.vpn.core.parse.base64DecodeOrNull(raw.removePrefix(prefix)) ?: raw
    }
}
