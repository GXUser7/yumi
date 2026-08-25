package com.mydrop.vpn.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outbound protocols the app can carry. The set is deliberately the sing-box outbound
 * catalogue rather than a UI-only enum, so a node parsed from a subscription maps 1:1 onto
 * something the core can actually dial.
 *
 * [label] stays plain text rather than a string resource: every one of these is the protocol's
 * own name and reads identically in any language.
 */
@Serializable
enum class Protocol(val label: String, val uriScheme: String) {
    @SerialName("vless")
    VLESS("VLESS", "vless"),

    @SerialName("vmess")
    VMESS("VMess", "vmess"),

    @SerialName("trojan")
    TROJAN("Trojan", "trojan"),

    @SerialName("shadowsocks")
    SHADOWSOCKS("Shadowsocks", "ss"),

    @SerialName("shadowtls")
    SHADOWTLS("ShadowTLS", "shadowtls"),

    @SerialName("hysteria2")
    HYSTERIA2("Hysteria2", "hysteria2"),

    @SerialName("hysteria")
    HYSTERIA("Hysteria", "hysteria"),

    @SerialName("tuic")
    TUIC("TUIC", "tuic"),

    @SerialName("anytls")
    ANYTLS("AnyTLS", "anytls"),

    @SerialName("wireguard")
    WIREGUARD("WireGuard", "wireguard"),

    @SerialName("ssh")
    SSH("SSH", "ssh"),

    @SerialName("http")
    HTTP("HTTP", "http"),

    @SerialName("socks")
    SOCKS("SOCKS5", "socks"),

    @SerialName("direct")
    DIRECT("Direct", "direct"),

    /**
     * An outbound carried through exactly as its author wrote it.
     *
     * The typed variants above exist because the app does things with a server beyond dialling it
     * — naming, pinging, sorting, failover — and those need the endpoint in fields. But a
     * configuration can say more than this model can hold: chained outbounds, multiplexing, ECH,
     * port hopping. Refusing those would mean refusing perfectly valid subscriptions, so they are
     * kept verbatim and handed to the core untouched.
     */
    @SerialName("raw")
    RAW("Raw", "raw"),
    ;

    /** True for protocols that ride QUIC and therefore need UDP to be reachable. */
    val isQuicBased: Boolean
        get() = this == HYSTERIA2 || this == HYSTERIA || this == TUIC

    companion object {
        private val bySchemeAlias: Map<String, Protocol> = buildMap {
            // Qualified on purpose: inside buildMap, a bare `entries` resolves to the
            // MutableMap's own entries rather than the enum's.
            Protocol.entries.forEach { put(it.uriScheme, it) }
            put("hy2", HYSTERIA2)
            put("hy", HYSTERIA)
            put("socks5", SOCKS)
            put("socks4", SOCKS)
            put("https", HTTP)
            put("wg", WIREGUARD)
        }

        fun fromScheme(scheme: String): Protocol? = bySchemeAlias[scheme.lowercase()]
    }
}
