package com.mydrop.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownBypassPolicyTest {

    @Test
    fun `allowBypass is enabled only when system lockdown is off`() {
        assertTrue(LockdownBypassPolicy.shouldAllowBypass(lockdownEnabled = false))
        assertFalse(LockdownBypassPolicy.shouldAllowBypass(lockdownEnabled = true))
    }

    @Test
    fun `needsTunRebuild triggers only when existing tun lockdown state diverges`() {
        assertFalse(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = null, currentLockdownEnabled = false))
        assertFalse(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = null, currentLockdownEnabled = true))
        assertFalse(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = false, currentLockdownEnabled = false))
        assertFalse(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = true, currentLockdownEnabled = true))

        assertTrue(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = false, currentLockdownEnabled = true))
        assertTrue(LockdownBypassPolicy.needsTunRebuild(tunLockdownEnabled = true, currentLockdownEnabled = false))
    }
}
