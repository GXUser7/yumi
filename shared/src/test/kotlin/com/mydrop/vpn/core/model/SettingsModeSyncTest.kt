package com.mydrop.vpn.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModeSyncTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `defaults and legacy json use simple mode with recommended badges enabled`() {
        val defaults = AppSettings()
        assertEquals(SettingsMode.Simple, defaults.settingsMode)
        assertTrue(defaults.showRecommendedBadges)

        val legacy = json.decodeFromString(AppSettings.serializer(), "{}")
        assertEquals(SettingsMode.Simple, legacy.settingsMode)
        assertTrue(legacy.showRecommendedBadges)
    }

    @Test
    fun `round-trip json serialization preserves advanced mode and hidden badges`() {
        val custom = AppSettings(
            settingsMode = SettingsMode.Advanced,
            showRecommendedBadges = false,
        )
        val encoded = json.encodeToString(AppSettings.serializer(), custom)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(custom, decoded)
    }

    @Test
    fun `simpleBypassEnabled and withSimpleBypass stay in sync with fragmentMode`() {
        val initial = AppSettings()
        assertEquals(FragmentMode.Off, initial.fragmentMode)
        assertFalse(initial.simpleBypassEnabled)

        val enabled = initial.withSimpleBypass(true)
        assertEquals(FragmentMode.TcpSegments, enabled.fragmentMode)
        assertTrue(enabled.simpleBypassEnabled)

        val customMode = initial.copy(fragmentMode = FragmentMode.TlsHello)
        assertTrue(customMode.simpleBypassEnabled)
        assertEquals(FragmentMode.TlsHello, customMode.withSimpleBypass(true).fragmentMode)

        val disabled = customMode.withSimpleBypass(false)
        assertEquals(FragmentMode.Off, disabled.fragmentMode)
        assertFalse(disabled.simpleBypassEnabled)
    }

    @Test
    fun `simpleAutoUpdateEnabled and withSimpleAutoUpdate sync both subscription flags`() {
        val off = AppSettings().withSimpleAutoUpdate(false)
        assertFalse(off.subscriptionAutoUpdate)
        assertFalse(off.subscriptionUpdateOnStart)
        assertFalse(off.simpleAutoUpdateEnabled)

        assertTrue(off.copy(subscriptionAutoUpdate = true).simpleAutoUpdateEnabled)
        assertTrue(off.copy(subscriptionUpdateOnStart = true).simpleAutoUpdateEnabled)

        val on = off.withSimpleAutoUpdate(true)
        assertTrue(on.subscriptionAutoUpdate)
        assertTrue(on.subscriptionUpdateOnStart)
        assertTrue(on.simpleAutoUpdateEnabled)
    }

    @Test
    fun `simpleAlertsEnabled and withSimpleAlerts sync all alert flags`() {
        val off = AppSettings().withSimpleAlerts(false)
        assertFalse(off.alertServer)
        assertFalse(off.alertDns)
        assertFalse(off.alertLists)
        assertFalse(off.alertUpdate)
        assertFalse(off.simpleAlertsEnabled)

        assertTrue(off.copy(alertServer = true).simpleAlertsEnabled)
        assertTrue(off.copy(alertDns = true).simpleAlertsEnabled)
        assertTrue(off.copy(alertLists = true).simpleAlertsEnabled)
        assertTrue(off.copy(alertUpdate = true).simpleAlertsEnabled)

        val on = off.withSimpleAlerts(true)
        assertTrue(on.alertServer)
        assertTrue(on.alertDns)
        assertTrue(on.alertLists)
        assertTrue(on.alertUpdate)
        assertTrue(on.simpleAlertsEnabled)
    }
}
