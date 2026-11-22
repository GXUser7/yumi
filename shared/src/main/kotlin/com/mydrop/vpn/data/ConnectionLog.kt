package com.mydrop.vpn.data

import com.mydrop.vpn.core.xray.XrayConfigFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer of live connections parsed from Xray access log lines, newest first.
 * Kept strictly in memory (never written to disk).
 */
class ConnectionLog(private val capacity: Int = 500) {

    enum class Route { Proxy, Direct, Block, Dns, Other }

    data class Entry(
        val atMillis: Long,
        val network: String,
        val host: String,
        val port: Int,
        val inbound: String,
        val outbound: String,
        val route: Route,
        val accepted: Boolean,
        val reason: String,
    ) {
        val nodeId: String?
            get() = outbound.takeIf { it.startsWith(NODE_PREFIX) }?.removePrefix(NODE_PREFIX)
    }

    private val ring = ArrayDeque<Entry>(capacity)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Parses [line] as an Xray access line and prepends it to [entries]. Returns true if matched. */
    fun offer(line: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val trimmed = line.trim()
        // Fast-path reject to avoid regex work on ordinary core/debug log lines.
        if (!trimmed.startsWith("from ")) return false
        val entry = parse(trimmed, nowMillis) ?: return false
        synchronized(this) {
            ring.addFirst(entry)
            while (ring.size > capacity) {
                ring.removeLast()
            }
            _entries.value = ring.toList()
        }
        return true
    }

    fun clear() {
        synchronized(this) {
            ring.clear()
            _entries.value = emptyList()
        }
    }

    companion object {
        private val NODE_PREFIX = XrayConfigFactory.nodeTag("")

        /**
         * Matches Xray's `AccessMessage.String()` (`common/log/access.go`):
         * `from <src> accepted|rejected [tcp:|udp:]<host>:<port> [<in> ->|>>|==> <out>] <reason>`.
         */
        private val ACCESS = Regex(
            """^from (\S+) (accepted|rejected) (?:(tcp|udp):)?(\S+?):(\d+)(?: \[([^\]]*)])?(?: (.*?))?(?: email: \S+)?$""",
        )
        private val DETOUR = Regex("""^(.*?)\s*(?:==>|->|>>)\s*(.+)$""")

        fun parse(line: String, nowMillis: Long): Entry? {
            val match = ACCESS.matchEntire(line.trim()) ?: return null
            val (_, status, network, host, port, detour, reason) = match.destructured
            val hop = DETOUR.matchEntire(detour.trim())
            val inbound = hop?.groupValues?.get(1)?.trim().orEmpty()
            val outbound = (hop?.groupValues?.get(2) ?: detour).trim()
            return Entry(
                atMillis = nowMillis,
                network = network.ifEmpty { "tcp" },
                host = host.removePrefix("[").removeSuffix("]"),
                port = port.toIntOrNull() ?: return null,
                inbound = inbound,
                outbound = outbound,
                route = routeOf(outbound),
                accepted = status == "accepted",
                reason = reason.trim(),
            )
        }

        fun routeOf(outbound: String): Route = when {
            outbound == XrayConfigFactory.BALANCER_TAG || outbound.startsWith(NODE_PREFIX) -> Route.Proxy
            outbound == XrayConfigFactory.DIRECT_TAG ||
                outbound == XrayConfigFactory.FRAGMENT_TAG ||
                outbound.startsWith("${XrayConfigFactory.FRAGMENT_TAG}-") -> Route.Direct
            outbound == XrayConfigFactory.BLOCK_TAG -> Route.Block
            outbound == XrayConfigFactory.DNS_TAG -> Route.Dns
            else -> Route.Other
        }
    }
}
