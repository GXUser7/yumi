package com.mydrop.vpn.vpn

/**
 * Thread-safe, memory-bounded rate limiter for [SplitTunnelUidPolicy] denial traces.
 *
 * Keeps `yumi.log` readable during connection bursts while surfacing fail-closed drops
 * (such as `INVALID_UID == -1`, Binder errors, or excluded UIDs) without logging IPs.
 */
class TrafficGuardRateLimiter(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(windowMillis >= 0L) { "windowMillis must be non-negative: $windowMillis" }
        require(maxKeys > 0) { "maxKeys must be positive: $maxKeys" }
    }

    private data class Key(
        val reason: SplitTunnelUidPolicy.DenyReason,
        val proto: Int,
        val dstPort: Int,
        val uid: Int,
    )

    private data class Entry(
        var lastEmittedAt: Long,
        var suppressed: Int = 0,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>()

    fun record(
        reason: SplitTunnelUidPolicy.DenyReason,
        proto: Int,
        dstPort: Int,
        uid: Int,
    ): String? {
        val now = nowMillis()
        val key = Key(reason, proto, dstPort, uid)
        synchronized(lock) {
            val existing = entries[key]
            if (existing != null) {
                if (now - existing.lastEmittedAt in 0L until windowMillis) {
                    existing.suppressed++
                    return null
                }
                val suppressed = existing.suppressed
                existing.lastEmittedAt = now
                existing.suppressed = 0
                return formatLine(reason, proto, dstPort, uid, suppressed)
            }

            if (entries.size >= maxKeys) {
                evictLocked(now)
            }
            entries[key] = Entry(lastEmittedAt = now)
            return formatLine(reason, proto, dstPort, uid, suppressed = 0)
        }
    }

    fun reset() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun evictLocked(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (now - entry.lastEmittedAt >= windowMillis || now < entry.lastEmittedAt) {
                it.remove()
            }
        }
        if (entries.size < maxKeys) return

        var oldestKey: Key? = null
        var oldestAt = Long.MAX_VALUE
        for ((k, v) in entries) {
            if (v.lastEmittedAt < oldestAt) {
                oldestAt = v.lastEmittedAt
                oldestKey = k
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey)
        }
    }

    private fun formatLine(
        reason: SplitTunnelUidPolicy.DenyReason,
        proto: Int,
        dstPort: Int,
        uid: Int,
        suppressed: Int,
    ): String {
        val base = "denied reason=${reason.wireName} proto=${protoName(proto)} dstPort=$dstPort uid=$uid"
        return if (suppressed > 0) "$base (+$suppressed suppressed)" else base
    }

    private fun protoName(proto: Int): String = when (proto) {
        SplitTunnelUidPolicy.PROTO_TCP -> "tcp"
        SplitTunnelUidPolicy.PROTO_UDP -> "udp"
        else -> proto.toString()
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 15_000L
        const val DEFAULT_MAX_KEYS = 64
    }
}
