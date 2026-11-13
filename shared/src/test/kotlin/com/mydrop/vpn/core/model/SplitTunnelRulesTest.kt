package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SplitTunnelRulesTest {
    @Test
    fun `reinstalling a selected package requires a new interface for either list mode`() {
        for (mode in listOf(SplitTunnelMode.AllowList, SplitTunnelMode.BlockList)) {
            val settings = AppSettings(splitTunnelMode = mode, splitTunnelPackages = setOf("app"))
            val before = SplitTunnelRules.resolve(settings) { 10001 }
            val after = SplitTunnelRules.resolve(settings) { 10042 }
            assertNotEquals(before, after)
            assertEquals(mapOf("app" to 10042), after.packageUids)
        }
    }

    @Test
    fun `a package installed after its rule was refused requires a new interface`() {
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.AllowList,
            splitTunnelPackages = setOf("app"),
        )
        assertNotEquals(
            SplitTunnelRules.resolve(settings) { null },
            SplitTunnelRules.resolve(settings) { 10001 },
        )
    }

    @Test
    fun `updates retaining the UID and changes to unrelated packages preserve the interface`() {
        val settings = AppSettings(
            splitTunnelMode = SplitTunnelMode.BlockList,
            splitTunnelPackages = setOf("app"),
        )
        val before = mapOf("app" to 10001, "other" to 10002)
        val after = mapOf("app" to 10001, "other" to 10042)
        assertEquals(
            SplitTunnelRules.resolve(settings, before::get),
            SplitTunnelRules.resolve(settings, after::get),
        )
    }

    @Test
    fun `package changes do not rebuild a tunnel with splitting disabled`() {
        val settings = AppSettings(splitTunnelPackages = setOf("app"))
        assertEquals(
            SplitTunnelRules.resolve(settings) { 10001 },
            SplitTunnelRules.resolve(settings) { null },
        )
    }
}
