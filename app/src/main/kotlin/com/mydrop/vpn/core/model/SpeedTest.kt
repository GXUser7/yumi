package com.mydrop.vpn.core.model

import androidx.annotation.StringRes
import com.mydrop.vpn.R

/** Stages of one measurement, in the order they run. */
enum class SpeedPhase(@StringRes val labelRes: Int) {
    Idle(R.string.speed_phase_idle),
    Latency(R.string.speed_phase_latency),
    Download(R.string.speed_phase_download),
    Upload(R.string.speed_phase_upload),
    Done(R.string.speed_phase_done),
    Failed(R.string.speed_phase_failed),
}

/**
 * One speed test, from the first packet to the last.
 *
 * [liveBytesPerSecond] is what the gauge shows while a phase runs; the download and upload figures
 * are what that phase settled on and stay on screen afterwards.
 */
data class SpeedTestState(
    val phase: SpeedPhase = SpeedPhase.Idle,
    val liveBytesPerSecond: Long = 0,
    /** How far through the current phase, 0..1. */
    val phaseProgress: Float = 0f,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val latencyMillis: Int = 0,
    /** Spread between consecutive round trips — a steady link and a jittery one differ here. */
    val jitterMillis: Int = 0,
    /**
     * Whether the traffic went through the tunnel. False means the tunnel was down and the figures
     * describe the phone's own connection, which is a different thing and has to be said so.
     */
    val throughTunnel: Boolean = false,
    val serverName: String? = null,
    val message: String? = null,
    /**
     * Every sample of the phase in flight, oldest first, so the trace can be drawn as it happens
     * rather than summarised once it is over. Reset when a phase starts.
     */
    val series: List<Long> = emptyList(),
    /**
     * The finished traces, kept once their phase is over. An average alone cannot tell a steady
     * link from one that burst and stalled to the same mean, and that difference is usually the
     * answer to "why does it feel slow".
     */
    val downloadSeries: List<Long> = emptyList(),
    val uploadSeries: List<Long> = emptyList(),
) {
    val running: Boolean
        get() = phase == SpeedPhase.Latency ||
            phase == SpeedPhase.Download ||
            phase == SpeedPhase.Upload

    val hasResult: Boolean get() = downloadBytesPerSecond > 0 || uploadBytesPerSecond > 0
}
