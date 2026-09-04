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
         *
         * Takes the whole node rather than the four fields it used to, and that is the point: an
         * id computed from a subset silently stops distinguishing whatever the subset leaves out.
         * See [dialDiscriminator] for what that cost.
         */
        fun stableId(node: ProxyNode): String {
            val scope = node.subscriptionId ?: "manual"
            val settings = node.settings
            val seed = buildString {
                append(scope).append('|')
                append(settings.protocol.name).append('|')
                append(node.server).append('|')
                append(node.port).append('|')
                append(settings.credentialFingerprint())
                append('|').append(node.dialDiscriminator())
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        /**
         * What else makes two servers on the same endpoint two servers.
         *
         * The seed used to stop at the credentials, and that was wrong in a way only a real
         * subscription showed: a provider offering the same host, port and key over several
         * transports produced several nodes with one id, and [distinctById] kept whichever came
         * first. In one subscription of a hundred and twenty-one servers, twelve never reached the
         * list — no error, no line in the journal, just a shorter list than the provider's.
         *
         * Only what changes how the server is dialled goes in. The uTLS fingerprint and the ALPN
         * list do not: they are how this side presents itself, not which endpoint it reaches, and
         * putting them here would split one server into two whenever a provider re-issued its links
         * with a different disguise.
         */
        private fun ProxyNode.dialDiscriminator(): String = buildString {
            transport?.let { t ->
                append(t.label)
                when (t) {
                    is TransportOptions.WebSocket -> append(t.path).append(t.headers["Host"].orEmpty())
                    is TransportOptions.Grpc -> append(t.serviceName)
                    is TransportOptions.HttpUpgrade -> append(t.host).append(t.path)
                    is TransportOptions.Xhttp -> append(t.host).append(t.path).append(t.mode)
                    is TransportOptions.Http -> append(t.host.joinToString(",")).append(t.path)
                    TransportOptions.Quic -> Unit
                }
            }
            tls?.takeIf { it.enabled }?.let { t ->
                append('|').append(t.serverName.orEmpty())
                t.reality?.let { append('|').append(it.publicKey).append(it.shortId) }
                if (t.pinnedCertSha256.isNotBlank()) append('|').append(t.pinnedCertSha256)
            }
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

/**
 * The same node with the id its contents imply.
 *
 * Every parser builds a node and then needs an id for it. Doing it this way round — build, then
 * identify — is what keeps the id and the node from disagreeing: there is no argument list to
 * forget a field in.
 */
fun ProxyNode.identified(): ProxyNode = copy(id = ProxyNode.stableId(this))

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
