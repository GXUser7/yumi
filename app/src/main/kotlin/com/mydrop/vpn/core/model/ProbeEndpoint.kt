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
