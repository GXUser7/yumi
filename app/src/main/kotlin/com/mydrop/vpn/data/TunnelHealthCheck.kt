package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProbeEndpoint
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
class TunnelHealthCheck {

    /**
     * @return true when traffic flows, false when it does not, and null when there is nothing to
     *   ask — no probe inbound means no tunnel to interrogate, and the caller should fall back to
     *   a direct measurement rather than read the absence as a failure.
     */
    suspend fun passes(probe: ProbeEndpoint?): Boolean? {
        probe ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { fetch(probe) }.getOrDefault(false)
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
    private fun fetch(probe: ProbeEndpoint): Boolean = Socket().use { socket ->
        socket.soTimeout = TIMEOUT_MILLIS
        socket.connect(InetSocketAddress("127.0.0.1", probe.port), TIMEOUT_MILLIS)

        val credentials = Base64.getEncoder()
            .encodeToString("${probe.username}:${probe.password}".toByteArray())
        socket.outputStream.write(
            (
                "GET $TARGET_URL HTTP/1.1\r\n" +
                    "Host: $TARGET_HOST\r\n" +
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

    private companion object {
        /**
         * Chosen for being boring: no body, no redirect, no TLS, and run by an operator whose
         * whole business is answering it quickly from everywhere. Reachability is judged from the
         * exit node, so nothing about this address needs to survive Russian filtering.
         */
        const val TARGET_HOST = "cp.cloudflare.com"
        const val TARGET_URL = "http://cp.cloudflare.com/generate_204"

        /** Comfortably inside the watchdog's probe interval, so a hang cannot stack up. */
        const val TIMEOUT_MILLIS = 5_000
    }
}
