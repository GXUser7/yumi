package com.mydrop.vpn.core.model

/**
 * Tunnel lifecycle. [Connecting] is modelled as its own state rather than a boolean flag
 * because the connect screen animates a distinct handshake phase, and a failed handshake has
 * to be distinguishable from a clean disconnect.
 */
sealed interface VpnState {
    data object Disconnected : VpnState

    data class Connecting(val nodeId: String, val phase: Phase = Phase.Starting) : VpnState {
        enum class Phase(val label: String) {
            Starting("Запуск ядра"),
            Handshaking("Рукопожатие"),
            EstablishingTunnel("Поднятие туннеля"),
            Testing("Проверка соединения"),
        }
    }

    data class Connected(
        val nodeId: String,
        val connectedAtEpochMillis: Long,
    ) : VpnState

    data object Disconnecting : VpnState

    data class Failed(val nodeId: String?, val message: String) : VpnState

    val isActive: Boolean
        get() = this is Connected || this is Connecting

    val activeNodeId: String?
        get() = when (this) {
            is Connected -> nodeId
            is Connecting -> nodeId
            is Failed -> nodeId
            else -> null
        }
}

/**
 * Live counters. Rates are computed by the source that owns the sampling interval, so the UI
 * never has to differentiate byte totals across recompositions.
 */
data class TrafficStats(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val activeConnections: Int = 0,
) {
    companion object {
        val Zero = TrafficStats()
    }
}

data class LogEntry(
    val timestampMillis: Long,
    val level: Level,
    val message: String,
) {
    enum class Level { Trace, Debug, Info, Warn, Error }
}
