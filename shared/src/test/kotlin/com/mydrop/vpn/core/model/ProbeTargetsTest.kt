package com.mydrop.vpn.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTargetsTest {

    @Test
    fun `isValidProbeUrl accepts http and https urls with host`() {
        assertTrue(ProbeTargets.isValidProbeUrl(ProbeTargets.DEFAULT_URL))
        assertTrue(ProbeTargets.isValidProbeUrl("https://www.gstatic.com/generate_204"))
        assertTrue(ProbeTargets.isValidProbeUrl("  HTTP://1.1.1.1:8080/check  "))
        assertTrue(ProbeTargets.isValidProbeUrl("https://[2606:4700::6810:84e5]/generate_204"))
    }

    @Test
    fun `isValidProbeUrl rejects blank, non-http schemes, and missing hosts`() {
        assertFalse(ProbeTargets.isValidProbeUrl(""))
        assertFalse(ProbeTargets.isValidProbeUrl("   "))
        assertFalse(ProbeTargets.isValidProbeUrl("ftp://example.com/file"))
        assertFalse(ProbeTargets.isValidProbeUrl("cp.cloudflare.com/generate_204"))
        assertFalse(ProbeTargets.isValidProbeUrl("http://"))
        assertFalse(ProbeTargets.isValidProbeUrl("https:///generate_204"))
        assertFalse(ProbeTargets.isValidProbeUrl("http://not valid host/path"))
    }

    @Test
    fun `AppSettings defaults and serialization preserve new fields`() {
        val defaults = AppSettings()
        assertEquals(ProbeTargets.DEFAULT_URL, defaults.probeUrl)
        assertEquals(LatencyResult.TIMEOUT_MILLIS, defaults.probeTimeoutMillis)
        assertTrue(defaults.subscriptionUpdateOnStart)
        assertTrue(defaults.resolveServerAddress)
        assertTrue(defaults.providerRouting)
        assertTrue(defaults.blockQuic)
        assertTrue(defaults.dynamicColor)
        assertTrue(defaults.preferOrdinaryOnCellular)

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val legacy = json.decodeFromString(AppSettings.serializer(), "{}")
        assertEquals(ProbeTargets.DEFAULT_URL, legacy.probeUrl)
        assertEquals(LatencyResult.TIMEOUT_MILLIS, legacy.probeTimeoutMillis)
        assertTrue(legacy.subscriptionUpdateOnStart)

        val custom = AppSettings(
            probeUrl = "https://example.com/204",
            probeTimeoutMillis = 2_500,
            subscriptionUpdateOnStart = false,
        )
        val encoded = json.encodeToString(AppSettings.serializer(), custom)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(custom, decoded)
    }
}
