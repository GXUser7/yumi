package com.mydrop.vpn.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Per-protocol credentials and tuning. Everything shared across protocols lives on [ProxyNode]. */
@Serializable
sealed interface ProxySettings {

    val protocol: Protocol

    @Serializable
    @SerialName("vless")
    data class Vless(
        val uuid: String,
        /** "xtls-rprx-vision" or empty. Vision only makes sense over raw TCP + TLS/REALITY. */
        val flow: String = "",
        val packetEncoding: String = "xudp",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.VLESS
    }

    @Serializable
    @SerialName("vmess")
    data class Vmess(
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",
        val packetEncoding: String = "xudp",
        val globalPadding: Boolean = false,
        val authenticatedLength: Boolean = false,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.VMESS
    }

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        val password: String,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.TROJAN
    }

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        val method: String,
        val password: String,
        val plugin: String = "",
        val pluginOptions: String = "",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.SHADOWSOCKS
    }

    @Serializable
    @SerialName("shadowtls")
    data class ShadowTls(
        val version: Int = 3,
        val password: String = "",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.SHADOWTLS
    }

    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        val password: String,
        val obfsType: String = "",
        val obfsPassword: String = "",
        /** 0 means "let BBR figure it out", which is the right default for most links. */
        val upMbps: Int = 0,
        val downMbps: Int = 0,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.HYSTERIA2
    }

    @Serializable
    @SerialName("hysteria")
    data class Hysteria(
        val auth: String = "",
        val obfs: String = "",
        val upMbps: Int = 0,
        val downMbps: Int = 0,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.HYSTERIA
    }

    @Serializable
    @SerialName("tuic")
    data class Tuic(
        val uuid: String,
        val password: String,
        val congestionControl: String = "bbr",
        val udpRelayMode: String = "native",
        val zeroRttHandshake: Boolean = false,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.TUIC
    }

    @Serializable
    @SerialName("anytls")
    data class AnyTls(
        val password: String,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.ANYTLS
    }

    @Serializable
    @SerialName("wireguard")
    data class WireGuard(
        val privateKey: String,
        val peerPublicKey: String,
        val preSharedKey: String = "",
        val localAddresses: List<String> = emptyList(),
        val mtu: Int = 1408,
        val reserved: List<Int> = emptyList(),
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.WIREGUARD
    }

    @Serializable
    @SerialName("ssh")
    data class Ssh(
        val user: String = "root",
        val password: String = "",
        val privateKey: String = "",
        val privateKeyPassphrase: String = "",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.SSH
    }

    @Serializable
    @SerialName("http")
    data class Http(
        val username: String = "",
        val password: String = "",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.HTTP
    }

    @Serializable
    @SerialName("socks")
    data class Socks(
        val version: String = "5",
        val username: String = "",
        val password: String = "",
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.SOCKS
    }

    @Serializable
    @SerialName("direct")
    data object Direct : ProxySettings {
        override val protocol: Protocol get() = Protocol.DIRECT
    }

    /**
     * An outbound object kept exactly as it arrived, for configurations this model cannot express.
     *
     * [outbound] is the provider's own JSON — everything in it survives, including fields the app
     * has never heard of. The endpoint is still lifted out into [ProxyNode.server] and `port` so
     * the server list, the latency probe and the failover group keep working on it like any other
     * node; [declaredType] is what the document called the protocol, used for the badge.
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val outbound: String,
        val declaredType: String,
    ) : ProxySettings {
        override val protocol: Protocol get() = Protocol.RAW
    }
}
