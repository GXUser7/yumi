package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRecoveryTrackerTest {

    @Test
    fun `waking after prolonged quiet sleep requests core reset`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = true, nowMillis = 1_000L)
        tracker.onScreenOff(nowMillis = 10_000L)
        // Small background keepalive ping must not count as an active media stream.
        tracker.onTrafficTick(deltaBytes = 512L, nowMillis = 39_000L)

        val decision = tracker.onScreenOn(nowMillis = 45_000L)
        assertTrue("Expected Reset after 35s sleep, got $decision", decision is WakeRecoveryTracker.Decision.Reset)
    }

    @Test
    fun `brief screen lock under minimum sleep threshold skips reset`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = true, nowMillis = 0L)
        tracker.onScreenOff(nowMillis = 10_000L)

        val decision = tracker.onScreenOn(nowMillis = 25_000L)
        assertEquals(WakeRecoveryTracker.Decision.Skip("short screen off"), decision)
    }

    @Test
    fun `active background stream while screen is off skips wake reset`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = true, nowMillis = 0L)
        tracker.onScreenOff(nowMillis = 10_000L)
        tracker.onTrafficTick(deltaBytes = 16_384L, nowMillis = 190_000L)

        val decision = tracker.onScreenOn(nowMillis = 200_000L)
        assertEquals(WakeRecoveryTracker.Decision.Skip("active traffic"), decision)
    }

    @Test
    fun `exiting deep device idle requests reset with screen still off`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = false, nowMillis = 0L)

        assertEquals(
            WakeRecoveryTracker.Decision.Skip("entered device idle"),
            tracker.onDeviceIdleChanged(isIdle = true, nowMillis = 10_000L),
        )
        val decision = tracker.onDeviceIdleChanged(isIdle = false, nowMillis = 20_000L)
        assertEquals(WakeRecoveryTracker.Decision.Reset("exited device idle"), decision)
    }

    @Test
    fun `device idle observed before screen on resets even if exit broadcast was missed`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = true, nowMillis = 0L)
        tracker.onScreenOff(nowMillis = 10_000L)
        tracker.onDeviceIdleChanged(isIdle = true, nowMillis = 15_000L)
        // Even with a recent tick and <30s off, returning from deep idle forces reset.
        tracker.onTrafficTick(deltaBytes = 8_192L, nowMillis = 20_000L)

        val decision = tracker.onScreenOn(nowMillis = 25_000L)
        assertEquals(WakeRecoveryTracker.Decision.Reset("exited device idle before wake"), decision)
    }

    @Test
    fun `handover reset triggers cooldown and prevents redundant wake reset`() {
        val tracker = WakeRecoveryTracker()
        tracker.start(screenOn = true, nowMillis = 0L)
        tracker.onScreenOff(nowMillis = 10_000L)
        tracker.onCoreReset(nowMillis = 90_000L)

        val decision = tracker.onScreenOn(nowMillis = 100_000L)
        assertEquals(WakeRecoveryTracker.Decision.Skip("cooldown"), decision)
    }
}
