package com.mydrop.vpn.core.model

import androidx.annotation.StringRes
import com.mydrop.vpn.shared.R

/**
 * Tunnel lifecycle. [Connecting] is modelled as its own state rather than a boolean flag
 * because the connect screen animates a distinct handshake phase, and a failed handshake has
 * to be distinguishable from a clean disconnect.
 */
sealed interface VpnState {
    data object Disconnected : VpnState

    data class Connecting(val nodeId: String, val phase: Phase = Phase.Starting) : VpnState {
        enum class Phase(@StringRes val labelRes: Int) {
            Starting(R.string.phase_starting),
            Handshaking(R.string.phase_handshaking),
            EstablishingTunnel(R.string.phase_establishing_tunnel),
            Testing(R.string.phase_testing),
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

    companion object {
        /**
         * Turns a sing-box log level into ours.
         *
         * The core numbers its levels the way logrus does — `Panic 0, Fatal 1, Error 2, Warn 3,
         * Info 4, Debug 5, Trace 6` — and this used to read them as syslog severities, where the
         * scale is longer and the numbers land elsewhere. Everything the core said therefore
         * arrived one step too severe: routine `INFO` lines were filed as warnings and painted as
         * such in the journal, while per-connection `TRACE` chatter was filed as `Info` and so
         * could not be told apart from anything worth reading.
         *
         * Anything past the known range is treated as trace rather than as an error: a level this
         * side does not recognise is a newer, more verbose one, not a more urgent one.
         */
        fun levelFromCore(level: Int): Level = when (level) {
            0, 1, 2 -> Level.Error
            3 -> Level.Warn
            4 -> Level.Info
            5 -> Level.Debug
            else -> Level.Trace
        }
    }
}
