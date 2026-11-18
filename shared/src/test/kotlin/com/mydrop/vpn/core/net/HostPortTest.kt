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
        assertTrue(isNumericAddress("2001:db8:abcd::203.0.113.77"))
        assertTrue(isNumericAddress("64:ff9b::203.0.113.77"))
        assertTrue(isNumericAddress("::203.0.113.77"))
        assertTrue(isNumericAddress("1:2:3:4:5::203.0.113.77"))
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
        assertFalse(isNumericAddress("1:2:3:4:5:6::203.0.113.77"))
        assertFalse(isNumericAddress("2001:db8:::203.0.113.77"))
        assertFalse(isNumericAddress(":203.0.113.77"))
        assertFalse(isNumericAddress("1:2:3:4:5::6:203.0.113.77"))
        assertFalse(isNumericAddress("2001:db8:abcd::999.0.113.77"))
    }

    @Test
    fun `extractAuthorityHost strips IPv6 zone ids including percent-25`() {
        assertEquals("::1", extractAuthorityHost("http://[::1%25lo0]:8080/secret"))
        assertEquals("::1", extractAuthorityHost("[::1%lo0]:8080"))
        assertEquals("::1", extractAuthorityHost("::1%lo0"))
        assertEquals("fe80::1", extractAuthorityHost("https://[fe80::1%25wlan0]:443/dns-query"))
    }

    @Test
    fun `isLoopbackOrUnspecifiedHost and isForbiddenDnsHost catch inet_aton non-canonical IPv4 and zoned IPv6`() {
        val blocked = listOf(
            "127.1",
            "127.0.1",
            "0177.0.0.1",
            "0177.1",
            "017700000001",
            "0x7f.0.0.1",
            "0x7f.1",
            "0x7f000001",
            "2130706433",
            "0",
            "0.1",
            "0000.0.0.0",
            "0x0.0.0.0",
            "::1%lo0",
            "[::1%25lo0]",
            "::%lo0",
            "[::%25lo0]",
            "::ffff:127.1",
            "::ffff:0177.0.0.1",
            "::127.0.0.1",
            "::127.1",
            "::0.0.0.0",
            "0:0:0:0:0:0:127.0.0.1",
            "0:0:0:0:0:0:0.0.0.0",
            "::ffff:0.0.0.0",
        )
        for (host in blocked) {
            assertTrue("isLoopbackOrUnspecifiedHost must reject '$host'", isLoopbackOrUnspecifiedHost(host))
            assertTrue("isForbiddenDnsHost must reject '$host'", isForbiddenDnsHost(host))
        }

        val allowed = listOf(
            "8.8.8.8",
            "1.1.1.1",
            "0x08.0x08.0x08.0x08",
            "dns.google",
            "2606:4700:4700::1111",
        )
        for (host in allowed) {
            assertFalse("isLoopbackOrUnspecifiedHost must allow '$host'", isLoopbackOrUnspecifiedHost(host))
            assertFalse("isForbiddenDnsHost must allow '$host'", isForbiddenDnsHost(host))
        }
    }
}
