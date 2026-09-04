package com.mydrop.vpn.core.model

/**
 * The loopback proxy the app dials to reach its own tunnel.
 *
 * The service excludes this app from the tunnel it builds (`addDisallowedApplication`), which is
 * what keeps subscription refreshes and latency probes honest while the tunnel is half-up — and
 * what makes an in-app speed test measure the phone's own connection instead of the server it is
 * supposed to be rating. Dialling this inbound hands the request to the core, which carries it out
 * through the selected server like any other traffic.
 *
 * The credentials are generated per connection and the inbound listens on loopback only. Without
 * them any application on the phone could use the running tunnel as an open proxy.
 */
data class ProbeEndpoint(
    val port: Int,
    val username: String,
    val password: String,
)

/**
 * The two hosts the app asks its own tunnel for, and the reason there are two of them.
 *
 * [TUNNEL] answers "does the tunnel carry traffic". Its name never reaches a resolver: the core
 * hands the hostname to the outbound and the server resolves it, so this probe passes while the
 * phone's DNS is entirely dead. That is deliberate — a switch to another server cannot fix a
 * broken resolver, and a probe that failed for that reason would send the watchdog hunting through
 * every server in the subscription for a fault none of them has.
 *
 * [DNS] answers "does the resolver work". It is the same request to a different host, and the
 * routing configuration carries one extra rule for it: `action: resolve`, which makes the core
 * resolve the name through its own DNS pipeline before dialling. So the only difference between
 * the two verdicts is the resolver, which is what makes the pair diagnostic rather than redundant.
 *
 * Both are boring endpoints that answer 204 with no body, run by operators whose business is
 * answering them quickly. Neither has to be reachable from Russia: the request is made from the
 * server's location, not the phone's.
 */
object ProbeTargets {
    const val TUNNEL = "cp.cloudflare.com"
    const val DNS = "connectivitycheck.gstatic.com"

    fun url(host: String) = "http://$host/generate_204"
}
