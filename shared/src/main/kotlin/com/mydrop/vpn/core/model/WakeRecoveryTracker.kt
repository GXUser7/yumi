package com.mydrop.vpn.core.model

/**
 * Decides whether a waking tunnel should drop and rebuild the core's socket table.
 *
 * Switching physical networks already restarts the core because sockets pinned to a vanished
 * interface stay stalled until long TCP timeouts expire. Screen-off sleep and Android Doze produce
 * the same half-open zombies even when the interface keeps its name (`wlan0` -> `wlan0`): carrier
 * NAT mappings and upstream proxy, XHTTP, gRPC, or QUIC sessions silently expire while the CPU is
 * suspended, yet opening a fresh health-check probe succeeds and hides the stall from
 * [com.mydrop.vpn.data.FailoverWatchdog].
 *
 * Two guards prevent interrupting active background streams (music playback, calls, downloads):
 * - Brief screen locks shorter than [minSleepMillis] are ignored unless Android entered deep Doze.
 * - Non-Doze wakeups skip resetting when significant traffic was flowing within
 *   [activeTrafficQuietMillis] before wake.
 */
class WakeRecoveryTracker(
    private val minSleepMillis: Long = MIN_SLEEP_MILLIS,
    private val activeTrafficQuietMillis: Long = ACTIVE_TRAFFIC_QUIET_MILLIS,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
    private val significantTrafficTickBytes: Long = SIGNIFICANT_TRAFFIC_TICK_BYTES,
) {
    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data class Reset(val reason: String) : Decision
    }

    private var screenOn = true
    private var screenOffAtMillis: Long? = null
    private var sawDeviceIdle = false
    private var lastSignificantTrafficAtMillis: Long? = null
    private var lastResetAtMillis: Long? = null

    fun start(screenOn: Boolean, nowMillis: Long) {
        this.screenOn = screenOn
        screenOffAtMillis = if (screenOn) null else nowMillis
        sawDeviceIdle = false
        lastSignificantTrafficAtMillis = null
        lastResetAtMillis = null
    }

    fun onTrafficTick(deltaBytes: Long, nowMillis: Long) {
        if (deltaBytes >= significantTrafficTickBytes) {
            lastSignificantTrafficAtMillis = nowMillis
        }
    }

    fun onCoreReset(nowMillis: Long) {
        lastResetAtMillis = nowMillis
        sawDeviceIdle = false
    }

    fun onScreenOff(nowMillis: Long) {
        if (!screenOn) return
        screenOn = false
        screenOffAtMillis = nowMillis
    }

    fun onScreenOn(nowMillis: Long): Decision {
        val sleptSince = screenOffAtMillis
        screenOn = true
        screenOffAtMillis = null

        val wasIdle = sawDeviceIdle
        sawDeviceIdle = false

        if (inCooldown(nowMillis)) return Decision.Skip("cooldown")
        if (!wasIdle && hasRecentTraffic(nowMillis)) return Decision.Skip("active traffic")

        val reason = when {
            wasIdle -> "exited device idle before wake"
            sleptSince != null && nowMillis - sleptSince >= minSleepMillis ->
                "screen asleep ${(nowMillis - sleptSince) / 1000}s"
            else -> return Decision.Skip("short screen off")
        }
        lastResetAtMillis = nowMillis
        return Decision.Reset(reason)
    }

    fun onDeviceIdleChanged(isIdle: Boolean, nowMillis: Long): Decision {
        if (isIdle) {
            sawDeviceIdle = true
            return Decision.Skip("entered device idle")
        }
        if (!sawDeviceIdle) return Decision.Skip("not returning from device idle")
        sawDeviceIdle = false

        if (inCooldown(nowMillis)) return Decision.Skip("cooldown")
        if (hasRecentTraffic(nowMillis)) return Decision.Skip("active traffic")

        lastResetAtMillis = nowMillis
        return Decision.Reset("exited device idle")
    }

    private fun inCooldown(nowMillis: Long): Boolean {
        val resetAt = lastResetAtMillis ?: return false
        return nowMillis - resetAt < cooldownMillis
    }

    private fun hasRecentTraffic(nowMillis: Long): Boolean {
        val activeAt = lastSignificantTrafficAtMillis ?: return false
        return nowMillis - activeAt < activeTrafficQuietMillis
    }

    companion object {
        const val MIN_SLEEP_MILLIS = 30_000L
        const val ACTIVE_TRAFFIC_QUIET_MILLIS = 20_000L
        const val COOLDOWN_MILLIS = 45_000L
        const val SIGNIFICANT_TRAFFIC_TICK_BYTES = 4_096L
    }
}
