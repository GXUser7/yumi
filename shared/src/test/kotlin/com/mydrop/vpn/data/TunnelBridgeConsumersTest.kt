package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProbeTargets
import com.mydrop.vpn.core.model.SpeedPhase
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import com.mydrop.vpn.core.net.resolveSafeDirectRedirect
import com.mydrop.vpn.core.parse.ProviderFixtures
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.TunnelHttpResponse
import com.mydrop.vpn.vpn.TunnelStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TunnelBridgeConsumersTest {

    @get:Rule
    val folder = TemporaryFolder()

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
        private val chunkStep: Int = 1024,
    ) : TunnelHttpResponse {
        private var offset = 0
        var closed = false
            private set

        override fun header(name: String): String =
            headerMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

        override fun read(maxBytes: Int): ByteArray? {
            if (offset >= bodyBytes.size) return null
            val n = minOf(maxBytes, chunkStep, bodyBytes.size - offset)
            val slice = bodyBytes.copyOfRange(offset, offset + n)
            offset += n
            return slice
        }

        override fun readAll(maxBytes: Long): ByteArray {
            if (maxBytes > 0 && bodyBytes.size > maxBytes) {
                return bodyBytes.copyOfRange(0, maxBytes.toInt())
            }
            return bodyBytes
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeBridge : TunnelBridge {
        override var isRunning: Boolean = true
        var probeHandler: (String, Int, Boolean) -> Int = { _, _, _ -> 204 }
        var openHttpHandler: (String, String, String, Int, Int, Boolean) -> TunnelHttpResponse =
            { _, _, _, _, _, _ -> FakeHttpResponse(204) }
        var dialTlsHandler: (String, Int, String, Int) -> TunnelStream =
            { _, _, _, _ -> error("unexpected dialTunnelTLS") }

        val probeCalls = mutableListOf<Triple<String, Int, Boolean>>()
        val openCalls = mutableListOf<Triple<String, String, String>>()

        override fun probeHTTP(targetURL: String, timeoutMillis: Int, resolveDNS: Boolean): Int {
            probeCalls += Triple(targetURL, timeoutMillis, resolveDNS)
            return probeHandler(targetURL, timeoutMillis, resolveDNS)
        }

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

    @Test
    fun `TunnelHealthCheck uses TunnelBridge probeHTTP with resolveDNS false for passes and true for resolves`() =
        runBlocking {
            val bridge = FakeBridge()
            val health = TunnelHealthCheck(logs = null, bridge = bridge)

            assertEquals(true, health.passes())
            assertEquals(false, bridge.probeCalls.single().third)

            assertEquals(true, health.resolves())
            assertEquals(true, bridge.probeCalls.last().third)
            assertEquals(ProbeTargets.url(ProbeTargets.DNS), bridge.probeCalls.last().first)

            // Non-204 rejected for 204 endpoint; exception -> false
            bridge.probeHandler = { _, _, _ -> 200 }
            assertEquals(false, health.passes())

            bridge.probeHandler = { _, _, _ -> throw RuntimeException("timeout") }
            assertEquals(false, health.passes())

            // Stopped core -> null without dialing bridge
            val beforeCalls = bridge.probeCalls.size
            bridge.isRunning = false
            assertNull(health.passes())
            assertNull(health.resolves())
            assertEquals(beforeCalls, bridge.probeCalls.size)
        }

    @Test
    fun `SpeedTester uses dialTunnelTLS when throughTunnel is true and handles read-ahead plus teardown close`() =
        runBlocking {
            val strings = TestStrings()
            val logs = LogRepository(strings)
            val bridge = FakeBridge()
            val createdStreams = java.util.concurrent.atomic.AtomicInteger(0)
            val closedStreams = java.util.concurrent.atomic.AtomicInteger(0)

            bridge.dialTlsHandler = { host, port, sni, _ ->
                assertEquals("speed.cloudflare.com", host)
                assertEquals(443, port)
                assertEquals("speed.cloudflare.com", sni)
                createdStreams.incrementAndGet()
                object : TunnelStream {
                    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    private val chunks = ArrayDeque<ByteArray>()
                    private var uploadWritten = 0
                    override fun setReadTimeout(timeoutMillis: Int) = Unit
                    override fun setWriteTimeout(timeoutMillis: Int) = Unit
                    override fun write(data: ByteArray): Int {
                        val req = data.decodeToString()
                        if (req.startsWith("GET /__down?bytes=0")) {
                            chunks += "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabcd".toByteArray()
                        } else if (req.startsWith("GET /__down?")) {
                            chunks += ("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n").toByteArray() +
                                ByteArray(512) { 1 }
                        } else if (req.startsWith("POST /__up")) {
                            uploadWritten = 0
                        } else {
                            uploadWritten += data.size
                            if (uploadWritten > 128 * 1024) {
                                throw java.io.IOException("simulated upload stream cap")
                            }
                        }
                        return data.size
                    }

                    override fun read(maxBytes: Int): ByteArray? =
                        if (chunks.isEmpty()) null else chunks.removeFirst()

                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            closedStreams.incrementAndGet()
                        }
                    }
                }
            }

            val tester = SpeedTester(logs, strings, bridge)
            val states = tester.measure(throughTunnel = true, serverName = "DE #1").toList()

            assertTrue(createdStreams.get() >= 1)
            assertEquals(createdStreams.get(), closedStreams.get())
            assertTrue(states.first().throughTunnel)
            assertTrue(states.any { it.phase == SpeedPhase.Latency })
            assertTrue(states.any { it.phase == SpeedPhase.Download })
            assertTrue(states.any { it.phase == SpeedPhase.Upload })
            assertEquals(SpeedPhase.Done, states.last().phase)
        }

    @Test
    fun `SubscriptionService falls back to openHTTP on IOException, checks empty hwid headers, decodes gzip, and strips cross-origin creds`() =
        runBlocking {
            val strings = TestStrings()
            val logs = LogRepository(strings)
            val bridge = FakeBridge()
            val vless = "vless://${ProviderFixtures.UUID}@203.0.113.10:443?security=tls#Server1"
            val encodedList = Base64.getEncoder().encodeToString(vless.toByteArray())
            val gzipped = ByteArrayOutputStream().use { out ->
                GZIPOutputStream(out).use { it.write(encodedList.toByteArray()) }
                out.toByteArray()
            }

            val responses = mutableListOf<FakeHttpResponse>()
            bridge.openHttpHandler = { _, targetUrl, _, _, _, resolveDNS ->
                assertFalse("SubscriptionService must pass resolveDNS=false", resolveDNS)
                val resp = when {
                    targetUrl == "http://127.0.0.1:9/sub" -> FakeHttpResponse(
                        statusCode = 302,
                        location = "https://other.example/redirected",
                    )
                    targetUrl == "https://other.example/redirected" -> FakeHttpResponse(
                        statusCode = 200,
                        bodyBytes = gzipped,
                        contentEncoding = "gzip",
                        headersJSON = """{"x-hwid-active":"","content-encoding":"gzip"}""",
                        chunkStep = 11,
                    )
                    else -> FakeHttpResponse(statusCode = 404)
                }
                responses += resp
                resp
            }

            val service = SubscriptionService(
                logs = logs,
                strings = strings,
                identity = { DeviceIdentity("hwid-1", "Android", "15", "Pixel") },
                bridge = bridge,
            )

            // Port 9 on localhost fails direct connection immediately -> falls back to TunnelBridge
            val sub = Subscription(
                id = "s1",
                name = "TestSub",
                url = "http://127.0.0.1:9/sub",
                headers = mapOf("X-Secret" to "top-secret"),
            )
            val update = service.fetch(sub)
            assertTrue("expected Success, got $update", update is SubscriptionUpdate.Success)
            val success = update as SubscriptionUpdate.Success
            assertEquals(1, success.nodes.size)
            assertEquals("Server1", success.nodes.single().name)
            assertTrue(responses.all { it.closed })

            // Cross-origin redirect must NOT send X-Secret or x-hwid
            val originHeaders = bridge.openCalls[0].third
            val crossHeaders = bridge.openCalls[1].third
            assertTrue(originHeaders.contains("X-Secret"))
            assertTrue(originHeaders.contains("x-hwid"))
            assertFalse(crossHeaders.contains("X-Secret"))
            assertFalse(crossHeaders.contains("x-hwid"))
        }

    @Test
    fun `SubscriptionService treats empty-valued x-hwid-active in headersJSON as hwidRequired`() =
        runBlocking {
            val strings = TestStrings()
            val logs = LogRepository(strings)
            val bridge = FakeBridge()
            val placeholder = "vless://${ProviderFixtures.UUID}@0.0.0.0:1#HWID_Required"
            val encoded = Base64.getEncoder().encodeToString(placeholder.toByteArray())

            bridge.openHttpHandler = { _, _, _, _, _, _ ->
                FakeHttpResponse(
                    statusCode = 200,
                    bodyBytes = encoded.toByteArray(),
                    headersJSON = """{"x-hwid-active":""}""",
                )
            }

            val service = SubscriptionService(logs = logs, strings = strings, bridge = bridge)
            val result = service.fetch(Subscription(id = "s1", name = "Sub", url = "http://127.0.0.1:9/sub"))
            assertTrue(result is SubscriptionUpdate.Failure)
        }

    @Test
    fun `GeoAssetStore uses TunnelBridge openHTTP with redirects, SHA-256 verification, and closes response`() =
        runBlocking {
            val strings = TestStrings()
            val logs = LogRepository(strings)
            val bridge = FakeBridge()
            val payload = ByteArray(110_000) { (it % 251).toByte() }
            val shaHex = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }
            val responses = mutableListOf<FakeHttpResponse>()

            bridge.openHttpHandler = { _, url, _, _, _, resolveDNS ->
                assertFalse(resolveDNS)
                val resp = when {
                    url.endsWith(".sha256sum") -> FakeHttpResponse(200, "$shaHex  asset.dat\n".toByteArray())
                    url.contains("github.com") -> FakeHttpResponse(302, location = "https://objects.example/asset.dat")
                    else -> FakeHttpResponse(200, payload, chunkStep = 16384)
                }
                responses += resp
                resp
            }

            val store = GeoAssetStore(folder.newFolder(), logs, bridge)
            assertTrue(store.refresh(force = true))
            assertTrue(store.available())
            assertTrue(File(store.directory, "geoip.dat").isFile)
            assertTrue(File(store.directory, "geosite.dat").isFile)
            assertTrue(responses.all { it.closed })
        }

    @Test
    fun `MapTileStore uses TunnelBridge openHTTP with User-Agent and follows redirects`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val bridge = FakeBridge()
            val tileBytes = ByteArray(128) { it.toByte() }
            val responses = mutableListOf<FakeHttpResponse>()
            bridge.openHttpHandler = { _, url, headersJson, _, _, resolveDNS ->
                assertFalse(resolveDNS)
                assertTrue(headersJson.contains("YumiVPN"))
                val resp = if (url.contains("tile.openstreetmap.org")) {
                    FakeHttpResponse(301, location = "https://cdn.osm.example/tile.png")
                } else {
                    FakeHttpResponse(200, tileBytes)
                }
                responses += resp
                resp
            }

            val store = MapTileStore(folder.newFolder(), scope, bridge)
            val fetched = store.fetchBytes(10, 1, 2)
            assertArrayEquals(tileBytes, fetched)
            assertTrue(responses.all { it.closed })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `F10 no production file retains Authenticator_setDefault or Proxy_Type_SOCKS or probe-in`() {
        val sharedMain = listOf(File("src/main"), File("shared/src/main")).first { it.isDirectory }
        val violations = mutableListOf<String>()
        sharedMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                if (text.contains("Authenticator.setDefault")) violations += "${file.name}: Authenticator.setDefault"
                if (text.contains("Proxy.Type.SOCKS")) violations += "${file.name}: Proxy.Type.SOCKS"
                if (text.contains("Proxy.Type.HTTP")) violations += "${file.name}: Proxy.Type.HTTP"
                if (text.contains("\"probe-in\"")) violations += "${file.name}: probe-in"
                if (text.contains("newProbeEndpoint")) violations += "${file.name}: newProbeEndpoint"
            }
        assertTrue("Legacy proxy violations found: $violations", violations.isEmpty())
    }

    @Test
    fun `Tier5 direct redirect guard rejects external-to-loopback and non-http schemes in SubscriptionService and GeoAssetStore`() =
        runBlocking {
            val externalSource = URL("https://panel.example/sub")
            val blockedLocations = listOf(
                "http://127.0.0.1:8080/secret",
                "http://127.1/secret",
                "http://127.0.1/secret",
                "http://0177.0.0.1/secret",
                "http://0177.1/secret",
                "http://0x7f.0.0.1/secret",
                "http://0x7f000001/secret",
                "http://2130706433/secret",
                "http://[::1]:8080/secret",
                "http://[::1%25lo0]:8080/secret",
                "http://[::1%lo0]:8080/secret",
                "http://localhost/secret",
                "http://0.0.0.0:80/secret",
                "http://0000.0.0.0/secret",
                "http://0x0.0.0.0/secret",
                "http://0/secret",
                "http://[::]/secret",
                "file:///etc/passwd",
                "ftp://example.com/file",
                "//127.0.0.1/secret",
                "//127.1/secret",
            )
            for (loc in blockedLocations) {
                val rejected = runCatching { resolveSafeDirectRedirect(externalSource, loc) }.isFailure
                assertTrue("Expected redirect '$loc' from external source to be rejected", rejected)
            }
            // Same-port cross-representation loopback redirect (127.0.0.1 -> 127.1) must also be rejected
            assertTrue(
                runCatching {
                    resolveSafeDirectRedirect(URL("http://127.0.0.1:18080/sub"), "http://127.1:18080/secret")
                }.isFailure,
            )
            assertEquals(
                "https://cdn.example/sub2",
                resolveSafeDirectRedirect(externalSource, "https://cdn.example/sub2").toString(),
            )

            // Live HttpURLConnection redirect test: cross-port loopback & non-canonical 127.1 redirects are blocked before dialing target.
            val loopback = InetAddress.getByName("127.0.0.1")
            val trapHits = AtomicInteger(0)
            ServerSocket(0, 8, loopback).use { trap ->
                ServerSocket(0, 8, loopback).use { redirector ->
                    val locationRef = java.util.concurrent.atomic.AtomicReference(
                        "http://127.0.0.1:${trap.localPort}/secret",
                    )
                    val trapThread = kotlin.concurrent.thread(isDaemon = true) {
                        while (!trap.isClosed) {
                            runCatching { trap.accept().use { trapHits.incrementAndGet() } }
                        }
                    }
                    val redirThread = kotlin.concurrent.thread(isDaemon = true) {
                        while (!redirector.isClosed) {
                            runCatching {
                                redirector.accept().use { client ->
                                    client.getInputStream().read()
                                    val resp = "HTTP/1.1 302 Found\r\nLocation: ${locationRef.get()}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    client.getOutputStream().write(resp.toByteArray())
                                    client.getOutputStream().flush()
                                }
                            }
                        }
                    }

                    val bridge = FakeBridge().apply { isRunning = false }
                    val logs = LogRepository(TestStrings())
                    val service = SubscriptionService(logs = logs, strings = TestStrings(), bridge = bridge)
                    for (badLoc in listOf(
                        "http://127.0.0.1:${trap.localPort}/secret",
                        "http://127.1:${trap.localPort}/secret",
                        "http://0177.0.0.1:${trap.localPort}/secret",
                    )) {
                        locationRef.set(badLoc)
                        val result = service.fetch(
                            Subscription(
                                id = "s-ssrf",
                                name = "SSRF",
                                url = "http://127.0.0.1:${redirector.localPort}/sub",
                            ),
                        )
                        assertTrue("Expected Failure on unsafe redirect '$badLoc', got $result", result is SubscriptionUpdate.Failure)
                    }
                    Thread.sleep(50)
                    assertEquals("Blocked redirect target must receive 0 connections", 0, trapHits.get())

                    redirector.close()
                    trap.close()
                    trapThread.join(500)
                    redirThread.join(500)
                }
            }
        }
}
