package com.mydrop.vpn.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPortTest {

    @Test
    fun `host and port split on the single colon`() {
        assertEquals(HostPort("example.com", 443), splitHostPort("example.com:443"))
        assertEquals(HostPort("1.2.3.4", 8388), splitHostPort("1.2.3.4:8388"))
    }

    @Test
    fun `a host without a port keeps a null port`() {
        assertEquals(HostPort("example.com", null), splitHostPort("example.com"))
        assertEquals(HostPort("8.8.8.8", null), splitHostPort("8.8.8.8"))
    }

    /**
     * The regression this whole file exists for. Cutting on the last colon made this
     * `2001:db8:` on port 1 — a node that reached the server list and could never dial.
     */
    @Test
    fun `a bare IPv6 literal is taken whole rather than cut on its last colon`() {
        assertEquals(HostPort("2001:db8::1", null), splitHostPort("2001:db8::1"))
        assertEquals(
            HostPort("2606:4700:4700::1111", null),
            splitHostPort("2606:4700:4700::1111"),
        )
    }

    @Test
    fun `brackets separate an IPv6 host from its port`() {
        assertEquals(HostPort("2001:db8::1", 443), splitHostPort("[2001:db8::1]:443"))
        assertEquals(HostPort("2001:db8::1", null), splitHostPort("[2001:db8::1]"))
    }

    @Test
    fun `malformed input is refused instead of guessed at`() {
        assertNull(splitHostPort(""))
        assertNull(splitHostPort("   "))
        assertNull(splitHostPort("[2001:db8::1"))
        assertNull(splitHostPort("example.com:0"))
        assertNull(splitHostPort("example.com:70000"))
        assertNull(splitHostPort("example.com:https"))
        assertNull(splitHostPort(":443"))
    }

    @Test
    fun `IPv4 addresses are recognised`() {
        assertTrue(isNumericAddress("1.1.1.1"))
        assertTrue(isNumericAddress("8.8.8.8"))
        assertTrue(isNumericAddress("255.255.255.255"))
        assertTrue(isNumericAddress("0.0.0.0"))
    }

    @Test
    fun `IPv6 addresses are recognised, brackets or not`() {
        assertTrue(isNumericAddress("2606:4700:4700::1111"))
        assertTrue(isNumericAddress("[2606:4700:4700::1111]"))
        assertTrue(isNumericAddress("2620:fe::fe"))
        assertTrue(isNumericAddress("::1"))
        assertTrue(isNumericAddress("::"))
        assertTrue(isNumericAddress("2001:0db8:0000:0000:0000:0000:0000:0001"))
        assertTrue(isNumericAddress("::ffff:192.0.2.1"))
    }

    /**
     * The old check asked whether every character was a digit, a dot, a colon or a hex letter.
     * Both of these are made entirely of those, and both are hostnames.
     */
    @Test
    fun `hostnames spelled with hex letters are not addresses`() {
        assertFalse(isNumericAddress("dead.beef"))
        assertFalse(isNumericAddress("ad.cafe"))
        assertFalse(isNumericAddress("dns.adguard.com"))
        assertFalse(isNumericAddress("example.com"))
        assertFalse(isNumericAddress(""))
    }

    @Test
    fun `not-quite addresses are refused`() {
        assertFalse(isNumericAddress("256.1.1.1"))
        assertFalse(isNumericAddress("1.2.3"))
        assertFalse(isNumericAddress("1.2.3.4.5"))
        assertFalse(isNumericAddress("2001:db8::1::2"))
        assertFalse(isNumericAddress("12345::1"))
        assertFalse(isNumericAddress("1:2:3:4:5:6:7:8:9"))
    }
}
