package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks the tunnel to fetch something trivial, and reports whether it managed.
 *
 * This exists because every other probe in the app measures the wrong thing for the server that
 * is currently carrying traffic. [LatencyTester] dials the server's own address, and the app is
 * excluded from its own tunnel, so that measurement leaves the phone directly — it answers "is
 * the port open", which under Russian DPI is routinely yes while the proxy carries nothing. The
 * common failure here is not a dead server; it is a live server whose sessions get killed after
 * the handshake, and a probe that never opens a session cannot see it.
 *
 * So this one goes through the core, over the loopback inbound the speed test already uses. What
 * it measures is the whole chain — TUN, routing, outbound, server, exit — which is exactly the
 * thing that breaks.
 *
 * Two consequences worth keeping in mind.
 *
 * The request leaves from the *server's* location, not the phone's, so what is reachable from
 * Russia has no bearing on the choice of target. And the round trip it takes is not comparable
 * with anything [LatencyTester] produces, which is why this returns a verdict rather than a
 * number: putting a full-chain figure next to direct handshake times in the server list would
 * make the working server look like the slow one.
 */
class TunnelHealthCheck(
    private val logs: LogRepository? = null,
    private val probeUrl: () -> String = { ProbeTargets.DEFAULT_URL },
    private val timeoutMillis: () -> Int = { DEFAULT_TIMEOUT_MILLIS },
) {

    /**
     * @return true when traffic flows, false when it does not, and null when there is nothing to
     *   ask — no probe inbound means no tunnel to interrogate, and the caller should fall back to
     *   a direct measurement rather than read the absence as a failure.
     */
    suspend fun passes(probe: ProbeEndpoint?): Boolean? {
        val target = probeUrl().takeIf(ProbeTargets::isValidProbeUrl) ?: ProbeTargets.DEFAULT_URL
        return askUrl(probe, target, "through tunnel")
    }

    /**
     * Whether names resolve, asked the same way and separated from [passes] by one routing rule.
     *
     * The configuration marks this host `action: resolve`, so the core looks it up through its own
     * DNS pipeline — the chosen resolver, the bootstrap behind it, the detour it goes out over —
     * before dialling. Every other connection in the app hands its hostname to the far end
     * instead, which is why a resolver can be completely dead while the tunnel probe stays green
     * and nothing anywhere says so.
     *
     * Only meaningful when [passes] said yes. A tunnel that carries nothing fails this too, and
     * reading that as a DNS fault would swap the resolver to fix somebody else's outage.
     */
    suspend fun resolves(probe: ProbeEndpoint?): Boolean? =
        askUrl(probe, ProbeTargets.url(ProbeTargets.DNS), "dns")

    private suspend fun askUrl(probe: ProbeEndpoint?, targetUrl: String, what: String): Boolean? {
        probe ?: run {
            trace(TAG, "no probe inbound — falling back to the direct measurement")
            return null
        }
        val timeout = timeoutMillis().coerceIn(
            AppSettings.MIN_PROBE_TIMEOUT_MILLIS,
            AppSettings.MAX_PROBE_TIMEOUT_MILLIS,
        )
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            runCatching { fetch(probe, targetUrl, timeout) }
                .onSuccess {
                    trace(
                        TAG,
                        "$what: ${if (it) "ok" else "wrong status"} " +
                            "in ${(System.nanoTime() - started) / 1_000_000} ms",
                    )
                }
                .onFailure {
                    // The reason matters: a refused loopback means the inbound is not there, a
                    // timeout means the chain past it is not carrying anything.
                    trace(
                        TAG,
                        "$what failed after ${(System.nanoTime() - started) / 1_000_000} ms: " +
                            "${it::class.simpleName}: ${it.message}",
                    )
                }
                .getOrDefault(false)
        }
    }

    /**
     * HTTP or HTTPS through the mixed inbound.
     *
     * Plain HTTP uses an absolute-URI GET with no `CONNECT`; HTTPS establishes a `CONNECT` tunnel
     * with Basic proxy credentials and performs a TLS handshake over it.
     */
    private fun fetch(probe: ProbeEndpoint, targetUrl: String, timeout: Int): Boolean {
        val parsed = URI(targetUrl.trim())
        val host = parsed.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Invalid probe URL: $targetUrl")
        val isHttps = parsed.scheme.equals("https", ignoreCase = true)
        val explicitPort = parsed.port
        val effectivePort = if (explicitPort > 0) explicitPort else if (isHttps) 443 else 80
        val hostHeader = if (explicitPort > 0) "$host:$explicitPort" else host
        val authority = "$host:$effectivePort"
        val credentials = Base64.getEncoder()
            .encodeToString("${probe.username}:${probe.password}".toByteArray())

        return Socket().use { socket ->
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress("127.0.0.1", probe.port), timeout)

            if (isHttps) {
                socket.outputStream.write(
                    (
                        "CONNECT $authority HTTP/1.1\r\n" +
                            "Host: $authority\r\n" +
                            "Proxy-Authorization: Basic $credentials\r\n\r\n"
                        ).toByteArray(),
                )
                socket.outputStream.flush()

                val connectStatus = readHead(socket.inputStream).lineSequence().firstOrNull().orEmpty()
                check(" 200" in connectStatus) {
                    "proxy CONNECT failed: ${connectStatus.trim()}"
                }

                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = (sslFactory.createSocket(
                    socket,
                    host,
                    effectivePort,
                    true,
                ) as SSLSocket).apply {
                    soTimeout = timeout
                    startHandshake()
                }
                tls.use { ssl ->
                    val rawPath = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                    val rawQuery = parsed.rawQuery?.let { "?$it" }.orEmpty()
                    sendGetAndCheck(ssl, "$rawPath$rawQuery", hostHeader, proxyCredentials = null, targetUrl)
                }
            } else {
                sendGetAndCheck(socket, targetUrl.trim(), hostHeader, proxyCredentials = credentials, targetUrl)
            }
        }
    }

    private fun sendGetAndCheck(
        socket: Socket,
        requestTarget: String,
        hostHeader: String,
        proxyCredentials: String?,
        targetUrl: String,
    ): Boolean {
        val request = buildString {
            append("GET $requestTarget HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            proxyCredentials?.let { append("Proxy-Authorization: Basic $it\r\n") }
            append("User-Agent: Yumi\r\n")
            // Nothing is reused, and leaving the socket open would hold an outbound
            // connection on the server for the whole idle timeout.
            append("Connection: close\r\n\r\n")
        }
        socket.outputStream.write(request.toByteArray())
        socket.outputStream.flush()

        val statusLine = readHead(socket.inputStream).lineSequence().firstOrNull().orEmpty()
        val ok = isExpectedStatus(statusLine, targetUrl)
        drain(socket.inputStream)
        return ok
    }

    private fun isExpectedStatus(statusLine: String, targetUrl: String): Boolean {
        val code = statusLine.trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return false
        // Exactly 204 for 204 endpoints, not "any 2xx". An endpoint that promises an empty response
        // and returns a page instead is a portal or an injected block page.
        return if (targetUrl.contains("204")) code == 204 else code in 200..299
    }

    /** Byte at a time so a buffered reader cannot swallow the start of a TLS ServerHello after CONNECT. */
    private fun readHead(stream: InputStream): String {
        val head = StringBuilder()
        var newlines = 0
        while (newlines < 2 && head.length < DRAIN_LIMIT_BYTES) {
            val byte = stream.read()
            if (byte < 0) break
            val symbol = byte.toChar()
            head.append(symbol)
            when (symbol) {
                '\n' -> newlines++
                '\r' -> Unit
                else -> newlines = 0
            }
        }
        return head.toString()
    }

    /**
     * Reads what is left of the response, so that closing the socket does not interrupt a write.
     *
     * The verdict is decided by the status line and nothing after it, so this reads only to be
     * polite — but the impoliteness was expensive. Closing here left the core mid-write and it
     * logged a warning for it: on one three-hour phone journal, 238 of 486 probes each produced
     * an `app/proxyman/inbound: connection ends > proxy/http: failed to write response > broken
     * pipe`, which is half the warnings in the file, all of them from the app talking to itself,
     * and all of them noise sitting on top of the ones that mean something.
     *
     * Bounded twice over: both probe targets answer `204` with no body, the response carries
     * `Connection: close`, and [DEFAULT_TIMEOUT_MILLIS] still applies — but a far end that ignores all of
     * that must not hold the watchdog. Failures are swallowed on purpose; the answer is already in
     * hand, and a probe that worked must not be reported as a failure because of the goodbye.
     */
    private fun drain(stream: java.io.InputStream) {
        runCatching {
            val scratch = ByteArray(DRAIN_CHUNK_BYTES)
            var left = DRAIN_LIMIT_BYTES
            while (left > 0) {
                val read = stream.read(scratch, 0, minOf(scratch.size, left))
                if (read < 0) break
                left -= read
            }
        }
    }

    private fun trace(tag: String, message: String) {
        logs?.trace(tag, message) ?: android.util.Log.i(tag, message)
    }

    private companion object {
        const val TAG = "YumiFailover"

        /**
         * Comfortably inside the watchdog's probe interval, so a hang cannot stack up — but not
         * so tight that a slow answer counts as no answer.
         *
         * Five seconds used to be the limit, and the longest probe that still made it back took
         * 4925 ms. A cutoff the successes are already touching is not measuring whether the exit
         * works; it is measuring whether the exit is fast, and then reporting the answer as death.
         * Eight leaves the failing probe and its confirmation inside one twenty-second cycle.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 8_000

        /**
         * Far more than a `204` and its headers, far less than a page somebody wants to send us.
         *
         * The point of a limit is the endpoint that is not the endpoint — a captive portal or an
         * injected block page, which is exactly the case [fetch] already refuses on the status
         * line. Having refused it, there is no reason to read its body to the end.
         */
        const val DRAIN_LIMIT_BYTES = 8 * 1024
        const val DRAIN_CHUNK_BYTES = 1024
    }
}
