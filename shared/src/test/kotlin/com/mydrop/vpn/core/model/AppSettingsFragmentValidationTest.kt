package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsFragmentValidationTest {

    @Test
    fun `normalizeRange trims valid numbers and ranges and rejects malformed inputs`() {
        assertEquals("42", AppSettings.normalizeRange(" 42 ", 1, 1500))
        assertEquals("10-20", AppSettings.normalizeRange(" 10-20 ", 1, 1500))
        assertEquals("7-7", AppSettings.normalizeRange("7-7", 1, 1500))

        assertNull(AppSettings.normalizeRange("", 1, 1500))
        assertNull(AppSettings.normalizeRange("   ", 1, 1500))
        assertNull(AppSettings.normalizeRange("0", 1, 1500))
        assertNull(AppSettings.normalizeRange("1501", 1, 1500))
        assertNull(AppSettings.normalizeRange("20-10", 1, 1500))
        assertNull(AppSettings.normalizeRange("-5", 0, 1000))
        assertNull(AppSettings.normalizeRange("10 - 20", 1, 1500))
        assertNull(AppSettings.normalizeRange("abc", 1, 1500))
        assertNull(AppSettings.normalizeRange("10-20-30", 1, 1500))
    }

    @Test
    fun `isValidFragmentLength enforces 1 to 1500`() {
        assertTrue(AppSettings.isValidFragmentLength("1"))
        assertTrue(AppSettings.isValidFragmentLength("1500"))
        assertTrue(AppSettings.isValidFragmentLength("10-20"))
        assertTrue(AppSettings.isValidFragmentLength(" 50-100 "))

        assertFalse(AppSettings.isValidFragmentLength(""))
        assertFalse(AppSettings.isValidFragmentLength("0"))
        assertFalse(AppSettings.isValidFragmentLength("0-10"))
        assertFalse(AppSettings.isValidFragmentLength("1501"))
        assertFalse(AppSettings.isValidFragmentLength("100-50"))
    }

    @Test
    fun `isValidFragmentInterval allows zero and up to 1000`() {
        assertTrue(AppSettings.isValidFragmentInterval("0"))
        assertTrue(AppSettings.isValidFragmentInterval("0-10"))
        assertTrue(AppSettings.isValidFragmentInterval("1000"))

        assertFalse(AppSettings.isValidFragmentInterval(""))
        assertFalse(AppSettings.isValidFragmentInterval("-1"))
        assertFalse(AppSettings.isValidFragmentInterval("1001"))
        assertFalse(AppSettings.isValidFragmentInterval("20-5"))
    }

    @Test
    fun `isValidFragmentMaxChunks permits blank unlimited or 1 to 1000`() {
        assertTrue(AppSettings.isValidFragmentMaxChunks(""))
        assertTrue(AppSettings.isValidFragmentMaxChunks("   "))
        assertTrue(AppSettings.isValidFragmentMaxChunks("1"))
        assertTrue(AppSettings.isValidFragmentMaxChunks("4-8"))
        assertTrue(AppSettings.isValidFragmentMaxChunks("1000"))

        assertFalse(AppSettings.isValidFragmentMaxChunks("0"))
        assertFalse(AppSettings.isValidFragmentMaxChunks("1001"))
        assertFalse(AppSettings.isValidFragmentMaxChunks("8-4"))
        assertFalse(AppSettings.isValidFragmentMaxChunks("unlimited"))
    }

    @Test
    fun `isValidFragmentPackets accepts blank, tlshello case-insensitively, and 1 to 1000`() {
        assertTrue(AppSettings.isValidFragmentPackets(""))
        assertTrue(AppSettings.isValidFragmentPackets("tlshello"))
        assertTrue(AppSettings.isValidFragmentPackets("TlsHello"))
        assertTrue(AppSettings.isValidFragmentPackets("1-3"))
        assertTrue(AppSettings.isValidFragmentPackets("5"))

        assertFalse(AppSettings.isValidFragmentPackets("0-3"))
        assertFalse(AppSettings.isValidFragmentPackets("1-1001"))
        assertFalse(AppSettings.isValidFragmentPackets("3-1"))
        assertFalse(AppSettings.isValidFragmentPackets("bogus"))
    }
}
