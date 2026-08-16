package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable

/**
 * REALITY turns the TLS handshake into a relay of a real third-party site's handshake, so the
 * client needs the server's x25519 public key and the short id it was provisioned with. Without
 * both, the handshake is indistinguishable from a plain failure — hence they are non-null here.
 */
@Serializable
data class RealityOptions(
    val publicKey: String,
    val shortId: String = "",
    val spiderX: String = "/",
)

@Serializable
data class EchOptions(
    val enabled: Boolean = true,
    val config: List<String> = emptyList(),
)

@Serializable
data class TlsOptions(
    val enabled: Boolean = true,
    val serverName: String? = null,
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    /** uTLS client hello to mimic: chrome, firefox, safari, ios, edge, random, randomized… */
    val fingerprint: String? = null,
    val reality: RealityOptions? = null,
    val certificate: String? = null,
    val ech: EchOptions? = null,
) {
    val isReality: Boolean get() = reality != null

    /** Short badge text for the server list: "REALITY", "TLS", or null when plaintext. */
    val badge: String?
        get() = when {
            !enabled -> null
            reality != null -> "REALITY"
            else -> "TLS"
        }
}
