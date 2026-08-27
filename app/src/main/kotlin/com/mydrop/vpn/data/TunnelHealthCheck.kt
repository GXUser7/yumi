package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
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
class TunnelHealthCheck(private val logs: LogRepository? = null) {

    /**
     * @return true when traffic flows, false when it does not, and null when there is nothing to
     *   ask — no probe inbound means no tunnel to interrogate, and the caller should fall back to
     *   a direct measurement rather than read the absence as a failure.
     */
    suspend fun passes(probe: ProbeEndpoint?): Boolean? =
        ask(probe, ProbeTargets.TUNNEL, "through tunnel")

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
        ask(probe, ProbeTargets.DNS, "dns")

    private suspend fun ask(probe: ProbeEndpoint?, host: String, what: String): Boolean? {
        probe ?: run {
            trace(TAG, "no probe inbound — falling back to the direct measurement")
            return null
        }
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            runCatching { fetch(probe, host) }
                .onSuccess {
                    trace(
                        TAG,
                        "$what: ${if (it) "204 ok" else "wrong status"} " +
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
     * Plain HTTP through the mixed inbound, as an absolute-URI GET.
     *
     * No TLS: the request is already inside an encrypted tunnel, and a handshake on top would
     * only add a round trip to a check that runs every twenty seconds. The absolute URI is what
     * makes this an ordinary HTTP proxy request, so no `CONNECT` is needed — unlike the speed
     * test, which has to tunnel TLS and therefore does.
     */
    private fun fetch(probe: ProbeEndpoint, host: String): Boolean = Socket().use { socket ->
        socket.soTimeout = TIMEOUT_MILLIS
        socket.connect(InetSocketAddress("127.0.0.1", probe.port), TIMEOUT_MILLIS)

        val credentials = Base64.getEncoder()
            .encodeToString("${probe.username}:${probe.password}".toByteArray())
        socket.outputStream.write(
            (
                "GET ${ProbeTargets.url(host)} HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Proxy-Authorization: Basic $credentials\r\n" +
                    "User-Agent: Yumi\r\n" +
                    // Nothing is reused, and leaving the socket open would hold an outbound
                    // connection on the server for the whole idle timeout.
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        socket.outputStream.flush()

        val status = socket.inputStream.bufferedReader().readLine().orEmpty()
        // Exactly 204, not "any 2xx". An endpoint that promises an empty response and returns a
        // page instead is not the endpoint answering — it is a portal, an injected block page, or
        // something else that replaced the response, and none of those mean the tunnel works.
        status.split(' ').getOrNull(1) == "204"
    }

    private fun trace(tag: String, message: String) {
        logs?.trace(tag, message) ?: android.util.Log.i(tag, message)
    }

    private companion object {
        const val TAG = "YumiFailover"

        /** Comfortably inside the watchdog's probe interval, so a hang cannot stack up. */
        const val TIMEOUT_MILLIS = 5_000
    }
}
