package com.mydrop.vpn.core.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * One dialable server. A subscription refresh replaces nodes wholesale, so [id] is derived from
 * the connection tuple rather than random — that keeps the user's selection and measured latency
 * attached to the same server across refreshes instead of resetting on every update.
 */
@Serializable
data class ProxyNode(
    val id: String,
    val name: String,
    val server: String,
    val port: Int,
    val settings: ProxySettings,
    val tls: TlsOptions? = null,
    val transport: TransportOptions? = null,
    val multiplex: MultiplexOptions? = null,
    val subscriptionId: String? = null,
    /** The URI this node was parsed from, kept so the node can be re-shared losslessly. */
    val sourceUri: String? = null,
) {
    val protocol: Protocol get() = settings.protocol

    val address: String get() = "$server:$port"

    /** Badges shown under the server name: protocol details that actually change behaviour. */
    val badges: List<String>
        get() = buildList {
            (settings as? ProxySettings.Raw)?.declaredType
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it) }
            tls?.badge?.let(::add)
            // Skipping verification is a decision with consequences, taken per server. It belongs
            // where the list is read, not only inside the menu it was set from.
            tls?.takeIf { it.enabled && it.insecure && it.reality == null }?.let { add("no-verify") }
            transport?.label?.let(::add)
            (settings as? ProxySettings.Vless)
                ?.flow
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(if (it.contains("vision")) "Vision" else it) }
            tls?.fingerprint?.takeIf { it.isNotEmpty() }?.let { add("uTLS:$it") }
            if (multiplex?.enabled == true) add("Mux")
        }

    companion object {
        /**
         * Stable identity for a server: same endpoint + credentials ⇒ same id. Names are
         * excluded on purpose, because providers rename nodes constantly (load tags, flags,
         * expiry notices) and a name-sensitive id would orphan the selection every refresh.
         *
         * [subscriptionId] is part of the seed so two providers reselling the same endpoint stay
         * two rows. Without it their nodes collide, and a collision is not cosmetic: the id keys
         * the server list, the selection and the latency map, so duplicates crash the list and
         * make "which one did I pick" unanswerable. It is stable across refreshes because a
         * subscription keeps its own id for life.
         */
        fun stableId(
            server: String,
            port: Int,
            settings: ProxySettings,
            subscriptionId: String? = null,
        ): String {
            val scope = subscriptionId ?: "manual"
            val seed =
                "$scope|${settings.protocol.name}|$server|$port|${settings.credentialFingerprint()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        private fun ProxySettings.credentialFingerprint(): String = when (this) {
            is ProxySettings.Vless -> uuid
            is ProxySettings.Vmess -> uuid
            is ProxySettings.Trojan -> password
            is ProxySettings.Shadowsocks -> "$method:$password"
            is ProxySettings.ShadowTls -> password
            is ProxySettings.Hysteria2 -> password
            is ProxySettings.Hysteria -> auth
            is ProxySettings.Tuic -> "$uuid:$password"
            is ProxySettings.AnyTls -> password
            is ProxySettings.WireGuard -> peerPublicKey
            is ProxySettings.Ssh -> "$user:$password"
            is ProxySettings.Http -> "$username:$password"
            is ProxySettings.Socks -> "$username:$password"
            // The whole document: two raw outbounds differing anywhere at all are two servers,
            // and this side cannot know which of those fields are the credentials.
            is ProxySettings.Raw -> outbound
            ProxySettings.Direct -> "direct"
        }
    }
}

/** Latency measured for a node. Kept out of [ProxyNode] so a refresh does not wipe measurements. */
@Serializable
data class LatencyResult(
    val nodeId: String,
    val millis: Int,
    val measuredAtEpochMillis: Long,
    val failed: Boolean = false,
) {
    companion object {
        const val TIMEOUT_MILLIS = 5_000
    }
}
