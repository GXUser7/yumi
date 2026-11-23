package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.BillingCheckoutRequest
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.TunnelHttpResponse
import com.mydrop.vpn.vpn.TunnelStream
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingServiceTest {

    private class TestStrings : Strings() {
        override fun get(id: Int, vararg formatArgs: Any): String = "res#$id(${formatArgs.joinToString(",")})"
        override fun plural(id: Int, count: Int): String = "$count"
    }

    private class FakeHttpResponse(
        override val statusCode: Int,
        private val bodyBytes: ByteArray = ByteArray(0),
        override val contentLength: Long = bodyBytes.size.toLong(),
        override val contentEncoding: String = "",
        override val location: String = "",
        override val headersJSON: String = "{}",
        private val headerMap: Map<String, String> = emptyMap(),
    ) : TunnelHttpResponse {
        private var offset = 0

        override fun header(name: String): String =
            headerMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

        override fun read(maxBytes: Int): ByteArray? {
            if (offset >= bodyBytes.size) return null
            val n = minOf(maxBytes, bodyBytes.size - offset)
            val slice = bodyBytes.copyOfRange(offset, offset + n)
            offset += n
            return slice
        }

        override fun readAll(maxBytes: Long): ByteArray =
            if (maxBytes > 0 && bodyBytes.size > maxBytes) bodyBytes.copyOfRange(0, maxBytes.toInt()) else bodyBytes

        override fun close() = Unit
    }

    private class FakeBridge : TunnelBridge {
        override var isRunning: Boolean = true
        var probeHandler: (String, Int, Boolean) -> Int = { _, _, _ -> 204 }
        var openHttpHandler: (String, String, String, Int, Int, Boolean) -> TunnelHttpResponse =
            { _, _, _, _, _, _ -> FakeHttpResponse(204) }
        var dialTlsHandler: (String, Int, String, Int) -> TunnelStream =
            { _, _, _, _ -> error("unexpected dialTunnelTLS") }

        val openCalls = mutableListOf<Triple<String, String, String>>()

        override fun probeHTTP(targetURL: String, timeoutMillis: Int, resolveDNS: Boolean): Int =
            probeHandler(targetURL, timeoutMillis, resolveDNS)

        override fun openHTTP(
            method: String,
            targetURL: String,
            headersJSON: String,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            resolveDNS: Boolean,
        ): TunnelHttpResponse {
            openCalls += Triple(method, targetURL, headersJSON)
            return openHttpHandler(method, targetURL, headersJSON, connectTimeoutMillis, readTimeoutMillis, resolveDNS)
        }

        override fun dialTunnelTLS(
            host: String,
            port: Int,
            serverName: String,
            timeoutMillis: Int,
        ): TunnelStream = dialTlsHandler(host, port, serverName, timeoutMillis)
    }

    private fun sampleManifestJson(
        baseUrl: String,
        version: Int = 1,
        planCount: Int = 2,
        checkoutEndpoint: String = "$baseUrl/checkout",
        fallbackWebUrl: String = "$baseUrl/portal",
    ): String {
        val plansJson = (1..planCount).joinToString(",") { i ->
            """
            {
              "id": "plan-$i",
              "title": "Plan $i",
              "price": ${i * 10000},
              "currency": "RUB",
              "duration_days": ${i * 30}
            }
            """.trimIndent()
        }
        return """
        {
          "version": $version,
          "provider": {
            "name": "Yumi Cloud",
            "support_url": "$baseUrl/support"
          },
          "routing_hint": "direct_ru",
          "promo_supported": true,
          "fallback_web_url": "$fallbackWebUrl",
          "payment_methods": [
            {"id": "sbp", "title": "SBP", "type": "sbp"}
          ],
          "plans": [$plansJson],
          "addons": [
            {"id": "addon-1", "title": "+100 GB", "price": 19900, "currency": "RUB"}
          ],
          "checkout": {
            "endpoint": "$checkoutEndpoint",
            "method": "POST"
          }
        }
        """.trimIndent()
    }

    @Test
    fun `fetchManifest parses valid OSB v1 JSON, filters unsafe URLs, caps plans at 50, and omits x-hwid and Authorization`() =
        runBlocking {
            val capturedHeaders = mutableMapOf<String, List<String>>()
            var responseBody = ""
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/manifest") { exchange ->
                capturedHeaders.clear()
                exchange.requestHeaders.forEach { (k, v) -> capturedHeaders[k.lowercase()] = v }
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = TestStrings()
                val logs = LogRepository(strings)
                val service = BillingService(
                    logs = logs,
                    strings = strings,
                    bridge = FakeBridge().apply { isRunning = false },
                    allowLocalHttp = true,
                )

                responseBody = sampleManifestJson(
                    baseUrl = baseUrl,
                    planCount = 60,
                    checkoutEndpoint = "http://evil.example.com/checkout",
                    fallbackWebUrl = "javascript:alert(1)",
                )
                val okResult = service.fetchManifest("$baseUrl/manifest", acceptLanguage = "ru")
                assertTrue(okResult is BillingResult.Success)
                okResult as BillingResult.Success
                assertEquals(50, okResult.value.plans.size)
                assertNull(okResult.value.checkout)
                assertNull(okResult.value.fallbackWebUrl)
                assertFalse(capturedHeaders.containsKey("x-hwid"))
                assertFalse(capturedHeaders.containsKey("authorization"))

                responseBody = sampleManifestJson(baseUrl = baseUrl, version = 2)
                val badVersion = service.fetchManifest("$baseUrl/manifest")
                assertTrue(badVersion is BillingResult.Failure)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `fetchManifest falls back to TunnelBridge when direct socket fails`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$closedPort/manifest"
        val bridge = FakeBridge()
        val manifestJson = sampleManifestJson(baseUrl = "http://127.0.0.1:$closedPort")
        bridge.openHttpHandler = { method, targetUrl, _, _, _, resolveDNS ->
            assertEquals("GET", method)
            assertEquals(url, targetUrl)
            assertFalse(resolveDNS)
            FakeHttpResponse(200, manifestJson.toByteArray(Charsets.UTF_8))
        }

        val strings = TestStrings()
        val service = BillingService(
            logs = LogRepository(strings),
            strings = strings,
            bridge = bridge,
            allowLocalHttp = true,
        )

        val result = service.fetchManifest(url)
        assertTrue(result is BillingResult.Success)
        assertEquals(1, bridge.openCalls.size)
        result as BillingResult.Success
        assertEquals("Yumi Cloud", result.value.provider.name)
    }

    @Test
    fun `createCheckout sends POST JSON, parses structured 400 error, and rejects unsafe payment_url`() =
        runBlocking {
            var nextStatus = 200
            var nextResponse = ""
            var capturedMethod = ""
            var capturedBody = ""

            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/checkout") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = nextResponse.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(nextStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = TestStrings()
                val service = BillingService(
                    logs = LogRepository(strings),
                    strings = strings,
                    bridge = FakeBridge().apply { isRunning = false },
                    allowLocalHttp = true,
                )

                nextStatus = 200
                nextResponse = """
                {
                  "order_id": "ord_123",
                  "amount": 170100,
                  "currency": "RUB",
                  "payment_url": "$baseUrl/pay/ord_123"
                }
                """.trimIndent()

                val ok = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(
                        planId = "pro-3m",
                        paymentMethod = "sbp",
                        promoCode = "YUMI10",
                        returnUrl = "mydrop://billing/return?sub_id=s1",
                    ),
                )
                assertEquals("POST", capturedMethod)
                assertTrue(capturedBody.contains("\"plan_id\":\"pro-3m\""))
                assertTrue(capturedBody.contains("\"promo_code\":\"YUMI10\""))
                assertTrue(ok is BillingResult.Success)
                ok as BillingResult.Success
                assertEquals("$baseUrl/pay/ord_123", ok.value.paymentUrl)

                nextStatus = 400
                nextResponse = """{"error":"invalid_promo","message":"Promo code not valid"}"""
                val promoErr = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(planId = "pro-3m", paymentMethod = "sbp", promoCode = "BAD"),
                )
                assertTrue(promoErr is BillingResult.Failure)
                promoErr as BillingResult.Failure
                assertEquals("invalid_promo", promoErr.errorCode)
                assertEquals("Promo code not valid", promoErr.message)

                nextStatus = 200
                nextResponse = """{"order_id":"ord_x","payment_url":"http://evil.example.com/pay"}"""
                val unsafePayUrl = service.createCheckout(
                    endpoint = "$baseUrl/checkout",
                    request = BillingCheckoutRequest(planId = "pro-3m", paymentMethod = "sbp"),
                )
                assertTrue(unsafePayUrl is BillingResult.Failure)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `SubscriptionService extracts billing URLs with header precedence and preserves metadata on failure`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            var baseUrl = ""
            server.createContext("/sub/ok") { exchange ->
                val bodyPlain = """
                    #billing-manifest-url: $baseUrl/api/from-comment
                    #payment-url: $baseUrl/pay/from-comment
                    vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#DE1
                """.trimIndent()
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/from-header")
                exchange.responseHeaders.add("profile-web-page-url", "$baseUrl/portal")
                exchange.responseHeaders.add("support-url", "$baseUrl/support")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.createContext("/sub/expired") { exchange ->
                val bodyPlain = "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#Expired"
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/billing")
                exchange.responseHeaders.add("sub-expire-button-link", "$baseUrl/pay/expire-btn")
                exchange.responseHeaders.add("support-url", "$baseUrl/support")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.createContext("/sub/hwid") { exchange ->
                val bodyPlain = "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#Device+Limit"
                val encoded = Base64.getEncoder().encode(bodyPlain.toByteArray(Charsets.UTF_8))
                exchange.responseHeaders.add("x-hwid-max-devices-reached", "true")
                exchange.responseHeaders.add("billing-manifest-url", "$baseUrl/api/billing")
                exchange.responseHeaders.add("payment-url", "$baseUrl/pay/quick")
                exchange.responseHeaders.add("profile-web-page-url", "$baseUrl/portal")
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
            server.start()

            try {
                baseUrl = "http://127.0.0.1:${server.address.port}"
                val strings = TestStrings()
                val service = SubscriptionService(
                    logs = LogRepository(strings),
                    strings = strings,
                    bridge = FakeBridge().apply { isRunning = false },
                    allowLocalBillingHttp = true,
                )

                val ok = service.fetch(Subscription(id = "sub-1", name = "Ok", url = "$baseUrl/sub/ok"))
                assertTrue(ok is SubscriptionUpdate.Success)
                ok as SubscriptionUpdate.Success
                assertEquals("$baseUrl/api/from-header", ok.subscription.billingManifestUrl)
                assertEquals("$baseUrl/pay/from-comment", ok.subscription.paymentUrl)
                assertEquals("$baseUrl/portal", ok.subscription.webPageUrl)
                assertEquals("$baseUrl/support", ok.subscription.supportUrl)

                val expired = service.fetch(Subscription(id = "sub-2", name = "Expired", url = "$baseUrl/sub/expired"))
                assertTrue(expired is SubscriptionUpdate.Failure)
                expired as SubscriptionUpdate.Failure
                assertEquals("$baseUrl/api/billing", expired.metadata?.billingManifestUrl)
                assertEquals("$baseUrl/pay/expire-btn", expired.metadata?.paymentUrl)
                assertEquals("$baseUrl/support", expired.metadata?.supportUrl)

                val hwid = service.fetch(Subscription(id = "sub-3", name = "HWID", url = "$baseUrl/sub/hwid"))
                assertTrue(hwid is SubscriptionUpdate.Failure)
                hwid as SubscriptionUpdate.Failure
                assertEquals(strings.get(com.mydrop.vpn.shared.R.string.error_device_limit), hwid.message)
                assertEquals("$baseUrl/api/billing", hwid.metadata?.billingManifestUrl)
                assertEquals("$baseUrl/pay/quick", hwid.metadata?.paymentUrl)
                assertEquals("$baseUrl/portal", hwid.metadata?.webPageUrl)
            } finally {
                server.stop(0)
            }
        }
}
