package com.mydrop.vpn.ui.screens.apps

import com.mydrop.vpn.vpn.SplitTunnelUidPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelScreenFilterTest {

    @Test
    fun `SplitTunnelScreen excludes Yumi package and always-outside telephony packages`() {
        val ownPackage = "com.mydrop.vpn"
        assertFalse(isSplitTunnelSelectable(ownPackage, ownPackage))
        for (pkg in SplitTunnelUidPolicy.ALWAYS_OUTSIDE_PACKAGES) {
            assertFalse("$pkg must not be selectable", isSplitTunnelSelectable(pkg, ownPackage))
        }
        assertTrue(isSplitTunnelSelectable("com.example.browser", ownPackage))

        val input = listOf(
            InstalledApp(ownPackage, "Yumi", isSystem = false),
            InstalledApp("com.android.phone", "Phone Services", isSystem = true),
            InstalledApp("com.shannon.imsservice", "IMS Service", isSystem = true),
            InstalledApp("com.example.browser", "Browser", isSystem = false),
        )
        val filtered = filterSelectableApps(input, ownPackage)
        assertEquals(listOf("com.example.browser"), filtered.map { it.packageName })
    }
}
