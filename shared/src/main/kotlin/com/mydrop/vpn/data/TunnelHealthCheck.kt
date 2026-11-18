package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProbeEndpoint
import com.mydrop.vpn.core.model.ProbeTargets
import com.mydrop.vpn.vpn.TunnelBridge
import com.mydrop.vpn.vpn.XrayCore
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
 * So this one goes through the core in-process via [TunnelBridge] (`core.Dial`), with no
 * loopback proxy listener. What it measures is the whole chain — routing, outbound, server, exit —
 * which is exactly the thing that breaks.
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
    private val bridge: TunnelBridge = XrayCore,
) {

    /**
     * @return true when traffic flows, false when it does not, and null when the core is not
     *   running — no running core means no tunnel to interrogate, and the caller should fall back
     *   to a direct measurement rather than read the absence as a failure.
     */
    suspend fun passes(): Boolean? {
        val target = probeUrl().takeIf(ProbeTargets::isValidProbeUrl) ?: ProbeTargets.DEFAULT_URL
        return askUrl(target, resolveDNS = false, what = "through tunnel")
    }

    /**
     * Whether names resolve, asked the same way and separated from [passes] by `resolveDNS = true`.
     *
     * Only meaningful when [passes] said yes. A tunnel that carries nothing fails this too, and
     * reading that as a DNS fault would swap the resolver to fix somebody else's outage.
     */
    suspend fun resolves(): Boolean? =
        askUrl(ProbeTargets.url(ProbeTargets.DNS), resolveDNS = true, what = "dns")

    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    @Deprecated("probe-in removed; use passes()", ReplaceWith("passes()"))
    suspend fun passes(probe: ProbeEndpoint?): Boolean? = passes()

    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    @Deprecated("probe-in removed; use resolves()", ReplaceWith("resolves()"))
    suspend fun resolves(probe: ProbeEndpoint?): Boolean? = resolves()

    private suspend fun askUrl(targetUrl: String, resolveDNS: Boolean, what: String): Boolean? {
        if (!bridge.isRunning) {
            trace(TAG, "core not running — falling back to the direct measurement")
            return null
        }
        val timeout = timeoutMillis().coerceIn(
            AppSettings.MIN_PROBE_TIMEOUT_MILLIS,
            AppSettings.MAX_PROBE_TIMEOUT_MILLIS,
        )
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            runCatching {
                val code = bridge.probeHTTP(targetUrl.trim(), timeout, resolveDNS)
                isExpectedStatus(code, targetUrl)
            }
                .onSuccess {
                    trace(
                        TAG,
                        "$what: ${if (it) "ok" else "wrong status"} " +
                            "in ${(System.nanoTime() - started) / 1_000_000} ms",
                    )
                }
                .onFailure {
                    trace(
                        TAG,
                        "$what failed after ${(System.nanoTime() - started) / 1_000_000} ms: " +
                            "${it::class.simpleName}: ${it.message}",
                    )
                }
                .getOrDefault(false)
        }
    }

    private fun isExpectedStatus(code: Int, targetUrl: String): Boolean =
        // Exactly 204 for 204 endpoints, not "any 2xx". An endpoint that promises an empty response
        // and returns a page instead is a portal or an injected block page.
        if (targetUrl.contains("204")) code == 204 else code in 200..299

    private fun trace(tag: String, message: String) {
        logs?.trace(tag, message) ?: runCatching { android.util.Log.i(tag, message) }
    }

    private companion object {
        const val TAG = "YumiFailover"
        const val DEFAULT_TIMEOUT_MILLIS = 8_000
    }
}
