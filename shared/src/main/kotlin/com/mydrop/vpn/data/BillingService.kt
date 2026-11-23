package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.BillingCheckoutRequest
import com.mydrop.vpn.core.model.BillingCheckoutResponse
import com.mydrop.vpn.core.model.BillingErrorBody
import com.mydrop.vpn.core.model.BillingManifest
import com.mydrop.vpn.core.net.resolveSafeDirectRedirect
import com.mydrop.vpn.core.net.sanitizeBillingUrl
import com.mydrop.vpn.core.net.sanitizePortalUrl
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.TunnelResponseInputStream
import com.mydrop.vpn.vpn.XrayCore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface BillingResult<out T> {
    data class Success<T>(val value: T) : BillingResult<T>
    data class Failure(val message: String, val errorCode: String? = null) : BillingResult<Nothing>
}

/**
 * Fetches Open Subscription Billing manifests and initiates checkout orders.
 *
 * Unlike [SubscriptionService], this client NEVER attaches `x-hwid`, `x-device-*`, or subscription
 * `Authorization` credentials: billing endpoints frequently live on a shop bot's domain that is
 * separate from the proxy panel itself.
 */
class BillingService(
    private val logs: LogRepository,
    private val strings: Strings,
    private val userAgent: String = "Yumi/2.3 (Android)",
    private val bridge: TunnelBridge = XrayCore,
    private val allowLocalHttp: Boolean = false,
) {

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_REDIRECTS = 5
        const val MAX_MANIFEST_BYTES = 512L * 1024
        const val MAX_ITEMS = 50
        const val SUPPORTED_VERSION = 1

        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    suspend fun fetchManifest(
        manifestUrl: String,
        acceptLanguage: String = "en",
    ): BillingResult<BillingManifest> = withContext(Dispatchers.IO) {
        val safeUrl = sanitizeBillingUrl(manifestUrl, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        val http = runCatching {
            requestGetWithFallback(safeUrl, acceptLanguage)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val msg = error.message ?: error::class.simpleName ?: strings.get(R.string.error_network)
            logs.warn(R.string.log_subscription_message, "Billing", msg)
            return@withContext BillingResult.Failure(msg)
        }

        if (http.code !in 200..299) {
            return@withContext failureFromHttp(http.code, http.body)
        }

        val parsed = runCatching {
            lenientJson.decodeFromString<BillingManifest>(http.body)
        }.getOrElse { error ->
            return@withContext BillingResult.Failure(
                error.message ?: strings.get(R.string.billing_error_unsupported_version),
            )
        }

        if (parsed.version != SUPPORTED_VERSION) {
            return@withContext BillingResult.Failure(
                strings.get(R.string.billing_error_unsupported_version),
            )
        }

        val sanitizedCheckout = parsed.checkout?.let { cfg ->
            val safeEndpoint = sanitizeBillingUrl(cfg.endpoint, allowLocalHttp)
            safeEndpoint?.let { cfg.copy(endpoint = it) }
        }
        val sanitized = parsed.copy(
            provider = parsed.provider.copy(
                supportUrl = sanitizePortalUrl(parsed.provider.supportUrl, allowLocalHttp),
            ),
            fallbackWebUrl = sanitizePortalUrl(parsed.fallbackWebUrl, allowLocalHttp),
            plans = parsed.plans.take(MAX_ITEMS),
            addons = parsed.addons.take(MAX_ITEMS),
            paymentMethods = parsed.paymentMethods.take(MAX_ITEMS),
            checkout = sanitizedCheckout,
        )
        BillingResult.Success(sanitized)
    }

    suspend fun createCheckout(
        endpoint: String,
        request: BillingCheckoutRequest,
        acceptLanguage: String = "en",
    ): BillingResult<BillingCheckoutResponse> = withContext(Dispatchers.IO) {
        val safeEndpoint = sanitizeBillingUrl(endpoint, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        val payload = lenientJson.encodeToString(BillingCheckoutRequest.serializer(), request)
        val http = runCatching {
            postDirect(safeEndpoint, payload, acceptLanguage)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val msg = error.message ?: error::class.simpleName ?: strings.get(R.string.error_network)
            logs.warn(R.string.log_subscription_message, "Billing", msg)
            return@withContext BillingResult.Failure(msg)
        }

        if (http.code !in 200..299) {
            return@withContext failureFromHttp(http.code, http.body)
        }

        val decoded = runCatching {
            lenientJson.decodeFromString<BillingCheckoutResponse>(http.body)
        }.getOrElse { error ->
            return@withContext BillingResult.Failure(
                error.message ?: strings.get(R.string.billing_error_no_checkout),
            )
        }

        val safePayUrl = sanitizeBillingUrl(decoded.paymentUrl, allowLocalHttp)
            ?: return@withContext BillingResult.Failure(strings.get(R.string.billing_error_unsafe_url))

        BillingResult.Success(decoded.copy(paymentUrl = safePayUrl))
    }

    private fun failureFromHttp(code: Int, body: String): BillingResult.Failure {
        val parsed = runCatching {
            lenientJson.decodeFromString<BillingErrorBody>(body)
        }.getOrNull()
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: strings.get(R.string.error_http, code)
        return BillingResult.Failure(message = message, errorCode = parsed?.error)
    }

    private data class HttpTextResponse(val code: Int, val body: String)

    private fun requestGetWithFallback(url: String, acceptLanguage: String): HttpTextResponse = try {
        getDirect(url, acceptLanguage)
    } catch (direct: IOException) {
        if (!bridge.isRunning) throw direct
        try {
            getViaTunnel(url, acceptLanguage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (viaTunnel: Exception) {
            direct.addSuppressed(viaTunnel)
            throw direct
        }
    }

    private fun getDirect(url: String, acceptLanguage: String): HttpTextResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = URL(current)
            val connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", acceptLanguage)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return HttpTextResponse(code, "")
                    val next = resolveSafeDirectRedirect(target, location).toString()
                    current = sanitizeBillingUrl(next, allowLocalHttp)
                        ?: throw IllegalStateException(strings.get(R.string.billing_error_unsafe_url))
                    return@repeat
                }
                val stream = (connection.errorStream ?: connection.inputStream)
                    .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
                val raw = stream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
                if (raw.size > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                return HttpTextResponse(code, String(raw, Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun getViaTunnel(url: String, acceptLanguage: String): HttpTextResponse {
        var current = url
        val headersJson = buildJsonObject {
            put("User-Agent", userAgent)
            put("Accept", "application/json")
            put("Accept-Language", acceptLanguage)
            put("Accept-Encoding", "gzip")
            put("Cache-Control", "no-cache")
        }.toString()

        repeat(MAX_REDIRECTS) {
            val target = URL(current)
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
                        ?: return HttpTextResponse(code, "")
                    val next = resolveSafeDirectRedirect(target, location).toString()
                    current = sanitizeBillingUrl(next, allowLocalHttp)
                        ?: throw IllegalStateException(strings.get(R.string.billing_error_unsafe_url))
                    return@repeat
                }
                val rawStream = TunnelResponseInputStream(response)
                val stream = if (response.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val raw = stream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
                if (raw.size > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException(strings.get(R.string.error_body_too_large))
                }
                return HttpTextResponse(code, String(raw, Charsets.UTF_8))
            } finally {
                runCatching { response.close() }
            }
        }
        throw IllegalStateException(strings.get(R.string.error_too_many_redirects))
    }

    private fun postDirect(
        endpoint: String,
        bodyJson: String,
        acceptLanguage: String,
    ): HttpTextResponse {
        val target = URL(endpoint)
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", acceptLanguage)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        return try {
            val bytes = bodyJson.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = (connection.errorStream ?: connection.inputStream)
                .let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
            val raw = stream.use { it.readAtMost(MAX_MANIFEST_BYTES + 1) }
            if (raw.size > MAX_MANIFEST_BYTES) {
                throw IllegalStateException(strings.get(R.string.error_body_too_large))
            }
            HttpTextResponse(code, String(raw, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }
}
